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
package androidx.media3.extractor.ts;

import static com.google.common.base.Preconditions.checkNotNull;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.DtsUtil;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import java.util.ArrayList;
import java.util.List;

/**
 * Probes an ambiguous private TS stream for DTS Core frames before forwarding it to {@link
 * DtsReader}.
 */
/* package */ final class DtsProbeReader implements ElementaryStreamReader {

  private static final int CLASSIFICATION_PROBING = 0;
  private static final int CLASSIFICATION_DTS = 1;
  private static final int CLASSIFICATION_UNSUPPORTED = 2;

  // FFmpeg's DTS probe requires more than three matching frame markers.
  private static final int REQUIRED_CONSECUTIVE_CORE_FRAMES = 4;
  private static final int CORE_HEADER_SIZE = 18;
  private static final int MAX_PROBE_BYTES = 64 * 1024;

  private final DtsReader delegate;
  private final @Nullable String language;
  private final @C.RoleFlags int roleFlags;
  private final String containerMimeType;
  private final List<PendingPacket> pendingPackets;
  private final ParsableByteArray probeData;
  private final byte[] headerScratch;

  @Nullable private TrackOutput output;
  @Nullable private String formatId;
  @Nullable private PendingPacket currentPacket;
  private int classification;
  private boolean packetOpen;

  DtsProbeReader(@Nullable String language, @C.RoleFlags int roleFlags, String containerMimeType) {
    this.language = language;
    this.roleFlags = roleFlags;
    this.containerMimeType = containerMimeType;
    delegate =
        new DtsReader(language, roleFlags, DtsReader.EXTSS_HEADER_SIZE_MAX, containerMimeType);
    pendingPackets = new ArrayList<>();
    probeData = new ParsableByteArray(MAX_PROBE_BYTES);
    probeData.setLimit(0);
    headerScratch = new byte[CORE_HEADER_SIZE];
    classification = CLASSIFICATION_PROBING;
  }

  @Override
  public void seek() {
    delegate.seek();
    if (classification == CLASSIFICATION_PROBING) {
      clearProbe();
    }
    packetOpen = false;
  }

  @Override
  public void createTracks(
      ExtractorOutput extractorOutput, PesReader.TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    formatId = idGenerator.getFormatId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_AUDIO);
    delegate.setTrackOutput(output, formatId);
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    packetOpen = true;
    if (classification == CLASSIFICATION_DTS) {
      delegate.packetStarted(pesTimeUs, flags);
    } else if (classification == CLASSIFICATION_PROBING) {
      currentPacket = new PendingPacket(pesTimeUs, flags, probeData.limit());
      pendingPackets.add(currentPacket);
    }
  }

  @Override
  public void consume(ParsableByteArray data) throws ParserException {
    if (classification == CLASSIFICATION_DTS) {
      delegate.consume(data);
      return;
    }
    if (classification == CLASSIFICATION_UNSUPPORTED) {
      data.skipBytes(data.bytesLeft());
      return;
    }

    PendingPacket packet = checkNotNull(currentPacket);
    int offset = data.getPosition();
    int length = data.bytesLeft();
    byte[] bytes = data.getData();
    int probeEndPosition = probeData.limit() + length;
    probeData.ensureCapacity(probeEndPosition);
    System.arraycopy(bytes, offset, probeData.getData(), probeData.limit(), length);
    probeData.setLimit(probeEndPosition);
    packet.endPosition = probeEndPosition;
    data.skipBytes(length);

    if (containsDtsCoreStream(probeData.getData(), probeEndPosition)) {
      classification = CLASSIFICATION_DTS;
      replayProbe();
    } else if (probeEndPosition >= MAX_PROBE_BYTES) {
      rejectProbe();
    }
  }

  @Override
  public void packetFinished() {
    if (classification == CLASSIFICATION_DTS) {
      delegate.packetFinished();
    }
    packetOpen = false;
    currentPacket = null;
  }

  @Override
  public void endOfInputReached() {
    if (classification == CLASSIFICATION_PROBING) {
      rejectProbe();
    } else if (classification == CLASSIFICATION_DTS) {
      delegate.endOfInputReached();
    }
  }

  private void replayProbe() throws ParserException {
    ParsableByteArray replayData = new ParsableByteArray(probeData.getData(), /* limit= */ 0);
    for (int i = 0; i < pendingPackets.size(); i++) {
      PendingPacket packet = pendingPackets.get(i);
      delegate.packetStarted(packet.pesTimeUs, packet.flags);
      replayData.setLimit(packet.endPosition);
      replayData.setPosition(packet.startPosition);
      delegate.consume(replayData);
      if (i < pendingPackets.size() - 1 || !packetOpen) {
        delegate.packetFinished();
      }
    }
    clearProbe();
  }

  private void rejectProbe() {
    classification = CLASSIFICATION_UNSUPPORTED;
    checkNotNull(output)
        .format(
            new Format.Builder()
                .setId(formatId)
                .setContainerMimeType(containerMimeType)
                .setSampleMimeType(MimeTypes.APPLICATION_OCTET_STREAM)
                .setLanguage(language)
                .setRoleFlags(roleFlags)
                .build());
    clearProbe();
  }

  private void clearProbe() {
    pendingPackets.clear();
    probeData.reset(/* limit= */ 0);
    currentPacket = null;
  }

  private boolean containsDtsCoreStream(byte[] data, int limit) {
    for (int offset = 0; offset + CORE_HEADER_SIZE <= limit; offset++) {
      int consecutiveFrames = 0;
      int sampleRate = Format.NO_VALUE;
      int frameOffset = offset;
      while (frameOffset + CORE_HEADER_SIZE <= limit) {
        if (DtsUtil.getFrameType(readSyncWord(data, frameOffset)) != DtsUtil.FRAME_TYPE_CORE) {
          break;
        }
        System.arraycopy(data, frameOffset, headerScratch, 0, CORE_HEADER_SIZE);
        Format format;
        int frameSize;
        try {
          format =
              DtsUtil.parseDtsFormat(
                  headerScratch,
                  /* trackId= */ null,
                  language,
                  roleFlags,
                  containerMimeType,
                  /* drmInitData= */ null);
          frameSize = DtsUtil.getDtsFrameSize(headerScratch);
        } catch (RuntimeException e) {
          break;
        }
        if (frameSize < CORE_HEADER_SIZE
            || frameSize > MAX_PROBE_BYTES
            || format.sampleRate <= 0
            || format.channelCount <= 0
            || (sampleRate != Format.NO_VALUE && format.sampleRate != sampleRate)) {
          break;
        }
        sampleRate = format.sampleRate;
        consecutiveFrames++;
        if (consecutiveFrames >= REQUIRED_CONSECUTIVE_CORE_FRAMES) {
          return true;
        }
        frameOffset += frameSize;
        while (frameOffset + CORE_HEADER_SIZE <= limit) {
          if (DtsUtil.getFrameType(readSyncWord(data, frameOffset))
              != DtsUtil.FRAME_TYPE_EXTENSION_SUBSTREAM) {
            break;
          }
          System.arraycopy(data, frameOffset, headerScratch, 0, CORE_HEADER_SIZE);
          int extensionFrameSize;
          try {
            extensionFrameSize = DtsUtil.parseDtsHdFrameSize(headerScratch);
          } catch (RuntimeException e) {
            break;
          }
          if (extensionFrameSize < CORE_HEADER_SIZE
              || extensionFrameSize > MAX_PROBE_BYTES
              || frameOffset + extensionFrameSize > limit) {
            break;
          }
          frameOffset += extensionFrameSize;
        }
      }
    }
    return false;
  }

  private static int readSyncWord(byte[] data, int offset) {
    return ((data[offset] & 0xFF) << 24)
        | ((data[offset + 1] & 0xFF) << 16)
        | ((data[offset + 2] & 0xFF) << 8)
        | (data[offset + 3] & 0xFF);
  }

  private static final class PendingPacket {

    final long pesTimeUs;
    final @TsPayloadReader.Flags int flags;
    final int startPosition;
    int endPosition;

    private PendingPacket(long pesTimeUs, @TsPayloadReader.Flags int flags, int startPosition) {
      this.pesTimeUs = pesTimeUs;
      this.flags = flags;
      this.startPosition = startPosition;
      endPosition = startPosition;
    }
  }
}
