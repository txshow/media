/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.extractor;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;

/** Utility methods for parsing AV3A audio streams. */
@UnstableApi
public final class Av3aUtil {

  public static final int MAX_HEADER_SIZE = 9;
  private static final int SAMPLES_PER_FRAME = 1024;
  private static final int BOX_HEADER_SIZE = 8;
  private static final int HEADER_BITS_DEFAULT = 56;
  private static final int HEADER_BITS_MIX_WITHOUT_BED = 64;
  private static final int HEADER_BITS_MIX_WITH_BED = 72;

  private static final int CHANNEL_CONFIG_MONO = 0;
  private static final int CHANNEL_CONFIG_STEREO = 1;
  private static final int CHANNEL_CONFIG_MC_5_1 = 2;
  private static final int CHANNEL_CONFIG_MC_7_1 = 3;
  private static final int CHANNEL_CONFIG_MC_10_2 = 4;
  private static final int CHANNEL_CONFIG_MC_22_2 = 5;
  private static final int CHANNEL_CONFIG_MC_4_0 = 6;
  private static final int CHANNEL_CONFIG_MC_5_1_2 = 7;
  private static final int CHANNEL_CONFIG_MC_5_1_4 = 8;
  private static final int CHANNEL_CONFIG_MC_7_1_2 = 9;
  private static final int CHANNEL_CONFIG_MC_7_1_4 = 10;
  private static final int CHANNEL_CONFIG_HOA_ORDER1 = 11;
  private static final int CHANNEL_CONFIG_HOA_ORDER2 = 12;
  private static final int CHANNEL_CONFIG_HOA_ORDER3 = 13;
  private static final int CHANNEL_CONFIG_UNKNOWN = 14;

  private static final int SYNC_WORD = 0xFFF;
  private static final int AUDIO_CODEC_ID = 2;
  private static final int AUDIO_CODEC_ID_LOSSLESS = 1;
  private static final int AUDIO_CODEC_ID_GENERAL_AUDIO = 2;
  private static final int CODING_PROFILE_CHANNEL = 0;
  private static final int CODING_PROFILE_MIX = 1;
  private static final int CODING_PROFILE_HOA = 2;
  private static final int CONTENT_TYPE_CHANNEL = 0;
  private static final int CONTENT_TYPE_OBJECT = 1;
  private static final int CONTENT_TYPE_MIX = 2;
  private static final int CONTENT_TYPE_HOA = 3;

  private static final int[] SAMPLING_RATE_TABLE = {
    192000, 96000, 48000, 44100, 32000, 24000, 22050, 16000, 8000
  };

  private static final int[] CHANNEL_COUNT_TABLE = {
    /* MONO     */ 1,
    /* STEREO   */ 2,
    /* MC_5_1   */ 6,
    /* MC_7_1   */ 8,
    /* MC_10_2  */ 12,
    /* MC_22_2  */ 24,
    /* MC_4_0   */ 4,
    /* MC_5_1_2 */ 8,
    /* MC_5_1_4 */ 10,
    /* MC_7_1_2 */ 10,
    /* MC_7_1_4 */ 12,
    /* HOA_1    */ -1,
    /* HOA_2    */ -1,
    /* HOA_3    */ -1,
  };

  // avs3_rom_com.c bitrate tables, indexed by CHANNEL_CONFIG_* constant.
  private static final int[][] BITRATE_TABLES = {
    /* MONO     */ {
      16000, 32000, 44000, 56000, 64000, 72000, 80000, 96000, 128000, 144000, 164000, 192000
    },
    /* STEREO   */ {
      24000, 32000, 48000, 64000, 80000, 96000, 128000, 144000, 192000, 256000, 320000
    },
    /* MC_5_1   */ {
      192000, 256000, 320000, 384000, 448000, 512000, 640000, 720000, 144000, 96000, 128000, 160000
    },
    /* MC_7_1   */ {192000, 480000, 256000, 384000, 576000, 640000, 128000, 160000},
    /* MC_10_2  */ {},
    /* MC_22_2  */ {},
    /* MC_4_0   */ {48000, 96000, 128000, 192000, 256000},
    /* MC_5_1_2 */ {152000, 320000, 480000, 576000},
    /* MC_5_1_4 */ {176000, 384000, 576000, 704000, 256000, 448000},
    /* MC_7_1_2 */ {216000, 480000, 576000, 384000, 768000},
    /* MC_7_1_4 */ {240000, 608000, 384000, 512000, 832000},
    /* HOA_1    */ {48000, 96000, 128000, 192000, 256000},
    /* HOA_2    */ {192000, 256000, 320000, 384000, 480000, 512000, 640000},
    /* HOA_3    */ {256000, 320000, 384000, 512000, 640000, 896000},
  };

  @Nullable
  public static FrameHeader parseFrameHeader(ParsableBitArray data) {
    if (data.readBits(12) != SYNC_WORD) {
      return null;
    }
    if (data.readBits(4) != AUDIO_CODEC_ID) {
      return null;
    }
    if (data.readBits(1) != 0) {
      return null;
    }
    data.skipBits(3);
    int codingProfile = data.readBits(3);
    int samplingRateIdx = data.readBits(4);
    if (samplingRateIdx >= SAMPLING_RATE_TABLE.length) {
      return null;
    }
    int samplingRate = SAMPLING_RATE_TABLE[samplingRateIdx];
    data.skipBits(8);
    int channelNumIdx = 0;
    int numObjs = 0;
    int hoaOrder = 0;
    int soundBedType = 0;
    int bitrateIdxPerObj = 0;
    int bitrateIdxBedMc = 0;
    switch (codingProfile) {
      case CODING_PROFILE_CHANNEL:
        channelNumIdx = data.readBits(7);
        break;
      case CODING_PROFILE_MIX:
        soundBedType = data.readBits(2);
        if (soundBedType == 0) {
          numObjs = data.readBits(7) + 1;
          bitrateIdxPerObj = data.readBits(4);
        } else if (soundBedType == 1) {
          channelNumIdx = data.readBits(7);
          bitrateIdxBedMc = data.readBits(4);
          numObjs = data.readBits(7) + 1;
          bitrateIdxPerObj = data.readBits(4);
        } else {
          return null;
        }
        break;
      case CODING_PROFILE_HOA:
        hoaOrder = data.readBits(4) + 1;
        break;
      default:
        return null;
    }
    data.skipBits(2);
    int bitrateIdx = (codingProfile != CODING_PROFILE_MIX) ? data.readBits(4) : 0;
    data.skipBits(8);
    return buildFrameHeader(
        samplingRate,
        codingProfile,
        channelNumIdx,
        numObjs,
        hoaOrder,
        soundBedType,
        bitrateIdx,
        bitrateIdxPerObj,
        bitrateIdxBedMc);
  }

  public static int getFrameSize(FrameHeader header) {
    int totalBits =
        (int) ((long) header.totalBitrate * header.samplesPerFrame / header.samplingRate);
    int payloadBits = totalBits - header.headerBits;
    if (payloadBits <= 0) {
      return 0;
    }
    return Util.ceilDivide(header.headerBits, 8) + Util.ceilDivide(payloadBits, 8);
  }

  public static Config parseDca3Box(ParsableByteArray parent, int position, int size)
      throws ParserException {
    ExtractorUtil.checkContainerInput((long) position + size <= parent.limit(), "Invalid dca3 box");
    ExtractorUtil.checkContainerInput(size > BOX_HEADER_SIZE, "dca3 box must have payload");
    ParsableBitArray data = new ParsableBitArray(parent.getData(), position + size);
    data.setPosition((position + BOX_HEADER_SIZE) * 8);
    int audioCodecId = readBits(data, 4);
    if (audioCodecId == AUDIO_CODEC_ID_GENERAL_AUDIO) {
      return parseGeneralAudioSpecificConfig(data, audioCodecId);
    } else if (audioCodecId == AUDIO_CODEC_ID_LOSSLESS) {
      return parseLosslessSpecificConfig(data, audioCodecId);
    }
    return new Config(getCodecs(audioCodecId), Format.NO_VALUE, Format.NO_VALUE, Format.NO_VALUE);
  }

  @Nullable
  private static FrameHeader buildFrameHeader(
      int samplingRate,
      int codingProfile,
      int channelNumIdx,
      int numObjs,
      int hoaOrder,
      int soundBedType,
      int bitrateIdx,
      int bitrateIdxPerObj,
      int bitrateIdxBedMc) {
    switch (codingProfile) {
      case CODING_PROFILE_CHANNEL:
        if (isInvalidChannelConfig(channelNumIdx)
            || isInvalidBitrateIdx(channelNumIdx, bitrateIdx)) {
          return null;
        }
        return new FrameHeader(
            samplingRate,
            CHANNEL_COUNT_TABLE[channelNumIdx],
            BITRATE_TABLES[channelNumIdx][bitrateIdx],
            SAMPLES_PER_FRAME,
            HEADER_BITS_DEFAULT);
      case CODING_PROFILE_MIX:
        return buildMixHeader(
            samplingRate, channelNumIdx, numObjs, soundBedType, bitrateIdxPerObj, bitrateIdxBedMc);
      case CODING_PROFILE_HOA:
        int hoaConfigIdx = hoaOrderToConfigIdx(hoaOrder);
        if (hoaConfigIdx == CHANNEL_CONFIG_UNKNOWN
            || isInvalidBitrateIdx(hoaConfigIdx, bitrateIdx)) {
          return null;
        }
        return new FrameHeader(
            samplingRate,
            (hoaOrder + 1) * (hoaOrder + 1),
            BITRATE_TABLES[hoaConfigIdx][bitrateIdx],
            SAMPLES_PER_FRAME,
            HEADER_BITS_DEFAULT);
      default:
        return null;
    }
  }

  @Nullable
  private static FrameHeader buildMixHeader(
      int samplingRate,
      int channelNumIdx,
      int numObjs,
      int soundBedType,
      int bitrateIdxPerObj,
      int bitrateIdxBedMc) {
    if (soundBedType == 0) {
      if (isInvalidBitrateIdx(CHANNEL_CONFIG_MONO, bitrateIdxPerObj)) {
        return null;
      }
      int bitratePerObj = BITRATE_TABLES[CHANNEL_CONFIG_MONO][bitrateIdxPerObj];
      return new FrameHeader(
          samplingRate,
          numObjs,
          numObjs * bitratePerObj,
          SAMPLES_PER_FRAME,
          HEADER_BITS_MIX_WITHOUT_BED);
    }
    if (isInvalidChannelConfig(channelNumIdx)
        || isInvalidBitrateIdx(channelNumIdx, bitrateIdxBedMc)
        || isInvalidBitrateIdx(CHANNEL_CONFIG_MONO, bitrateIdxPerObj)) {
      return null;
    }
    int bedBitrate = BITRATE_TABLES[channelNumIdx][bitrateIdxBedMc];
    int bitratePerObj = BITRATE_TABLES[CHANNEL_CONFIG_MONO][bitrateIdxPerObj];
    return new FrameHeader(
        samplingRate,
        CHANNEL_COUNT_TABLE[channelNumIdx] + numObjs,
        bedBitrate + numObjs * bitratePerObj,
        SAMPLES_PER_FRAME,
        HEADER_BITS_MIX_WITH_BED);
  }

  private static Config parseGeneralAudioSpecificConfig(ParsableBitArray data, int audioCodecId)
      throws ParserException {
    int samplingFrequencyIndex = readBits(data, 4);
    int sampleRate = getSampleRate(samplingFrequencyIndex);
    skipBits(data, 3); // nn_type.
    skipBits(data, 1); // reserved.
    int contentType = readBits(data, 4);
    int channelCount = Format.NO_VALUE;
    if (contentType == CONTENT_TYPE_CHANNEL) {
      channelCount = getChannelCount(readBits(data, 7));
      skipBits(data, 1); // reserved.
    } else if (contentType == CONTENT_TYPE_OBJECT) {
      channelCount = readBits(data, 7);
      skipBits(data, 1); // reserved.
    } else if (contentType == CONTENT_TYPE_MIX) {
      int bedChannelCount = getChannelCount(readBits(data, 7));
      skipBits(data, 1); // reserved.
      int objectCount = readBits(data, 7);
      skipBits(data, 1); // reserved.
      if (bedChannelCount != Format.NO_VALUE) {
        channelCount = bedChannelCount + objectCount;
      }
    } else if (contentType == CONTENT_TYPE_HOA) {
      int hoaOrder = readBits(data, 4);
      channelCount = (hoaOrder + 1) * (hoaOrder + 1);
    } else {
      return new Config(getCodecs(audioCodecId), sampleRate, Format.NO_VALUE, Format.NO_VALUE);
    }
    int bitrate = readBits(data, 16) * 1000;
    return new Config(getCodecs(audioCodecId), sampleRate, channelCount, bitrate);
  }

  private static Config parseLosslessSpecificConfig(ParsableBitArray data, int audioCodecId)
      throws ParserException {
    int samplingFrequencyIndex = readBits(data, 4);
    int sampleRate =
        samplingFrequencyIndex == 0xF ? readBits(data, 24) : getSampleRate(samplingFrequencyIndex);
    skipBits(data, 1); // anc_data_index.
    skipBits(data, 3); // coding_profile.
    int channelCount = readBits(data, 8);
    return new Config(
        getCodecs(audioCodecId), sampleRate, channelCount, /* bitrate= */ Format.NO_VALUE);
  }

  private static int readBits(ParsableBitArray data, int numBits) throws ParserException {
    ExtractorUtil.checkContainerInput(data.bitsLeft() >= numBits, "Invalid dca3 box");
    return data.readBits(numBits);
  }

  private static void skipBits(ParsableBitArray data, int numBits) throws ParserException {
    ExtractorUtil.checkContainerInput(data.bitsLeft() >= numBits, "Invalid dca3 box");
    data.skipBits(numBits);
  }

  private static String getCodecs(int audioCodecId) {
    return Util.formatInvariant("av3a.%02d", audioCodecId);
  }

  private static int getSampleRate(int samplingFrequencyIndex) {
    return samplingFrequencyIndex >= 0 && samplingFrequencyIndex < SAMPLING_RATE_TABLE.length
        ? SAMPLING_RATE_TABLE[samplingFrequencyIndex]
        : Format.NO_VALUE;
  }

  private static int getChannelCount(int channelNumIndex) {
    return channelNumIndex >= 0
            && channelNumIndex < CHANNEL_COUNT_TABLE.length
            && CHANNEL_COUNT_TABLE[channelNumIndex] > 0
        ? CHANNEL_COUNT_TABLE[channelNumIndex]
        : Format.NO_VALUE;
  }

  private static boolean isInvalidChannelConfig(int configIdx) {
    return configIdx < 0
        || configIdx >= CHANNEL_COUNT_TABLE.length
        || CHANNEL_COUNT_TABLE[configIdx] <= 0
        || BITRATE_TABLES[configIdx].length == 0;
  }

  private static boolean isInvalidBitrateIdx(int configIdx, int bitrateIdx) {
    return configIdx < 0
        || configIdx >= BITRATE_TABLES.length
        || bitrateIdx < 0
        || bitrateIdx >= BITRATE_TABLES[configIdx].length;
  }

  private static int hoaOrderToConfigIdx(int hoaOrder) {
    switch (hoaOrder) {
      case 1:
        return CHANNEL_CONFIG_HOA_ORDER1;
      case 2:
        return CHANNEL_CONFIG_HOA_ORDER2;
      case 3:
        return CHANNEL_CONFIG_HOA_ORDER3;
      default:
        return CHANNEL_CONFIG_UNKNOWN;
    }
  }

  /** Data parsed from a dca3 box. */
  public static final class Config {

    public final String codecs;
    public final int sampleRate;
    public final int channelCount;
    public final int bitrate;

    private Config(String codecs, int sampleRate, int channelCount, int bitrate) {
      this.codecs = codecs;
      this.sampleRate = sampleRate;
      this.channelCount = channelCount;
      this.bitrate = bitrate;
    }
  }

  /** Data parsed from an AV3A frame header. */
  public static final class FrameHeader {

    public final int samplingRate;
    public final int channelCount;
    public final int totalBitrate;
    public final int samplesPerFrame;
    private final int headerBits;

    private FrameHeader(
        int samplingRate, int channelCount, int totalBitrate, int samplesPerFrame, int headerBits) {
      this.samplingRate = samplingRate;
      this.channelCount = channelCount;
      this.totalBitrate = totalBitrate;
      this.samplesPerFrame = samplesPerFrame;
      this.headerBits = headerBits;
    }
  }
}
