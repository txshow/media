/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.extractor.mmt;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.Math.max;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.util.SparseArray;
import android.util.SparseIntArray;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput;
import java.io.EOFException;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Arrays;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Extracts MMT (MPEG Media Transport) packets carried in ARIB TLV packets.
 *
 * <p>The extractor accepts a stream that starts between TLV packet boundaries, as commonly happens
 * when connecting to a live HTTP relay.
 */
@UnstableApi
public final class TlvExtractor implements Extractor {

  private static final SeekMap LIVE_SEEK_MAP = new SeekMap.Unseekable(C.TIME_UNSET);

  /** Creates a factory using the supplied subtitle parser. */
  public static ExtractorsFactory newFactory(SubtitleParser.Factory subtitleParserFactory) {
    return () -> new Extractor[] {new TlvExtractor(subtitleParserFactory, /* flags= */ 0)};
  }

  /** Flags controlling the extractor. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef(
      flag = true,
      value = {FLAG_EMIT_RAW_SUBTITLE_DATA})
  public @interface Flags {}

  /**
   * Uses the source TTML samples without transcoding them to {@link
   * MimeTypes#APPLICATION_MEDIA3_CUES}.
   */
  public static final int FLAG_EMIT_RAW_SUBTITLE_DATA = 1;

  /**
   * @deprecated Use {@link #newFactory(SubtitleParser.Factory)} instead.
   */
  @Deprecated
  public static final ExtractorsFactory FACTORY =
      () ->
          new Extractor[] {
            new TlvExtractor(SubtitleParser.Factory.UNSUPPORTED, FLAG_EMIT_RAW_SUBTITLE_DATA)
          };

  private static final int TLV_SYNC_BYTE = 0x7F;

  private static final int TLV_PACKET_TYPE_UNDEFINED = 0x00;
  private static final int TLV_PACKET_TYPE_IPV4 = 0x01;
  private static final int TLV_PACKET_TYPE_IPV6 = 0x02;
  private static final int TLV_PACKET_TYPE_COMPRESSED_IP = 0x03;
  private static final int TLV_PACKET_TYPE_SIGNALLING = 0xFE;
  private static final int TLV_PACKET_TYPE_NULL = 0xFF;

  private static final int CONTEXT_TYPE_PARTIAL_IPV4_AND_UDP = 0x20;
  private static final int CONTEXT_TYPE_IPV4 = 0x21;
  private static final int CONTEXT_TYPE_PARTIAL_IPV6_AND_UDP = 0x60;
  private static final int CONTEXT_TYPE_NO_COMPRESSED_HEADER = 0x61;

  private static final int TLV_HEADER_SIZE = 4;
  private static final int MAX_TLV_PACKET_SIZE = TLV_HEADER_SIZE + 0xFFFF;
  private static final int MAX_SYNC_SEARCH_BYTES = 2 * MAX_TLV_PACKET_SIZE + TLV_HEADER_SIZE;
  private static final int SYNC_SEARCH_SKIP_BYTES = MAX_TLV_PACKET_SIZE;
  private static final int SYNC_SEARCH_CHUNK_SIZE = 4096;
  private static final int MAX_CONTEXTS = 8;
  private static final int UNCOMPRESSED_CONTEXT_ID = 0x1000;

  private static final int IP_PROTOCOL_UDP = 17;
  private static final int UDP_PORT_NTP = 123;
  private static final int IPV4_MIN_HEADER_SIZE = 20;
  private static final int IPV6_HEADER_SIZE = 40;
  private static final int UDP_HEADER_SIZE = 8;
  private static final int NTP_PACKET_SIZE = 48;
  private static final int PARTIAL_IPV4_AND_UDP_HEADER_SIZE = 20;
  private static final int IPV4_IDENTIFICATION_SIZE = 2;
  private static final int PARTIAL_IPV6_AND_UDP_HEADER_SIZE = 42;

  private static final int COMPRESSED_IP_PROTOCOL_UNKNOWN = 0;
  private static final int COMPRESSED_IP_PROTOCOL_MMTP = 1;
  private static final int COMPRESSED_IP_PROTOCOL_NTP = 2;
  private static final int COMPRESSED_IP_PROTOCOL_UNSUPPORTED = 3;
  private static final int MMT_DATA_TRACK_ID = 0x7F000000;
  private static final int TLV_SI_ASSET_TYPE = 0x746C7673;
  private static final int MMT_DATA_HEADER_SIZE = 10 * Integer.BYTES;

  private final SubtitleParser.Factory subtitleParserFactory;
  private final boolean parseSubtitlesDuringExtraction;
  private final ParsableByteArray tlvHeader;
  private final ParsableByteArray tlvPayload;
  private final byte[] syncSearchBuffer;
  private final SparseArray<MmtpReader> readersByContextId;
  private final SparseIntArray compressedIpProtocols;
  private final ArrayList<RawIpFlow> rawIpFlows;
  private final MmtTimestampAdjuster timestampAdjuster;

  private @MonotonicNonNull ExtractorOutput output;
  private @MonotonicNonNull ExtractorOutput readerOutput;
  private @MonotonicNonNull TrackOutput dataOutput;
  private boolean synchronizedToPackets;
  private boolean syncSearchReachedEnd;
  private boolean tracksEnded;
  private boolean inputEnded;
  private long tlvSiSequenceNumber;

  /** Creates an extractor that emits raw TTML subtitle samples. */
  public TlvExtractor() {
    this(SubtitleParser.Factory.UNSUPPORTED, FLAG_EMIT_RAW_SUBTITLE_DATA);
  }

  /**
   * Creates an extractor.
   *
   * @param subtitleParserFactory Parser used when subtitle transcoding is enabled.
   * @param flags Flags controlling extraction.
   */
  public TlvExtractor(SubtitleParser.Factory subtitleParserFactory, @Flags int flags) {
    this.subtitleParserFactory = subtitleParserFactory;
    parseSubtitlesDuringExtraction = (flags & FLAG_EMIT_RAW_SUBTITLE_DATA) == 0;
    tlvHeader = new ParsableByteArray(TLV_HEADER_SIZE);
    tlvPayload = new ParsableByteArray(/* limit= */ 0);
    syncSearchBuffer = new byte[MAX_SYNC_SEARCH_BYTES];
    readersByContextId = new SparseArray<>();
    compressedIpProtocols = new SparseIntArray();
    rawIpFlows = new ArrayList<>();
    timestampAdjuster = new MmtTimestampAdjuster();
  }

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    return findPacketBoundary(input, /* allowSinglePacketAtEnd= */ true) >= 0;
  }

  @Override
  public void init(ExtractorOutput output) {
    this.output = output;
    readerOutput =
        parseSubtitlesDuringExtraction
            ? new SubtitleTranscodingExtractorOutput(output, subtitleParserFactory)
            : output;
    dataOutput = readerOutput.track(MMT_DATA_TRACK_ID, C.TRACK_TYPE_METADATA);
    dataOutput.format(
        new Format.Builder()
            .setId("mmt-data")
            .setContainerMimeType(MimeTypes.VIDEO_MMT_TLV)
            .setSampleMimeType(MimeTypes.APPLICATION_MMT_DATA)
            .build());
    output.seekMap(LIVE_SEEK_MAP);
  }

  @Override
  public @ReadResult int read(ExtractorInput input, PositionHolder seekPosition)
      throws IOException {
    checkNotNull(output);
    checkNotNull(readerOutput);
    if (!ensurePacketBoundary(input)) {
      if (syncSearchReachedEnd) {
        endInput();
        return RESULT_END_OF_INPUT;
      }
      input.skip(SYNC_SEARCH_SKIP_BYTES);
      return RESULT_CONTINUE;
    }

    if (!input.readFully(
        tlvHeader.getData(), /* offset= */ 0, TLV_HEADER_SIZE, /* allowEndOfInput= */ true)) {
      endInput();
      return RESULT_END_OF_INPUT;
    }
    tlvHeader.setPosition(0);
    int syncByte = tlvHeader.readUnsignedByte();
    int packetType = tlvHeader.readUnsignedByte();
    int dataLength = tlvHeader.readUnsignedShort();
    if (syncByte != TLV_SYNC_BYTE || !isKnownPacketType(packetType)) {
      synchronizedToPackets = false;
      return RESULT_CONTINUE;
    }

    if (!readPayload(input, dataLength)) {
      endInput();
      return RESULT_END_OF_INPUT;
    }
    processTlvPayload(packetType, tlvPayload);
    maybeEndTracks();
    return RESULT_CONTINUE;
  }

  @Override
  public void seek(long position, long timeUs) {
    synchronizedToPackets = false;
    inputEnded = false;
    compressedIpProtocols.clear();
    rawIpFlows.clear();
    timestampAdjuster.reset();
    for (int i = 0; i < readersByContextId.size(); i++) {
      readersByContextId.valueAt(i).seek();
    }
    if (readerOutput instanceof SubtitleTranscodingExtractorOutput) {
      ((SubtitleTranscodingExtractorOutput) readerOutput).resetSubtitleParsers();
    }
  }

  @Override
  public void release() {
    // Do nothing.
  }

  private boolean ensurePacketBoundary(ExtractorInput input) throws IOException {
    if (synchronizedToPackets) {
      byte[] header = tlvHeader.getData();
      try {
        if (!input.peekFully(
            header, /* offset= */ 0, TLV_HEADER_SIZE, /* allowEndOfInput= */ true)) {
          syncSearchReachedEnd = true;
          return false;
        }
      } catch (EOFException e) {
        syncSearchReachedEnd = true;
        return false;
      } finally {
        input.resetPeekPosition();
      }
      if (isPlausibleHeader(header, /* offset= */ 0)) {
        return true;
      }
      synchronizedToPackets = false;
    }

    int syncOffset = findPacketBoundary(input, /* allowSinglePacketAtEnd= */ true);
    if (syncOffset < 0) {
      return false;
    }
    if (syncOffset > 0) {
      input.skipFully(syncOffset);
    }
    synchronizedToPackets = true;
    return true;
  }

  private int findPacketBoundary(ExtractorInput input, boolean allowSinglePacketAtEnd)
      throws IOException {
    int bytesPeeked = 0;
    syncSearchReachedEnd = false;
    input.resetPeekPosition();
    try {
      while (bytesPeeked < syncSearchBuffer.length) {
        int bytesRead =
            input.peek(
                syncSearchBuffer,
                bytesPeeked,
                Math.min(SYNC_SEARCH_CHUNK_SIZE, syncSearchBuffer.length - bytesPeeked));
        if (bytesRead == C.RESULT_END_OF_INPUT) {
          syncSearchReachedEnd = true;
          break;
        }
        bytesPeeked += bytesRead;
        int syncOffset = findChainedPacketBoundary(syncSearchBuffer, bytesPeeked);
        if (syncOffset >= 0) {
          return syncOffset;
        }
      }
      return allowSinglePacketAtEnd && syncSearchReachedEnd
          ? findSingleCompletePacket(syncSearchBuffer, bytesPeeked)
          : C.INDEX_UNSET;
    } finally {
      input.resetPeekPosition();
    }
  }

  private boolean readPayload(ExtractorInput input, int dataLength) throws IOException {
    if (dataLength > tlvPayload.capacity()) {
      tlvPayload.reset(new byte[max(tlvPayload.capacity() * 2, dataLength)], dataLength);
    } else {
      tlvPayload.setPosition(0);
      tlvPayload.setLimit(dataLength);
    }
    if (dataLength == 0) {
      return true;
    }
    try {
      input.readFully(tlvPayload.getData(), /* offset= */ 0, dataLength);
      tlvPayload.setPosition(0);
      return true;
    } catch (EOFException e) {
      return false;
    }
  }

  private void processTlvPayload(int packetType, ParsableByteArray payload) {
    switch (packetType) {
      case TLV_PACKET_TYPE_IPV4:
        processIpv4Packet(payload);
        break;
      case TLV_PACKET_TYPE_IPV6:
        processIpv6Packet(payload);
        break;
      case TLV_PACKET_TYPE_COMPRESSED_IP:
        processCompressedIpPacket(payload);
        break;
      case TLV_PACKET_TYPE_SIGNALLING:
        emitTlvSiPacket(payload);
        break;
      case TLV_PACKET_TYPE_UNDEFINED:
      case TLV_PACKET_TYPE_NULL:
      default:
        break;
    }
  }

  private void processIpv4Packet(ParsableByteArray packet) {
    int start = packet.getPosition();
    if (packet.bytesLeft() < IPV4_MIN_HEADER_SIZE) {
      return;
    }
    byte[] data = packet.getData();
    int versionAndHeaderLength = data[start] & 0xFF;
    int headerLength = (versionAndHeaderLength & 0x0F) * 4;
    int totalLength = ((data[start + 2] & 0xFF) << 8) | (data[start + 3] & 0xFF);
    int fragment = ((data[start + 6] & 0x3F) << 8) | (data[start + 7] & 0xFF);
    if ((versionAndHeaderLength >> 4) != 4
        || headerLength < IPV4_MIN_HEADER_SIZE
        || totalLength < headerLength
        || totalLength > packet.bytesLeft()
        || fragment != 0
        || (data[start + 9] & 0xFF) != IP_PROTOCOL_UDP) {
      return;
    }
    int oldLimit = packet.limit();
    packet.setPosition(start + headerLength);
    packet.setLimit(start + totalLength);
    dispatchUdpPayload(packet, data, start + 12, start + 16, /* addressLength= */ 4);
    packet.setLimit(oldLimit);
  }

  private void processIpv6Packet(ParsableByteArray packet) {
    int start = packet.getPosition();
    if (packet.bytesLeft() < IPV6_HEADER_SIZE) {
      return;
    }
    byte[] data = packet.getData();
    int payloadLength = ((data[start + 4] & 0xFF) << 8) | (data[start + 5] & 0xFF);
    int totalLength = IPV6_HEADER_SIZE + payloadLength;
    if ((data[start] >> 4) != 6
        || totalLength > packet.bytesLeft()
        || (data[start + 6] & 0xFF) != IP_PROTOCOL_UDP) {
      return;
    }
    int oldLimit = packet.limit();
    packet.setPosition(start + IPV6_HEADER_SIZE);
    packet.setLimit(start + totalLength);
    dispatchUdpPayload(packet, data, start + 8, start + 24, /* addressLength= */ 16);
    packet.setLimit(oldLimit);
  }

  private void processCompressedIpPacket(ParsableByteArray packet) {
    if (packet.bytesLeft() < 3) {
      return;
    }
    int contextId = packet.readUnsignedShort() >> 4;
    int contextType = packet.readUnsignedByte();
    switch (contextType) {
      case CONTEXT_TYPE_PARTIAL_IPV4_AND_UDP:
        processFullCompressedIpPacket(
            packet,
            contextId,
            PARTIAL_IPV4_AND_UDP_HEADER_SIZE,
            /* protocolOffset= */ 7,
            /* udpHeaderOffset= */ 16);
        break;
      case CONTEXT_TYPE_IPV4:
        if (packet.bytesLeft() < IPV4_IDENTIFICATION_SIZE) {
          return;
        }
        packet.skipBytes(IPV4_IDENTIFICATION_SIZE);
        dispatchCompressedPayload(contextId, packet);
        break;
      case CONTEXT_TYPE_PARTIAL_IPV6_AND_UDP:
        processFullCompressedIpPacket(
            packet,
            contextId,
            PARTIAL_IPV6_AND_UDP_HEADER_SIZE,
            /* protocolOffset= */ 4,
            /* udpHeaderOffset= */ 38);
        break;
      case CONTEXT_TYPE_NO_COMPRESSED_HEADER:
        dispatchCompressedPayload(contextId, packet);
        break;
      default:
        break;
    }
  }

  private void processFullCompressedIpPacket(
      ParsableByteArray packet,
      int contextId,
      int headerSize,
      int protocolOffset,
      int udpHeaderOffset) {
    if (packet.bytesLeft() < headerSize) {
      return;
    }
    int headerStart = packet.getPosition();
    byte[] data = packet.getData();
    int sourcePort =
        ((data[headerStart + udpHeaderOffset] & 0xFF) << 8)
            | (data[headerStart + udpHeaderOffset + 1] & 0xFF);
    int destinationPort =
        ((data[headerStart + udpHeaderOffset + 2] & 0xFF) << 8)
            | (data[headerStart + udpHeaderOffset + 3] & 0xFF);
    int protocol =
        (data[headerStart + protocolOffset] & 0xFF) != IP_PROTOCOL_UDP
            ? COMPRESSED_IP_PROTOCOL_UNSUPPORTED
            : isNtpPacket(sourcePort, destinationPort)
                ? COMPRESSED_IP_PROTOCOL_NTP
                : COMPRESSED_IP_PROTOCOL_MMTP;
    compressedIpProtocols.put(contextId, protocol);
    packet.skipBytes(headerSize);
    dispatchCompressedPayload(contextId, packet);
  }

  private void dispatchCompressedPayload(int contextId, ParsableByteArray packet) {
    int protocol = compressedIpProtocols.get(contextId, COMPRESSED_IP_PROTOCOL_UNKNOWN);
    if (protocol == COMPRESSED_IP_PROTOCOL_UNKNOWN) {
      if (consumeNtpPayload(packet, /* strict= */ true)) {
        compressedIpProtocols.put(contextId, COMPRESSED_IP_PROTOCOL_NTP);
        return;
      }
      if (!isMmtpPacket(packet)) {
        return;
      }
      compressedIpProtocols.put(contextId, COMPRESSED_IP_PROTOCOL_MMTP);
      protocol = COMPRESSED_IP_PROTOCOL_MMTP;
    }
    if (protocol == COMPRESSED_IP_PROTOCOL_NTP) {
      consumeNtpPayload(packet, /* strict= */ false);
    } else if (protocol == COMPRESSED_IP_PROTOCOL_MMTP) {
      dispatchMmtpPayload(contextId, packet);
    }
  }

  private void emitTlvSiPacket(ParsableByteArray payload) {
    int payloadSize = payload.bytesLeft();
    byte[] sample = new byte[MMT_DATA_HEADER_SIZE + payloadSize];
    writeInt(sample, /* offset= */ 0, TLV_SI_ASSET_TYPE);
    writeInt(sample, /* offset= */ Integer.BYTES, 0xFFFF);
    writeInt(sample, /* offset= */ 2 * Integer.BYTES, (int) tlvSiSequenceNumber++);
    writeInt(
        sample,
        /* offset= */ 4 * Integer.BYTES,
        payloadSize == 0 ? 0 : payload.getData()[payload.getPosition()] & 0xFF);
    System.arraycopy(
        payload.getData(), payload.getPosition(), sample, MMT_DATA_HEADER_SIZE, payloadSize);
    ParsableByteArray sampleData = new ParsableByteArray(sample);
    checkNotNull(dataOutput).sampleData(sampleData, sample.length);
    dataOutput.sampleMetadata(
        /* timeUs= */ 0,
        C.BUFFER_FLAG_KEY_FRAME,
        sample.length,
        /* offset= */ 0,
        /* cryptoData= */ null);
  }

  private void dispatchUdpPayload(
      ParsableByteArray packet,
      byte[] ipPacketData,
      int sourceAddressOffset,
      int destinationAddressOffset,
      int addressLength) {
    int start = packet.getPosition();
    if (packet.bytesLeft() < UDP_HEADER_SIZE) {
      return;
    }
    byte[] data = packet.getData();
    int sourcePort = ((data[start] & 0xFF) << 8) | (data[start + 1] & 0xFF);
    int destinationPort = ((data[start + 2] & 0xFF) << 8) | (data[start + 3] & 0xFF);
    int udpLength = ((data[start + 4] & 0xFF) << 8) | (data[start + 5] & 0xFF);
    if (udpLength < UDP_HEADER_SIZE || udpLength > packet.bytesLeft()) {
      return;
    }
    int oldLimit = packet.limit();
    packet.setPosition(start + UDP_HEADER_SIZE);
    packet.setLimit(start + udpLength);
    if (isNtpPacket(sourcePort, destinationPort)) {
      consumeNtpPayload(packet, /* strict= */ false);
    } else if (isMmtpPacket(packet)) {
      int contextId =
          getRawFlowContextId(
              ipPacketData,
              sourceAddressOffset,
              destinationAddressOffset,
              addressLength,
              sourcePort,
              destinationPort);
      if (contextId != C.INDEX_UNSET) {
        dispatchMmtpPayload(contextId, packet);
      }
    }
    packet.setLimit(oldLimit);
  }

  private static boolean isNtpPacket(int sourcePort, int destinationPort) {
    return sourcePort == UDP_PORT_NTP || destinationPort == UDP_PORT_NTP;
  }

  private boolean consumeNtpPayload(ParsableByteArray packet, boolean strict) {
    int start = packet.getPosition();
    if (packet.bytesLeft() < NTP_PACKET_SIZE) {
      return false;
    }
    byte[] data = packet.getData();
    int version = data[start] >> 3 & 0x07;
    int mode = data[start] & 0x07;
    long transmitTimestamp = readLong(data, start + 40);
    if (version != 4 || (mode != 4 && mode != 5) || transmitTimestamp == 0) {
      return false;
    }
    if (strict
        && (mode != 5
            || (data[start + 1] & 0xFF) < 1
            || (data[start + 1] & 0xFF) > 15
            || readInt(data, start + 12) != 0
            || readLong(data, start + 24) != 0
            || readLong(data, start + 32) != 0)) {
      return false;
    }
    timestampAdjuster.setNtpAnchor(transmitTimestamp);
    return true;
  }

  private int getRawFlowContextId(
      byte[] data,
      int sourceAddressOffset,
      int destinationAddressOffset,
      int addressLength,
      int sourcePort,
      int destinationPort) {
    for (int i = 0; i < rawIpFlows.size(); i++) {
      RawIpFlow flow = rawIpFlows.get(i);
      if (flow.matches(
          data,
          sourceAddressOffset,
          destinationAddressOffset,
          addressLength,
          sourcePort,
          destinationPort)) {
        return flow.contextId;
      }
    }
    if (rawIpFlows.size() >= MAX_CONTEXTS) {
      return C.INDEX_UNSET;
    }
    int contextId = UNCOMPRESSED_CONTEXT_ID + rawIpFlows.size();
    rawIpFlows.add(
        new RawIpFlow(
            data,
            sourceAddressOffset,
            destinationAddressOffset,
            addressLength,
            sourcePort,
            destinationPort,
            contextId));
    return contextId;
  }

  private static int readInt(byte[] data, int offset) {
    return (data[offset] & 0xFF) << 24
        | (data[offset + 1] & 0xFF) << 16
        | (data[offset + 2] & 0xFF) << 8
        | (data[offset + 3] & 0xFF);
  }

  private static void writeInt(byte[] data, int offset, int value) {
    data[offset] = (byte) (value >> 24);
    data[offset + 1] = (byte) (value >> 16);
    data[offset + 2] = (byte) (value >> 8);
    data[offset + 3] = (byte) value;
  }

  private static long readLong(byte[] data, int offset) {
    return ((long) readInt(data, offset) << 32) | (readInt(data, offset + 4) & 0xFFFFFFFFL);
  }

  private void dispatchMmtpPayload(int contextId, ParsableByteArray packet) {
    if (!isMmtpPacket(packet)) {
      return;
    }
    @Nullable MmtpReader reader = readersByContextId.get(contextId);
    if (reader == null) {
      if (readersByContextId.size() >= MAX_CONTEXTS) {
        return;
      }
      reader = new MmtpReader(contextId, timestampAdjuster);
      reader.init(checkNotNull(readerOutput), checkNotNull(dataOutput), "mmt-data");
      if (tracksEnded) {
        // ExtractorOutput forbids new track IDs after endTracks(). Preserve assets from contexts
        // first observed after that point on the metadata track instead.
        reader.finishTrackCreation();
      }
      readersByContextId.put(contextId, reader);
    }
    reader.consume(packet);
  }

  private static boolean isMmtpPacket(ParsableByteArray packet) {
    int start = packet.getPosition();
    if (packet.bytesLeft() < 12) {
      return false;
    }
    byte[] data = packet.getData();
    return (data[start] & 0xC0) == 0;
  }

  private void maybeEndTracks() {
    if (tracksEnded) {
      return;
    }
    boolean hasAssetSignaling = false;
    for (int i = 0; i < readersByContextId.size(); i++) {
      MmtpReader reader = readersByContextId.valueAt(i);
      if (!reader.hasAssetSignaling()) {
        continue;
      }
      hasAssetSignaling = true;
      if (!reader.isTrackDiscoveryComplete()) {
        return;
      }
    }
    if (hasAssetSignaling) {
      finishTrackCreation();
      checkNotNull(readerOutput).endTracks();
      tracksEnded = true;
    }
  }

  private void endInput() {
    if (inputEnded) {
      return;
    }
    inputEnded = true;
    for (int i = 0; i < readersByContextId.size(); i++) {
      readersByContextId.valueAt(i).endOfInputReached();
    }
    if (!tracksEnded) {
      finishTrackCreation();
      checkNotNull(readerOutput).endTracks();
      tracksEnded = true;
    }
  }

  private void finishTrackCreation() {
    for (int i = 0; i < readersByContextId.size(); i++) {
      readersByContextId.valueAt(i).finishTrackCreation();
    }
  }

  private static int findChainedPacketBoundary(byte[] data, int limit) {
    for (int offset = 0; offset <= limit - TLV_HEADER_SIZE; offset++) {
      if (!isPlausibleHeader(data, offset)) {
        continue;
      }
      int dataLength = ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
      int nextOffset = offset + TLV_HEADER_SIZE + dataLength;
      if (nextOffset > limit - TLV_HEADER_SIZE) {
        continue;
      }
      if (isPlausiblePacket(data, offset, nextOffset) && isPlausibleHeader(data, nextOffset)) {
        return offset;
      }
    }
    return C.INDEX_UNSET;
  }

  private static int findSingleCompletePacket(byte[] data, int limit) {
    for (int offset = 0; offset <= limit - TLV_HEADER_SIZE; offset++) {
      if (!isPlausibleHeader(data, offset)) {
        continue;
      }
      int dataLength = ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
      int packetEnd = offset + TLV_HEADER_SIZE + dataLength;
      if (packetEnd <= limit && isPlausiblePacket(data, offset, packetEnd)) {
        return offset;
      }
    }
    return C.INDEX_UNSET;
  }

  private static boolean isPlausiblePacket(byte[] data, int offset, int packetEnd) {
    int packetType = data[offset + 1] & 0xFF;
    int payloadOffset = offset + TLV_HEADER_SIZE;
    if (packetType == TLV_PACKET_TYPE_COMPRESSED_IP) {
      return packetEnd - payloadOffset >= 3 && isKnownContextType(data[payloadOffset + 2] & 0xFF);
    }
    if (packetType == TLV_PACKET_TYPE_NULL) {
      for (int i = payloadOffset; i < packetEnd; i++) {
        if ((data[i] & 0xFF) != 0xFF) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean isPlausibleHeader(byte[] data, int offset) {
    return (data[offset] & 0xFF) == TLV_SYNC_BYTE && isKnownPacketType(data[offset + 1] & 0xFF);
  }

  private static boolean isKnownPacketType(int packetType) {
    return packetType == TLV_PACKET_TYPE_UNDEFINED
        || packetType == TLV_PACKET_TYPE_IPV4
        || packetType == TLV_PACKET_TYPE_IPV6
        || packetType == TLV_PACKET_TYPE_COMPRESSED_IP
        || packetType == TLV_PACKET_TYPE_SIGNALLING
        || packetType == TLV_PACKET_TYPE_NULL;
  }

  private static boolean isKnownContextType(int contextType) {
    return contextType == CONTEXT_TYPE_PARTIAL_IPV4_AND_UDP
        || contextType == CONTEXT_TYPE_IPV4
        || contextType == CONTEXT_TYPE_PARTIAL_IPV6_AND_UDP
        || contextType == CONTEXT_TYPE_NO_COMPRESSED_HEADER;
  }

  private static final class RawIpFlow {
    private final byte[] sourceAddress;
    private final byte[] destinationAddress;
    private final int sourcePort;
    private final int destinationPort;
    public final int contextId;

    public RawIpFlow(
        byte[] data,
        int sourceAddressOffset,
        int destinationAddressOffset,
        int addressLength,
        int sourcePort,
        int destinationPort,
        int contextId) {
      sourceAddress =
          Arrays.copyOfRange(data, sourceAddressOffset, sourceAddressOffset + addressLength);
      destinationAddress =
          Arrays.copyOfRange(
              data, destinationAddressOffset, destinationAddressOffset + addressLength);
      this.sourcePort = sourcePort;
      this.destinationPort = destinationPort;
      this.contextId = contextId;
    }

    public boolean matches(
        byte[] data,
        int sourceAddressOffset,
        int destinationAddressOffset,
        int addressLength,
        int sourcePort,
        int destinationPort) {
      if (sourceAddress.length != addressLength
          || this.sourcePort != sourcePort
          || this.destinationPort != destinationPort) {
        return false;
      }
      for (int i = 0; i < addressLength; i++) {
        if (sourceAddress[i] != data[sourceAddressOffset + i]
            || destinationAddress[i] != data[destinationAddressOffset + i]) {
          return false;
        }
      }
      return true;
    }
  }
}
