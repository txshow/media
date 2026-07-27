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

import static java.lang.Math.max;
import static java.lang.Math.min;

import android.util.LongSparseArray;
import android.util.SparseArray;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableByteArray;
import com.google.common.collect.ImmutableList;

/**
 * Parses the MMT signaling messages needed to discover and configure media assets.
 *
 * <p>The parser accepts both indexed PA messages from ISO/IEC 23008-1 and the inlined-table PA
 * layout used by observed ARIB streams.
 */
/* package */ final class MmtSignalingParser {

  public static final int ASSET_TYPE_HEV1 = 0x68657631;
  public static final int ASSET_TYPE_HVC1 = 0x68766331;
  public static final int ASSET_TYPE_AVC1 = 0x61766331;
  public static final int ASSET_TYPE_AVC3 = 0x61766333;
  public static final int ASSET_TYPE_MP4A = 0x6D703461;
  public static final int ASSET_TYPE_STPP = 0x73747070;
  public static final int ASSET_TYPE_AAPP = 0x61617070;
  public static final int ASSET_TYPE_ASGD = 0x61736764;
  public static final int ASSET_TYPE_AAGD = 0x61616764;

  public static final int AUDIO_STREAM_CONTENT_AAC = 0x03;
  public static final int AUDIO_STREAM_CONTENT_ALS = 0x04;
  public static final int AUDIO_STREAM_TYPE_LATM = 0x11;
  public static final int AUDIO_STREAM_TYPE_RAW = 0x1C;

  private static final String TAG = "MmtSignalingParser";

  private static final int MESSAGE_ID_PA = 0x0000;
  private static final int MESSAGE_ID_M2_SECTION = 0x8000;
  private static final int TABLE_ID_MPT = 0x20;

  private static final int DESCRIPTOR_MPU_TIMESTAMP = 0x0001;
  private static final int DESCRIPTOR_MPEG4_AUDIO_EXTENSION = 0x8009;
  private static final int DESCRIPTOR_VIDEO_COMPONENT = 0x8010;
  private static final int DESCRIPTOR_STREAM_IDENTIFIER = 0x8011;
  private static final int DESCRIPTOR_AUDIO_COMPONENT = 0x8014;
  private static final int DESCRIPTOR_DATA_COMPONENT = 0x8020;
  private static final int DESCRIPTOR_MPU_EXTENDED_TIMESTAMP = 0x8026;

  private static final int LOCATION_TYPE_SAME_FLOW = 0x00;
  private static final int LOCATION_TYPE_IPV4 = 0x01;
  private static final int LOCATION_TYPE_IPV6 = 0x02;
  private static final int LOCATION_TYPE_MPEG2_TS = 0x03;
  private static final int LOCATION_TYPE_IPV6_MPEG2_TS = 0x04;
  private static final int LOCATION_TYPE_URL = 0x05;

  private static final int FI_COMPLETE = 0;
  private static final int FI_FIRST = 1;
  private static final int FI_MIDDLE = 2;
  private static final int FI_LAST = 3;
  private static final int MAX_SIGNALING_MESSAGE_SIZE = 16 * 1024 * 1024;
  private static final int MAX_SIGNALING_PACKET_IDS = 128;
  private static final int MAX_SIGNALING_BUFFER_CAPACITY = 32 * 1024 * 1024;
  private static final int MAX_TIMESTAMP_DESCRIPTORS = 32;

  /** Description of an asset carried by an MMTP packet ID. */
  public static final class Asset {
    public final int packetId;
    public final int assetType;
    public final LongSparseArray<Long> presentationTimesNtp;
    public final LongSparseArray<ExtendedTimestampDescriptor> extendedTimestamps;

    @Nullable public String language;
    @Nullable public byte[] audioSpecificConfig;
    public int audioStreamContent;
    public int audioStreamType;
    public int audioSampleRate;
    public int timescale;
    public int videoFrameRateNumerator;
    public int videoFrameRateDenominator;
    public int subtitleTmd;
    public int subtitleResolution;
    public boolean videoComponentDescriptorPresent;
    public boolean audioSpecificConfigDescriptorPresent;
    public boolean audioComponentDescriptorPresent;
    public boolean subtitleDescriptorPresent;
    public boolean subtitleSupported;
    public boolean superimpose;
    public boolean hasSubtitleReferenceTime;
    public long subtitleReferenceTimeNtp;

    public Asset(int packetId, int assetType) {
      this.packetId = packetId;
      this.assetType = assetType;
      presentationTimesNtp = new LongSparseArray<>();
      extendedTimestamps = new LongSparseArray<>();
      audioStreamContent = C.INDEX_UNSET;
      audioStreamType = C.INDEX_UNSET;
      audioSampleRate = C.RATE_UNSET_INT;
      timescale = C.RATE_UNSET_INT;
    }
  }

  /** Per-MPU decoding and presentation offsets from descriptor {@code 0x8026}. */
  public static final class ExtendedTimestampDescriptor {
    public final int decodingTimeOffset;
    public final int[] dtsPtsOffsets;
    public final int[] ptsOffsets;
    public final int ptsOffsetType;

    public ExtendedTimestampDescriptor(
        int decodingTimeOffset, int[] dtsPtsOffsets, int[] ptsOffsets, int ptsOffsetType) {
      this.decodingTimeOffset = decodingTimeOffset;
      this.dtsPtsOffsets = dtsPtsOffsets;
      this.ptsOffsets = ptsOffsets;
      this.ptsOffsetType = ptsOffsetType;
    }
  }

  private final SparseArray<FragmentState> fragmentStates;

  private ImmutableList<Asset> assets;
  private boolean hasPackageTable;
  private boolean hasNtpAnchor;
  private long ntpAnchor;
  private int signalingBufferCapacity;

  public MmtSignalingParser() {
    fragmentStates = new SparseArray<>();
    assets = ImmutableList.of();
  }

  public ImmutableList<Asset> getAssets() {
    return assets;
  }

  public boolean hasPackageTable() {
    return hasPackageTable;
  }

  public boolean hasNtpAnchor() {
    return hasNtpAnchor;
  }

  public long getNtpAnchor() {
    return ntpAnchor;
  }

  public void reset() {
    fragmentStates.clear();
    hasNtpAnchor = false;
    ntpAnchor = 0;
    signalingBufferCapacity = 0;
    for (int i = 0; i < assets.size(); i++) {
      Asset asset = assets.get(i);
      asset.presentationTimesNtp.clear();
      asset.extendedTimestamps.clear();
      asset.hasSubtitleReferenceTime = false;
      asset.subtitleReferenceTimeNtp = 0;
    }
  }

  /**
   * Consumes an MMTP signaling payload.
   *
   * @return Whether a package table was parsed.
   */
  public boolean consume(ParsableByteArray payload, int packetId, long packetSequenceNumber) {
    @Nullable FragmentState state = fragmentStates.get(packetId);
    if (state == null) {
      if (fragmentStates.size() >= MAX_SIGNALING_PACKET_IDS) {
        return false;
      }
      state = new FragmentState();
      fragmentStates.put(packetId, state);
    }
    if (state.hasLastPacketSequenceNumber) {
      long expectedPacketSequenceNumber = (state.lastPacketSequenceNumber + 1) & 0xFFFFFFFFL;
      if (packetSequenceNumber != expectedPacketSequenceNumber) {
        if (!isSequenceAfter(packetSequenceNumber, state.lastPacketSequenceNumber)) {
          return false;
        }
        state.clearMessage();
      }
    }
    state.hasLastPacketSequenceNumber = true;
    state.lastPacketSequenceNumber = packetSequenceNumber;

    if (payload.bytesLeft() < 2) {
      return false;
    }
    int header = payload.readUnsignedByte();
    int fragmentationIndicator = header >> 6;
    boolean lengthExtensionFlag = (header & 0x02) != 0;
    boolean aggregated = (header & 0x01) != 0;
    payload.skipBytes(1);

    if (aggregated) {
      if (fragmentationIndicator != FI_COMPLETE) {
        return false;
      }
      state.clearMessage();
      boolean updated = false;
      int lengthFieldSize = lengthExtensionFlag ? 4 : 2;
      while (payload.bytesLeft() >= lengthFieldSize) {
        long messageLength =
            lengthExtensionFlag ? payload.readUnsignedInt() : payload.readUnsignedShort();
        if (messageLength <= 0
            || messageLength > payload.bytesLeft()
            || messageLength > MAX_SIGNALING_MESSAGE_SIZE) {
          return updated;
        }
        int messageLimit = payload.getPosition() + (int) messageLength;
        updated |= parseSignalingMessage(payload, messageLimit);
        payload.setPosition(messageLimit);
      }
      return updated;
    }

    switch (fragmentationIndicator) {
      case FI_COMPLETE:
        state.clearMessage();
        return parseSignalingMessage(payload, payload.limit());
      case FI_FIRST:
        state.startMessage();
        appendToMessageBuffer(state, payload);
        return false;
      case FI_MIDDLE:
        if (state.assembling) {
          appendToMessageBuffer(state, payload);
        }
        return false;
      case FI_LAST:
        if (!state.assembling || !appendToMessageBuffer(state, payload)) {
          return false;
        }
        state.assembling = false;
        state.messageBuffer.setPosition(0);
        return parseSignalingMessage(state.messageBuffer, state.messageBuffer.limit());
      default:
        return false;
    }
  }

  private boolean appendToMessageBuffer(FragmentState state, ParsableByteArray payload) {
    ParsableByteArray messageBuffer = state.messageBuffer;
    int length = payload.bytesLeft();
    int currentLimit = messageBuffer.limit();
    if (length > MAX_SIGNALING_MESSAGE_SIZE - currentLimit) {
      state.clearMessage();
      return false;
    }
    int requiredCapacity = currentLimit + length;
    if (requiredCapacity > messageBuffer.capacity()) {
      int grownCapacity =
          min(
              MAX_SIGNALING_MESSAGE_SIZE,
              max(max(messageBuffer.capacity() * 2, 1024), requiredCapacity));
      int additionalCapacity = grownCapacity - messageBuffer.capacity();
      if (additionalCapacity > MAX_SIGNALING_BUFFER_CAPACITY - signalingBufferCapacity) {
        state.clearMessage();
        return false;
      }
      byte[] grown = new byte[grownCapacity];
      System.arraycopy(messageBuffer.getData(), 0, grown, 0, currentLimit);
      messageBuffer.reset(grown, currentLimit);
      signalingBufferCapacity += additionalCapacity;
    }
    payload.readBytes(messageBuffer.getData(), currentLimit, length);
    messageBuffer.setLimit(requiredCapacity);
    return true;
  }

  private boolean parseSignalingMessage(ParsableByteArray data, int limit) {
    int startPosition = data.getPosition();
    try {
      if (!hasBytes(data, limit, 3)) {
        return false;
      }
      int messageId = data.readUnsignedShort();
      data.skipBytes(1);
      switch (messageId) {
        case MESSAGE_ID_PA:
          if (!hasBytes(data, limit, 4)) {
            return false;
          }
          long paLength = data.readUnsignedInt();
          if (paLength > limit - data.getPosition()) {
            return false;
          }
          return parsePaMessage(data, data.getPosition() + (int) paLength);
        case MESSAGE_ID_M2_SECTION:
          if (!hasBytes(data, limit, 2)) {
            return false;
          }
          int sectionLength = data.readUnsignedShort();
          if (sectionLength > limit - data.getPosition()) {
            return false;
          }
          return parseTable(data, data.getPosition() + sectionLength);
        default:
          return false;
      }
    } catch (RuntimeException e) {
      Log.w(TAG, "Discarding malformed signaling message", e);
      data.setPosition(Math.min(limit, Math.max(startPosition, data.getPosition())));
      return false;
    }
  }

  private boolean parsePaMessage(ParsableByteArray data, int limit) {
    if (!hasBytes(data, limit, 1)) {
      return false;
    }
    int numberOfTables = data.readUnsignedByte();
    if (!hasBytes(data, limit, numberOfTables * 4)) {
      return false;
    }
    int[] tableSizes = new int[numberOfTables];
    for (int i = 0; i < numberOfTables; i++) {
      data.skipBytes(2);
      tableSizes[i] = data.readUnsignedShort() + 4;
    }

    boolean updated = false;
    for (int tableSize : tableSizes) {
      if (!hasBytes(data, limit, tableSize)) {
        return updated;
      }
      int tableLimit = data.getPosition() + tableSize;
      updated |= parseTable(data, tableLimit);
      data.setPosition(tableLimit);
    }

    // ARIB streams have also been observed with number_of_tables=0 and tables placed directly
    // after the empty index.
    while (hasBytes(data, limit, 4)) {
      int tableStart = data.getPosition();
      int tableLength =
          ((data.getData()[tableStart + 2] & 0xFF) << 8) | (data.getData()[tableStart + 3] & 0xFF);
      int tableSize = tableLength + 4;
      if (tableSize < 4 || !hasBytes(data, limit, tableSize)) {
        break;
      }
      int tableLimit = tableStart + tableSize;
      updated |= parseTable(data, tableLimit);
      data.setPosition(tableLimit);
    }
    return updated;
  }

  private boolean parseTable(ParsableByteArray data, int limit) {
    if (!hasBytes(data, limit, 4)) {
      return false;
    }
    int tableId = data.getData()[data.getPosition()] & 0xFF;
    if (tableId == TABLE_ID_MPT) {
      return parseMmtPackageTable(data, limit);
    }
    data.setPosition(limit);
    return false;
  }

  private boolean parseMmtPackageTable(ParsableByteArray data, int limit) {
    if (!hasBytes(data, limit, 4) || data.readUnsignedByte() != TABLE_ID_MPT) {
      return false;
    }
    data.skipBytes(1);
    int tableLength = data.readUnsignedShort();
    int tableLimit = data.getPosition() + tableLength;
    if (tableLimit > limit || !hasBytes(data, tableLimit, 4)) {
      return false;
    }
    data.skipBytes(1);
    int packageIdLength = data.readUnsignedByte();
    if (!hasBytes(data, tableLimit, packageIdLength + 2)) {
      return false;
    }
    data.skipBytes(packageIdLength);
    int packageDescriptorsLength = data.readUnsignedShort();
    if (!hasBytes(data, tableLimit, packageDescriptorsLength + 1)) {
      return false;
    }
    data.skipBytes(packageDescriptorsLength);
    int numberOfAssets = data.readUnsignedByte();

    SparseArray<Asset> parsedAssets = new SparseArray<>();
    for (int i = 0; i < numberOfAssets; i++) {
      @Nullable Asset asset = parseAsset(data, tableLimit);
      if (asset == null) {
        return false;
      }
      if (asset.packetId != C.INDEX_UNSET) {
        parsedAssets.put(asset.packetId, asset);
      }
    }

    ImmutableList.Builder<Asset> builder = ImmutableList.builder();
    for (int i = 0; i < parsedAssets.size(); i++) {
      builder.add(parsedAssets.valueAt(i));
    }
    assets = builder.build();
    hasPackageTable = true;
    data.setPosition(tableLimit);
    return true;
  }

  @Nullable
  private Asset parseAsset(ParsableByteArray data, int limit) {
    if (!hasBytes(data, limit, 6)) {
      return null;
    }
    data.skipBytes(5);
    int assetIdLength = data.readUnsignedByte();
    if (!hasBytes(data, limit, assetIdLength + 6)) {
      return null;
    }
    data.skipBytes(assetIdLength);
    int assetType = data.readInt();
    data.skipBytes(1);
    int locationCount = data.readUnsignedByte();
    if (locationCount == 0) {
      return null;
    }

    int packetId = C.INDEX_UNSET;
    for (int i = 0; i < locationCount; i++) {
      int locationPacketId = parseLocation(data, limit);
      if (locationPacketId == Integer.MIN_VALUE) {
        return null;
      }
      if (packetId == C.INDEX_UNSET && locationPacketId >= 0) {
        packetId = locationPacketId;
      }
    }
    if (!hasBytes(data, limit, 2)) {
      return null;
    }
    int descriptorsLength = data.readUnsignedShort();
    if (!hasBytes(data, limit, descriptorsLength)) {
      return null;
    }

    Asset asset = new Asset(packetId, assetType);
    int descriptorsLimit = data.getPosition() + descriptorsLength;
    while (data.getPosition() < descriptorsLimit) {
      if (!parseDescriptor(asset, data, descriptorsLimit)) {
        return null;
      }
    }
    return asset;
  }

  private int parseLocation(ParsableByteArray data, int limit) {
    if (!hasBytes(data, limit, 1)) {
      return Integer.MIN_VALUE;
    }
    switch (data.readUnsignedByte()) {
      case LOCATION_TYPE_SAME_FLOW:
        if (!hasBytes(data, limit, 2)) {
          return Integer.MIN_VALUE;
        }
        return data.readUnsignedShort();
      case LOCATION_TYPE_IPV4:
        return readPacketIdFromLocation(data, limit, 12);
      case LOCATION_TYPE_IPV6:
        return readPacketIdFromLocation(data, limit, 36);
      case LOCATION_TYPE_MPEG2_TS:
        return skipLocation(data, limit, 4);
      case LOCATION_TYPE_IPV6_MPEG2_TS:
        return skipLocation(data, limit, 38);
      case LOCATION_TYPE_URL:
        if (!hasBytes(data, limit, 1)) {
          return Integer.MIN_VALUE;
        }
        return skipLocation(data, limit, data.readUnsignedByte());
      default:
        return Integer.MIN_VALUE;
    }
  }

  private static int skipLocation(ParsableByteArray data, int limit, int length) {
    if (!hasBytes(data, limit, length)) {
      return Integer.MIN_VALUE;
    }
    data.skipBytes(length);
    return C.INDEX_UNSET;
  }

  private static int readPacketIdFromLocation(
      ParsableByteArray data, int limit, int locationLength) {
    if (!hasBytes(data, limit, locationLength)) {
      return Integer.MIN_VALUE;
    }
    data.skipBytes(locationLength - 2);
    return data.readUnsignedShort();
  }

  private boolean parseDescriptor(Asset asset, ParsableByteArray data, int limit) {
    if (!hasBytes(data, limit, 3)) {
      return false;
    }
    int tag = data.readUnsignedShort();
    int descriptorLength = readDescriptorLength(tag, data, limit);
    if (descriptorLength < 0 || !hasBytes(data, limit, descriptorLength)) {
      return false;
    }
    int descriptorLimit = data.getPosition() + descriptorLength;
    boolean valid;
    switch (tag) {
      case DESCRIPTOR_MPU_TIMESTAMP:
        valid = parseMpuTimestampDescriptor(asset, data, descriptorLimit);
        break;
      case DESCRIPTOR_MPEG4_AUDIO_EXTENSION:
        valid = parseMpeg4AudioExtensionDescriptor(asset, data, descriptorLimit);
        break;
      case DESCRIPTOR_VIDEO_COMPONENT:
        valid = parseVideoComponentDescriptor(asset, data, descriptorLimit);
        break;
      case DESCRIPTOR_STREAM_IDENTIFIER:
        valid = true;
        break;
      case DESCRIPTOR_AUDIO_COMPONENT:
        valid = parseAudioComponentDescriptor(asset, data, descriptorLimit);
        break;
      case DESCRIPTOR_DATA_COMPONENT:
        valid = parseDataComponentDescriptor(asset, data, descriptorLimit);
        break;
      case DESCRIPTOR_MPU_EXTENDED_TIMESTAMP:
        valid = parseExtendedTimestampDescriptor(asset, data, descriptorLimit);
        break;
      default:
        valid = true;
        break;
    }
    data.setPosition(descriptorLimit);
    return valid;
  }

  private static int readDescriptorLength(int tag, ParsableByteArray data, int limit) {
    if (tag <= 0x3FFF || (tag >= 0x8000 && tag <= 0xEFFF)) {
      return hasBytes(data, limit, 1) ? data.readUnsignedByte() : -1;
    }
    if (tag <= 0x6FFF || tag >= 0xF000) {
      return hasBytes(data, limit, 2) ? data.readUnsignedShort() : -1;
    }
    return hasBytes(data, limit, 4) ? data.readUnsignedIntToInt() : -1;
  }

  private boolean parseMpuTimestampDescriptor(Asset asset, ParsableByteArray data, int limit) {
    while (data.getPosition() < limit) {
      if (!hasBytes(data, limit, 12)) {
        return false;
      }
      long mpuSequenceNumber = data.readUnsignedInt();
      long presentationTimeNtp = data.readLong();
      putTimestampDescriptor(asset.presentationTimesNtp, mpuSequenceNumber, presentationTimeNtp);
      setNtpAnchor(presentationTimeNtp);
    }
    return true;
  }

  private static boolean parseVideoComponentDescriptor(
      Asset asset, ParsableByteArray data, int limit) {
    if (!hasBytes(data, limit, 8)) {
      return false;
    }
    data.skipBytes(1);
    setVideoFrameRate(asset, data.readUnsignedByte() & 0x1F);
    data.skipBytes(3);
    asset.language = data.readString(3);
    asset.videoComponentDescriptorPresent = true;
    return true;
  }

  private static void setVideoFrameRate(Asset asset, int frameRateCode) {
    asset.videoFrameRateNumerator = 0;
    asset.videoFrameRateDenominator = 0;
    switch (frameRateCode) {
      case 1:
        asset.videoFrameRateNumerator = 15;
        asset.videoFrameRateDenominator = 1;
        break;
      case 2:
        asset.videoFrameRateNumerator = 24_000;
        asset.videoFrameRateDenominator = 1_001;
        break;
      case 3:
        asset.videoFrameRateNumerator = 24;
        asset.videoFrameRateDenominator = 1;
        break;
      case 4:
        asset.videoFrameRateNumerator = 25;
        asset.videoFrameRateDenominator = 1;
        break;
      case 5:
        asset.videoFrameRateNumerator = 30_000;
        asset.videoFrameRateDenominator = 1_001;
        break;
      case 6:
        asset.videoFrameRateNumerator = 30;
        asset.videoFrameRateDenominator = 1;
        break;
      case 7:
        asset.videoFrameRateNumerator = 50;
        asset.videoFrameRateDenominator = 1;
        break;
      case 8:
        asset.videoFrameRateNumerator = 60_000;
        asset.videoFrameRateDenominator = 1_001;
        break;
      case 9:
        asset.videoFrameRateNumerator = 60;
        asset.videoFrameRateDenominator = 1;
        break;
      case 10:
        asset.videoFrameRateNumerator = 100;
        asset.videoFrameRateDenominator = 1;
        break;
      case 11:
        asset.videoFrameRateNumerator = 120_000;
        asset.videoFrameRateDenominator = 1_001;
        break;
      case 12:
        asset.videoFrameRateNumerator = 120;
        asset.videoFrameRateDenominator = 1;
        break;
      default:
        break;
    }
  }

  private static boolean parseAudioComponentDescriptor(
      Asset asset, ParsableByteArray data, int limit) {
    if (!hasBytes(data, limit, 10)) {
      return false;
    }
    int streamContent = data.readUnsignedByte() & 0x0F;
    data.skipBytes(3);
    int streamType = data.readUnsignedByte();
    data.skipBytes(1);
    int audioFlags = data.readUnsignedByte();
    boolean multiLingual = (audioFlags & 0x80) != 0;
    asset.audioSampleRate = getAudioSampleRate((audioFlags >> 1) & 0x07);
    asset.language = data.readString(3);
    if (multiLingual) {
      if (!hasBytes(data, limit, 3)) {
        return false;
      }
      data.skipBytes(3);
    }
    asset.audioComponentDescriptorPresent = true;
    asset.audioStreamContent = C.INDEX_UNSET;
    asset.audioStreamType = C.INDEX_UNSET;
    if ((streamContent == AUDIO_STREAM_CONTENT_AAC || streamContent == AUDIO_STREAM_CONTENT_ALS)
        && (streamType == AUDIO_STREAM_TYPE_LATM || streamType == AUDIO_STREAM_TYPE_RAW)) {
      asset.audioStreamContent = streamContent;
      asset.audioStreamType = streamType;
    }
    return true;
  }

  private static boolean parseMpeg4AudioExtensionDescriptor(
      Asset asset, ParsableByteArray data, int limit) {
    if (!hasBytes(data, limit, 1)) {
      return false;
    }
    int flags = data.readUnsignedByte();
    int profileLevelCount = flags & 0x0F;
    if (!hasBytes(data, limit, profileLevelCount)) {
      return false;
    }
    data.skipBytes(profileLevelCount);
    @Nullable byte[] audioSpecificConfig = null;
    if ((flags & 0x80) != 0) {
      if (!hasBytes(data, limit, 1)) {
        return false;
      }
      int audioSpecificConfigSize = data.readUnsignedByte();
      if (!hasBytes(data, limit, audioSpecificConfigSize)) {
        return false;
      }
      audioSpecificConfig = new byte[audioSpecificConfigSize];
      data.readBytes(audioSpecificConfig, /* offset= */ 0, audioSpecificConfigSize);
    }
    asset.audioSpecificConfigDescriptorPresent = true;
    asset.audioSpecificConfig = audioSpecificConfig;
    return true;
  }

  private static int getAudioSampleRate(int sampleRateCode) {
    switch (sampleRateCode) {
      case 1:
        return 16_000;
      case 2:
        return 22_050;
      case 3:
        return 24_000;
      case 5:
        return 32_000;
      case 6:
        return 44_100;
      case 7:
        return 48_000;
      default:
        return C.RATE_UNSET_INT;
    }
  }

  private boolean parseDataComponentDescriptor(Asset asset, ParsableByteArray data, int limit) {
    if (!hasBytes(data, limit, 2)) {
      return false;
    }
    if (data.readUnsignedShort() != 0x0020) {
      return true;
    }
    if (!parseAdditionalSubtitleInfo(asset, data, limit)) {
      return false;
    }
    asset.subtitleDescriptorPresent = true;
    return true;
  }

  private boolean parseAdditionalSubtitleInfo(Asset asset, ParsableByteArray data, int limit) {
    if (!hasBytes(data, limit, 8)) {
      return false;
    }
    data.skipBytes(1);
    boolean hasStartMpuSequenceNumber = (data.readUnsignedByte() & 0x08) != 0;
    asset.language = data.readString(3);
    int typeAndFormat = data.readUnsignedByte();
    int subtitleType = typeAndFormat >> 6;
    int subtitleFormat = (typeAndFormat >> 2) & 0x0F;
    asset.subtitleTmd = data.readUnsignedByte() >> 4;
    int resolutionAndCompression = data.readUnsignedByte();
    asset.subtitleResolution = resolutionAndCompression >> 4;
    int subtitleCompression = resolutionAndCompression & 0x0F;
    asset.hasSubtitleReferenceTime = false;
    asset.subtitleReferenceTimeNtp = 0;
    if (hasStartMpuSequenceNumber) {
      if (!hasBytes(data, limit, 4)) {
        return false;
      }
      data.skipBytes(4);
    }
    if (asset.subtitleTmd == 2) {
      if (!hasBytes(data, limit, 9)) {
        return false;
      }
      asset.subtitleReferenceTimeNtp = data.readLong();
      asset.hasSubtitleReferenceTime = true;
      setNtpAnchor(asset.subtitleReferenceTimeNtp);
      data.skipBytes(1);
    }
    asset.superimpose = subtitleType == 1;
    asset.subtitleSupported =
        subtitleFormat == 0 && subtitleCompression == 0 && asset.subtitleResolution <= 2;
    return true;
  }

  private static boolean parseExtendedTimestampDescriptor(
      Asset asset, ParsableByteArray data, int limit) {
    if (!hasBytes(data, limit, 1)) {
      return false;
    }
    int flags = data.readUnsignedByte();
    int ptsOffsetType = (flags >> 1) & 0x03;
    boolean timescaleFlag = (flags & 0x01) != 0;
    if (timescaleFlag) {
      if (!hasBytes(data, limit, 4)) {
        return false;
      }
      long timescale = data.readUnsignedInt();
      if (timescale == 0 || timescale > Integer.MAX_VALUE) {
        return false;
      }
      asset.timescale = (int) timescale;
    }
    int defaultPtsOffset = 0;
    if (ptsOffsetType == 3) {
      return false;
    }
    if (ptsOffsetType == 1) {
      if (!hasBytes(data, limit, 2)) {
        return false;
      }
      defaultPtsOffset = data.readUnsignedShort();
    }

    while (data.getPosition() < limit) {
      if (!hasBytes(data, limit, 8)) {
        return false;
      }
      long mpuSequenceNumber = data.readUnsignedInt();
      data.skipBytes(1);
      int decodingTimeOffset = data.readUnsignedShort();
      int numberOfAccessUnits = data.readUnsignedByte();
      int bytesPerAccessUnit = ptsOffsetType == 2 ? 4 : 2;
      if (!hasBytes(data, limit, numberOfAccessUnits * bytesPerAccessUnit)) {
        return false;
      }
      int[] dtsPtsOffsets = new int[numberOfAccessUnits];
      int[] ptsOffsets = new int[numberOfAccessUnits];
      for (int i = 0; i < numberOfAccessUnits; i++) {
        dtsPtsOffsets[i] = data.readUnsignedShort();
        ptsOffsets[i] = ptsOffsetType == 2 ? data.readUnsignedShort() : defaultPtsOffset;
      }
      putTimestampDescriptor(
          asset.extendedTimestamps,
          mpuSequenceNumber,
          new ExtendedTimestampDescriptor(
              decodingTimeOffset, dtsPtsOffsets, ptsOffsets, ptsOffsetType));
    }
    return true;
  }

  private static <T> void putTimestampDescriptor(
      LongSparseArray<T> descriptors, long mpuSequenceNumber, T descriptor) {
    int existingIndex = descriptors.indexOfKey(mpuSequenceNumber);
    if (existingIndex >= 0) {
      descriptors.setValueAt(existingIndex, descriptor);
      return;
    }
    if (descriptors.size() >= MAX_TIMESTAMP_DESCRIPTORS) {
      int farthestIndex = 0;
      long farthestDistance = -1;
      for (int i = 0; i < descriptors.size(); i++) {
        long distance = sequenceDistance(descriptors.keyAt(i), mpuSequenceNumber);
        if (distance > farthestDistance) {
          farthestIndex = i;
          farthestDistance = distance;
        }
      }
      descriptors.removeAt(farthestIndex);
    }
    descriptors.put(mpuSequenceNumber, descriptor);
  }

  private static long sequenceDistance(long first, long second) {
    long forward = (first - second) & 0xFFFFFFFFL;
    long backward = (second - first) & 0xFFFFFFFFL;
    return min(forward, backward);
  }

  private void setNtpAnchor(long value) {
    ntpAnchor = value;
    hasNtpAnchor = true;
  }

  private static boolean hasBytes(ParsableByteArray data, int limit, int length) {
    return length >= 0 && data.getPosition() <= limit - length;
  }

  private static boolean isSequenceAfter(long value, long reference) {
    long distance = (value - reference) & 0xFFFFFFFFL;
    return distance != 0 && distance < 0x80000000L;
  }

  private static final class FragmentState {
    public final ParsableByteArray messageBuffer;

    public boolean assembling;
    public boolean hasLastPacketSequenceNumber;
    public long lastPacketSequenceNumber;

    public FragmentState() {
      messageBuffer = new ParsableByteArray(/* limit= */ 0);
    }

    public void startMessage() {
      clearMessage();
      assembling = true;
    }

    public void clearMessage() {
      assembling = false;
      messageBuffer.setPosition(0);
      messageBuffer.setLimit(0);
    }
  }
}
