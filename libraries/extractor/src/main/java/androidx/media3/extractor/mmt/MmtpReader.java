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

import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.extractor.ts.TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR;
import static androidx.media3.extractor.ts.TsPayloadReader.FLAG_RANDOM_ACCESS_INDICATOR;
import static java.lang.Math.max;
import static java.lang.Math.min;

import android.util.LongSparseArray;
import android.util.SparseArray;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.container.ParsableNalUnitBitArray;
import androidx.media3.extractor.AacUtil;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ForwardingExtractorOutput;
import androidx.media3.extractor.ForwardingTrackOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.ts.ElementaryStreamReader;
import androidx.media3.extractor.ts.H264Reader;
import androidx.media3.extractor.ts.H265Reader;
import androidx.media3.extractor.ts.LatmReader;
import androidx.media3.extractor.ts.SeiReader;
import androidx.media3.extractor.ts.TsPayloadReader;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.Objects;

/** Parses MMTP packets and emits the media assets declared by MMT signaling. */
/* package */
final class MmtpReader {
  private static final String TAG = "MmtpReader";
  private static final int PAYLOAD_TYPE_MPU = 0;
  private static final int PAYLOAD_TYPE_SIGNALLING = 2;
  private static final int MPU_FRAGMENT_TYPE_MFU = 2;
  private static final int MAX_TRACKS = 64;
  private static final int MAX_FRAGMENT_CAPACITY = 32 * 1024 * 1024;
  private static final int MMT_DATA_HEADER_SIZE = 10 * Integer.BYTES;
  private static final int MMT_DATA_FLAG_FEC_TYPE_SHIFT = 1;
  private static final int MMT_DATA_FLAG_FEC_TYPE_PRESENT = 1 << 3;
  private static final int MMT_DATA_FLAG_SUBTITLE_RESOURCE = 1 << 4;
  private static final int MMT_SUBTITLE_RESOURCE_HEADER_SIZE =
      MMT_DATA_HEADER_SIZE + 3 * Integer.BYTES;
  private static final int ASSET_TYPE_MMTP = 0x6D6D7470;
  private static final int FI_COMPLETE = 0;
  private static final int FI_FIRST = 1;
  private static final int FI_MIDDLE = 2;
  private static final int FI_LAST = 3;
  private final MmtSignalingParser signalingParser = new MmtSignalingParser();
  private final SparseArray<TrackState> tracksByPacketId = new SparseArray<>();
  private final TsPayloadReader.TrackIdGenerator idGenerator;
  private final MmtTimestampAdjuster timestampAdjuster;
  private final FragmentMemoryBudget fragmentMemoryBudget;
  @Nullable private ExtractorOutput output;
  @Nullable private TrackOutput dataOutput;
  @Nullable private String dataFormatId;
  private boolean trackCreationEnded;

  public MmtpReader(int contextId, MmtTimestampAdjuster timestampAdjuster) {
    idGenerator = new TsPayloadReader.TrackIdGenerator(contextId, contextId << 16, 1);
    this.timestampAdjuster = timestampAdjuster;
    fragmentMemoryBudget = new FragmentMemoryBudget();
  }

  public void init(ExtractorOutput output, TrackOutput dataOutput, String dataFormatId) {
    this.output = output;
    this.dataOutput = dataOutput;
    this.dataFormatId = dataFormatId;
  }

  public boolean isTrackDiscoveryComplete() {
    if (!signalingParser.hasPackageTable()) {
      return false;
    }
    ImmutableList<MmtSignalingParser.Asset> assets = signalingParser.getAssets();
    for (int i = 0; i < assets.size(); i++) {
      MmtSignalingParser.Asset asset = assets.get(i);
      @Nullable TrackState track = tracksByPacketId.get(asset.packetId);
      if (track == null && tracksByPacketId.size() >= MAX_TRACKS) {
        continue;
      }
      if (!hasRequiredTrackConfiguration(asset)) {
        return false;
      }
      if (track != null) {
        continue;
      }
      if (asset.assetType == MmtSignalingParser.ASSET_TYPE_MP4A
          || asset.assetType == MmtSignalingParser.ASSET_TYPE_STPP) {
        continue;
      }
    }
    return true;
  }

  private static boolean isRawMpeg4Audio(MmtSignalingParser.Asset asset) {
    return asset.assetType == MmtSignalingParser.ASSET_TYPE_MP4A
        && asset.audioStreamType == MmtSignalingParser.AUDIO_STREAM_TYPE_RAW
        && (asset.audioStreamContent == MmtSignalingParser.AUDIO_STREAM_CONTENT_AAC
            || asset.audioStreamContent == MmtSignalingParser.AUDIO_STREAM_CONTENT_ALS);
  }

  private static boolean isSupportedAudioAsset(MmtSignalingParser.Asset asset) {
    if (asset.assetType != MmtSignalingParser.ASSET_TYPE_MP4A) {
      return false;
    }
    if (asset.audioStreamContent == MmtSignalingParser.AUDIO_STREAM_CONTENT_AAC
        && asset.audioStreamType == MmtSignalingParser.AUDIO_STREAM_TYPE_LATM) {
      return true;
    }
    return isRawMpeg4Audio(asset)
        && asset.audioSpecificConfig != null
        && asset.audioSpecificConfig.length > 0;
  }

  private static boolean hasRequiredTrackConfiguration(MmtSignalingParser.Asset asset) {
    if (asset.assetType == MmtSignalingParser.ASSET_TYPE_MP4A
        && !asset.audioComponentDescriptorPresent) {
      return false;
    }
    if (isRawMpeg4Audio(asset)
        && (asset.audioSpecificConfig == null || asset.audioSpecificConfig.length == 0)) {
      return false;
    }
    return asset.assetType != MmtSignalingParser.ASSET_TYPE_STPP || asset.subtitleDescriptorPresent;
  }

  public boolean hasAssetSignaling() {
    return signalingParser.hasPackageTable();
  }

  public void seek() {
    signalingParser.reset();
    for (int i = 0; i < tracksByPacketId.size(); i++) {
      tracksByPacketId.valueAt(i).seek();
    }
  }

  public void endOfInputReached() {
    for (int i = 0; i < tracksByPacketId.size(); i++) {
      tracksByPacketId.valueAt(i).endOfInputReached();
    }
  }

  public void finishTrackCreation() {
    trackCreationEnded = true;
  }

  public void consume(ParsableByteArray packet) {
    if (packet.bytesLeft() < 12) {
      return;
    }
    int firstHeaderByte = packet.readUnsignedByte();
    int secondHeaderByte = packet.readUnsignedByte();
    if ((firstHeaderByte & 0xC0) != 0) {
      return;
    }
    boolean packetCounterFlag = (firstHeaderByte & 0x20) != 0;
    int fecType = firstHeaderByte >> 3 & 0x03;
    boolean extensionHeaderFlag = (firstHeaderByte & 2) != 0;
    boolean randomAccessPoint = (firstHeaderByte & 1) != 0;
    int payloadType = secondHeaderByte & 0x3F;
    int packetId = packet.readUnsignedShort();
    long transmissionTimestamp = packet.readUnsignedInt();
    long packetSequenceNumber = packet.readUnsignedInt();
    if (packetCounterFlag) {
      if (packet.bytesLeft() < 4) {
        return;
      }
      packet.skipBytes(4);
    }
    if (extensionHeaderFlag) {
      if (packet.bytesLeft() < 4) {
        return;
      }
      packet.skipBytes(2);
      int extensionHeaderLength = packet.readUnsignedShort();
      if (packet.bytesLeft() < extensionHeaderLength) {
        return;
      }
      packet.skipBytes(extensionHeaderLength);
    }
    if (fecType == 1) {
      if (packet.bytesLeft() < 4) {
        return;
      }
      packet.setLimit(packet.limit() - 4);
    }
    if (fecType >= 2) {
      emitUnknownPayload(
          payloadType, fecType, packetId, transmissionTimestamp, packetSequenceNumber, packet);
      return;
    }
    switch (payloadType) {
      case PAYLOAD_TYPE_SIGNALLING:
        if (signalingParser.consume(packet, packetId, packetSequenceNumber)) {
          if (signalingParser.hasNtpAnchor()) {
            timestampAdjuster.setNtpAnchor(signalingParser.getNtpAnchor());
          }
          maybeCreateOrUpdateTracks();
        }
        break;
      case PAYLOAD_TYPE_MPU:
        consumeMpu(
            fecType,
            packetId,
            transmissionTimestamp,
            packetSequenceNumber,
            randomAccessPoint,
            packet);
        break;
      default:
        emitUnknownPayload(
            payloadType, fecType, packetId, transmissionTimestamp, packetSequenceNumber, packet);
        break;
    }
  }

  private void emitUnknownPayload(
      int payloadType,
      int fecType,
      int packetId,
      long transmissionTimestamp,
      long packetSequenceNumber,
      ParsableByteArray payload) {
    int payloadSize = payload.bytesLeft();
    byte[] sample = new byte[MMT_DATA_HEADER_SIZE + payloadSize];
    writeIntBigEndian(sample, /* offset= */ 0, ASSET_TYPE_MMTP);
    writeIntBigEndian(sample, /* offset= */ Integer.BYTES, packetId);
    writeIntBigEndian(sample, /* offset= */ 2 * Integer.BYTES, (int) packetSequenceNumber);
    writeIntBigEndian(
        sample,
        /* offset= */ 3 * Integer.BYTES,
        MMT_DATA_FLAG_FEC_TYPE_PRESENT | (fecType << MMT_DATA_FLAG_FEC_TYPE_SHIFT));
    writeIntBigEndian(sample, /* offset= */ 4 * Integer.BYTES, payloadType);
    System.arraycopy(
        payload.getData(), payload.getPosition(), sample, MMT_DATA_HEADER_SIZE, payloadSize);
    TrackOutput output = getDataOutput();
    output.sampleData(new ParsableByteArray(sample), sample.length);
    long timeUs =
        timestampAdjuster.hasNtpAnchor() && timestampAdjuster.hasTimestampOrigin()
            ? timestampAdjuster.adjustShortNtpTimestamp(
                transmissionTimestamp, timestampAdjuster.getNtpAnchor())
            : 0;
    output.sampleMetadata(
        timeUs, C.BUFFER_FLAG_KEY_FRAME, sample.length, /* offset= */ 0, /* cryptoData= */ null);
  }

  private void maybeCreateOrUpdateTracks() {
    if (output == null) {
      return;
    }
    ImmutableList<MmtSignalingParser.Asset> assets = signalingParser.getAssets();
    for (int i = 0; i < tracksByPacketId.size(); i++) {
      int packetId = tracksByPacketId.keyAt(i);
      boolean advertised = false;
      for (int j = 0; j < assets.size(); j++) {
        if (assets.get(j).packetId == packetId) {
          advertised = true;
          break;
        }
      }
      if (!advertised) {
        tracksByPacketId.valueAt(i).disablePayload();
      }
    }
    for (int i = 0; i < assets.size(); i++) {
      MmtSignalingParser.Asset asset = assets.get(i);
      @Nullable TrackState track = tracksByPacketId.get(asset.packetId);
      if (track != null) {
        if (track.canAcceptAsset(asset)) {
          track.updateAsset(asset);
          continue;
        }
        track.release();
        tracksByPacketId.remove(asset.packetId);
      }
      if (trackCreationEnded) {
        track = takeReusableTrack(asset);
        if (track == null) {
          if (tracksByPacketId.size() >= MAX_TRACKS) {
            continue;
          }
          track = createDataTrack(asset);
        }
        tracksByPacketId.put(asset.packetId, track);
        continue;
      }
      if (tracksByPacketId.size() >= MAX_TRACKS) {
        continue;
      }
      track = createTrack(asset);
      if (track != null) {
        tracksByPacketId.put(asset.packetId, track);
      }
    }
  }

  @Nullable
  private TrackState takeReusableTrack(MmtSignalingParser.Asset asset) {
    for (int i = 0; i < tracksByPacketId.size(); i++) {
      TrackState track = tracksByPacketId.valueAt(i);
      if (track.canReuseFor(asset)) {
        tracksByPacketId.removeAt(i);
        track.rebindAsset(asset);
        return track;
      }
    }
    return null;
  }

  @Nullable
  private TrackState createTrack(MmtSignalingParser.Asset asset) {
    if (output == null) {
      return null;
    }
    @Nullable ElementaryStreamReader reader = null;
    switch (asset.assetType) {
      case MmtSignalingParser.ASSET_TYPE_HEV1:
      case MmtSignalingParser.ASSET_TYPE_HVC1:
        reader =
            new H265Reader(
                new SeiReader(/* closedCaptionFormats= */ ImmutableList.of(), MimeTypes.VIDEO_H265),
                MimeTypes.VIDEO_MMT_TLV);
        break;
      case MmtSignalingParser.ASSET_TYPE_AVC1:
      case MmtSignalingParser.ASSET_TYPE_AVC3:
        reader =
            new H264Reader(
                new SeiReader(/* closedCaptionFormats= */ ImmutableList.of(), MimeTypes.VIDEO_H264),
                MimeTypes.VIDEO_MMT_TLV);
        break;
      case MmtSignalingParser.ASSET_TYPE_MP4A:
        if (asset.audioStreamContent == MmtSignalingParser.AUDIO_STREAM_CONTENT_AAC
            && asset.audioStreamType == MmtSignalingParser.AUDIO_STREAM_TYPE_LATM) {
          reader = new LatmReader(asset.language, /* roleFlags= */ 0, MimeTypes.VIDEO_MMT_TLV);
        } else if (isSupportedAudioAsset(asset)
            && asset.audioStreamContent == MmtSignalingParser.AUDIO_STREAM_CONTENT_AAC) {
          reader =
              new RawMpeg4AudioReader(
                  asset.language,
                  asset.audioSampleRate,
                  MimeTypes.AUDIO_AAC,
                  asset.audioSpecificConfig);
        } else if (isSupportedAudioAsset(asset)
            && asset.audioStreamContent == MmtSignalingParser.AUDIO_STREAM_CONTENT_ALS) {
          reader =
              new RawMpeg4AudioReader(
                  asset.language,
                  asset.audioSampleRate,
                  MimeTypes.AUDIO_MP4_ALS,
                  asset.audioSpecificConfig);
        }
        break;
      case MmtSignalingParser.ASSET_TYPE_STPP:
        if (asset.subtitleSupported) {
          idGenerator.generateNewId();
          TrackOutput subtitleOutput = output.track(idGenerator.getTrackId(), C.TRACK_TYPE_TEXT);
          TrackState subtitleTrack =
              new TrackState(
                  asset,
                  /* reader= */ null,
                  subtitleOutput,
                  getDataOutput(),
                  idGenerator.getFormatId(),
                  signalingParser,
                  timestampAdjuster,
                  fragmentMemoryBudget,
                  /* languageTrackOutput= */ null);
          subtitleTrack.updateSubtitleFormat();
          return subtitleTrack;
        }
        break;
      default:
        break;
    }
    if (reader == null) {
      return createDataTrack(asset);
    }
    LanguageExtractorOutput languageExtractorOutput =
        new LanguageExtractorOutput(output, asset.language);
    reader.createTracks(languageExtractorOutput, idGenerator);
    return new TrackState(
        asset,
        reader,
        /* subtitleOutput= */ null,
        /* dataOutput= */ null,
        /* formatId= */ null,
        signalingParser,
        timestampAdjuster,
        fragmentMemoryBudget,
        languageExtractorOutput.languageTrackOutput);
  }

  private TrackState createDataTrack(MmtSignalingParser.Asset asset) {
    return new TrackState(
        asset,
        /* reader= */ null,
        /* subtitleOutput= */ null,
        getDataOutput(),
        dataFormatId,
        signalingParser,
        timestampAdjuster,
        fragmentMemoryBudget,
        /* languageTrackOutput= */ null);
  }

  private TrackOutput getDataOutput() {
    return Objects.requireNonNull(dataOutput);
  }

  private void consumeMpu(
      int fecType,
      int packetId,
      long transmissionTimestamp,
      long packetSequenceNumber,
      boolean randomAccessPoint,
      ParsableByteArray payload) {
    int payloadStartPosition = payload.getPosition();
    if (payload.bytesLeft() < 8) {
      return;
    }
    int declaredPayloadLength = payload.readUnsignedShort();
    if (declaredPayloadLength != payload.bytesLeft()) {
      @Nullable TrackState track = tracksByPacketId.get(packetId);
      if (track != null) {
        track.discardFragment();
      }
      return;
    }
    int header = payload.readUnsignedByte();
    int fragmentType = header >> 4;
    boolean timed = (header & 8) != 0;
    int fragmentationIndicator = header >> 1 & 3;
    boolean aggregated = (header & 1) != 0;
    payload.skipBytes(1);
    long mpuSequenceNumber = payload.readUnsignedInt();
    @Nullable TrackState track = tracksByPacketId.get(packetId);
    if (fragmentType != MPU_FRAGMENT_TYPE_MFU) {
      if (track != null) {
        track.onPacketSequenceNumber(packetSequenceNumber);
      }
      payload.setPosition(payloadStartPosition);
      emitUnknownPayload(
          PAYLOAD_TYPE_MPU,
          fecType,
          packetId,
          transmissionTimestamp,
          packetSequenceNumber,
          payload);
      return;
    }
    if (track == null) {
      return;
    }
    if (!track.onPacketSequenceNumber(packetSequenceNumber)
        || (aggregated && fragmentationIndicator != FI_COMPLETE)) {
      return;
    }
    if (aggregated) {
      while (payload.bytesLeft() > 0) {
        if (payload.bytesLeft() < 2) {
          track.discardFragment();
          return;
        }
        int dataUnitLength = payload.readUnsignedShort();
        if (dataUnitLength == 0 || dataUnitLength > payload.bytesLeft()) {
          track.discardFragment();
          return;
        }
        int dataUnitLimit = payload.getPosition() + dataUnitLength;
        track.consumeDataUnit(
            FI_COMPLETE,
            timed,
            mpuSequenceNumber,
            transmissionTimestamp,
            randomAccessPoint,
            payload,
            dataUnitLimit);
        payload.setPosition(dataUnitLimit);
      }
    } else {
      track.consumeDataUnit(
          fragmentationIndicator,
          timed,
          mpuSequenceNumber,
          transmissionTimestamp,
          randomAccessPoint,
          payload,
          payload.limit());
    }
  }

  private static void writeIntBigEndian(byte[] data, int offset, int value) {
    data[offset] = (byte) (value >> 24);
    data[offset + 1] = (byte) (value >> 16);
    data[offset + 2] = (byte) (value >> 8);
    data[offset + 3] = (byte) value;
  }

  private static final class LanguageExtractorOutput extends ForwardingExtractorOutput {

    private final @Nullable String language;
    private int languageTrackId;
    @Nullable private LanguageTrackOutput languageTrackOutput;

    public LanguageExtractorOutput(ExtractorOutput output, @Nullable String language) {
      super(output);
      this.language = language;
      languageTrackId = C.INDEX_UNSET;
    }

    @Override
    public TrackOutput track(int id, @C.TrackType int type) {
      TrackOutput trackOutput = super.track(id, type);
      if (type != C.TRACK_TYPE_VIDEO && type != C.TRACK_TYPE_AUDIO) {
        return trackOutput;
      }
      if (languageTrackOutput == null) {
        languageTrackId = id;
        languageTrackOutput = new LanguageTrackOutput(trackOutput, language);
      }
      return id == languageTrackId ? languageTrackOutput : trackOutput;
    }
  }

  /* package */ static final class LanguageTrackOutput extends ForwardingTrackOutput {

    @Nullable private String language;
    @Nullable private Format sourceFormat;

    public LanguageTrackOutput(TrackOutput trackOutput, @Nullable String language) {
      super(trackOutput);
      this.language = language;
    }

    public void setLanguage(@Nullable String language) {
      if (Objects.equals(this.language, language)) {
        return;
      }
      this.language = language;
      if (sourceFormat != null) {
        super.format(withLanguage(sourceFormat, language));
      }
    }

    @Override
    public void format(Format format) {
      sourceFormat = format;
      super.format(withLanguage(format, language));
    }

    private static Format withLanguage(Format format, @Nullable String language) {
      return Objects.equals(format.language, language)
          ? format
          : format.buildUpon().setLanguage(language).build();
    }
  }

  private static final class FragmentMemoryBudget {
    private int capacity;

    public boolean tryReserve(int additionalCapacity) {
      if (additionalCapacity < 0 || additionalCapacity > MAX_FRAGMENT_CAPACITY - capacity) {
        return false;
      }
      capacity += additionalCapacity;
      return true;
    }

    public void release(int releasedCapacity) {
      checkState(releasedCapacity >= 0 && releasedCapacity <= capacity);
      capacity -= releasedCapacity;
    }
  }

  private static final class RawMpeg4AudioReader implements ElementaryStreamReader {
    @Nullable private final String language;
    private final int sampleRate;
    private final String sampleMimeType;
    @Nullable private byte[] audioSpecificConfig;
    @Nullable private String formatId;
    @Nullable private TrackOutput output;
    private long sampleTimeUs;
    private int sampleSize;

    public RawMpeg4AudioReader(
        @Nullable String language,
        int sampleRate,
        String sampleMimeType,
        @Nullable byte[] audioSpecificConfig) {
      this.language = language;
      this.sampleRate = sampleRate;
      this.sampleMimeType = sampleMimeType;
      this.audioSpecificConfig = audioSpecificConfig;
      sampleTimeUs = C.TIME_UNSET;
    }

    @Override
    public void seek() {
      sampleTimeUs = C.TIME_UNSET;
      sampleSize = 0;
    }

    @Override
    public void createTracks(
        ExtractorOutput extractorOutput, TsPayloadReader.TrackIdGenerator idGenerator) {
      idGenerator.generateNewId();
      formatId = idGenerator.getFormatId();
      output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_AUDIO);
      output.format(createFormat());
    }

    public void setAudioSpecificConfig(@Nullable byte[] audioSpecificConfig) {
      if (Arrays.equals(this.audioSpecificConfig, audioSpecificConfig)) {
        return;
      }
      this.audioSpecificConfig = audioSpecificConfig;
      if (output != null) {
        output.format(createFormat());
      }
    }

    private Format createFormat() {
      Format.Builder builder =
          new Format.Builder()
              .setId(formatId)
              .setContainerMimeType(MimeTypes.VIDEO_MMT_TLV)
              .setSampleMimeType(sampleMimeType)
              .setLanguage(language)
              .setSampleRate(sampleRate);
      if (audioSpecificConfig != null && audioSpecificConfig.length > 0) {
        builder.setInitializationData(ImmutableList.of(audioSpecificConfig));
        if (MimeTypes.AUDIO_AAC.equals(sampleMimeType)) {
          try {
            AacUtil.Config config = AacUtil.parseAudioSpecificConfig(audioSpecificConfig);
            builder
                .setCodecs(config.codecs)
                .setChannelCount(config.channelCount)
                .setSampleRate(config.sampleRateHz);
          } catch (ParserException | RuntimeException e) {
            Log.w(TAG, "Ignoring malformed raw AAC AudioSpecificConfig", e);
          }
        }
      }
      return builder.build();
    }

    @Override
    public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
      sampleTimeUs = pesTimeUs;
      sampleSize = 0;
    }

    @Override
    public void consume(ParsableByteArray data) {
      int bytesLeft = data.bytesLeft();
      Objects.requireNonNull(output).sampleData(data, bytesLeft);
      sampleSize += bytesLeft;
    }

    @Override
    public void packetFinished() {
      if (sampleTimeUs == C.TIME_UNSET || sampleSize == 0) {
        return;
      }
      Objects.requireNonNull(output)
          .sampleMetadata(
              sampleTimeUs,
              C.BUFFER_FLAG_KEY_FRAME,
              sampleSize,
              /* offset= */ 0,
              /* cryptoData= */ null);
      sampleTimeUs = C.TIME_UNSET;
      sampleSize = 0;
    }
  }

  private static final class TrackState {
    private static final int TIMED_MFU_HEADER_SIZE = 14;
    private static final int NON_TIMED_MFU_HEADER_SIZE = 4;
    private static final int MAX_MFU_SIZE = 16 * 1024 * 1024;
    private static final int MAX_TIMESTAMP_DESCRIPTORS = 32;
    private static final long DEFAULT_VIDEO_SAMPLE_DURATION_US = C.MICROS_PER_SECOND / 30;
    private static final long DEFAULT_AUDIO_SAMPLE_DURATION_US =
        1024L * C.MICROS_PER_SECOND / 48000;
    private MmtSignalingParser.Asset asset;
    @Nullable private final ElementaryStreamReader reader;
    @Nullable private final TrackOutput subtitleOutput;
    @Nullable private final TrackOutput dataOutput;
    @Nullable private final String formatId;
    private final MmtSignalingParser signalingParser;
    private final MmtTimestampAdjuster timestampAdjuster;
    private final FragmentMemoryBudget fragmentMemoryBudget;
    @Nullable private final LanguageTrackOutput languageTrackOutput;
    private final ParsableByteArray mfuBuffer;
    private boolean assembling;
    private boolean hasLastPacketSequenceNumber;
    private long lastPacketSequenceNumber;
    private long assembledMpuSequenceNumber;
    private long assembledItemId;
    private long assembledMovieFragmentSequenceNumber;
    private long assembledSampleNumber;
    private long assembledMfuOffset;
    private int assembledPriority;
    private int assembledDependencyCounter;
    private long assembledTransmissionTimestamp;
    private boolean assembledTimed;
    private boolean assembledRandomAccessPoint;
    private long fallbackSampleTimeUs;
    private long lastFallbackMpuSequenceNumber;
    private int lastFallbackAccessUnitIndex;
    private boolean hasFallbackSample;
    private boolean hasTimingMpuSequenceNumber;
    private long timingMpuSequenceNumber;
    private int nextAudioAccessUnitIndex;
    private int currentVideoAccessUnitIndex;
    private boolean videoAccessUnitStartedByAud;
    private boolean startedAtRandomAccessPoint;
    private boolean payloadEnabled;
    @Nullable private Format subtitleFormat;

    public TrackState(
        MmtSignalingParser.Asset asset,
        @Nullable ElementaryStreamReader reader,
        @Nullable TrackOutput subtitleOutput,
        @Nullable TrackOutput dataOutput,
        @Nullable String formatId,
        MmtSignalingParser signalingParser,
        MmtTimestampAdjuster timestampAdjuster,
        FragmentMemoryBudget fragmentMemoryBudget,
        @Nullable LanguageTrackOutput languageTrackOutput) {
      this.asset = asset;
      this.reader = reader;
      this.subtitleOutput = subtitleOutput;
      this.dataOutput = dataOutput;
      this.formatId = formatId;
      this.signalingParser = signalingParser;
      this.timestampAdjuster = timestampAdjuster;
      this.fragmentMemoryBudget = fragmentMemoryBudget;
      this.languageTrackOutput = languageTrackOutput;
      payloadEnabled = true;
      mfuBuffer = new ParsableByteArray(0);
    }

    public void updateAsset(MmtSignalingParser.Asset asset) {
      if (!TrackState.isCompatibleAssetType(this.asset.assetType, asset.assetType)) {
        disablePayload();
        return;
      }
      TrackState.mergeRetainedAssetState(this.asset, asset);
      this.asset = asset;
      payloadEnabled = true;
      updateReaderFormat(asset);
      if (languageTrackOutput != null) {
        languageTrackOutput.setLanguage(asset.language);
      }
      if (subtitleOutput != null) {
        updateSubtitleFormat();
      }
    }

    public void rebindAsset(MmtSignalingParser.Asset asset) {
      this.asset = asset;
      payloadEnabled = true;
      seek();
      updateReaderFormat(asset);
      if (languageTrackOutput != null) {
        languageTrackOutput.setLanguage(asset.language);
      }
      if (subtitleOutput != null) {
        updateSubtitleFormat();
      }
    }

    public void disablePayload() {
      if (!payloadEnabled) {
        return;
      }
      payloadEnabled = false;
      discardFragment();
      startedAtRandomAccessPoint = false;
      hasLastPacketSequenceNumber = false;
      if (reader != null) {
        reader.seek();
      }
    }

    public void release() {
      disablePayload();
      fragmentMemoryBudget.release(mfuBuffer.capacity());
      mfuBuffer.reset(Util.EMPTY_BYTE_ARRAY);
    }

    private static boolean isCompatibleAssetType(int first, int second) {
      return first == second
          || TrackState.isHevcAssetType(first) && TrackState.isHevcAssetType(second)
          || TrackState.isAvcAssetType(first) && TrackState.isAvcAssetType(second)
          || TrackState.isDataAssetType(first) && TrackState.isDataAssetType(second);
    }

    private static boolean isHevcAssetType(int assetType) {
      return assetType == MmtSignalingParser.ASSET_TYPE_HEV1
          || assetType == MmtSignalingParser.ASSET_TYPE_HVC1;
    }

    private static boolean isAvcAssetType(int assetType) {
      return assetType == MmtSignalingParser.ASSET_TYPE_AVC1
          || assetType == MmtSignalingParser.ASSET_TYPE_AVC3;
    }

    private static boolean isDataAssetType(int assetType) {
      return assetType == MmtSignalingParser.ASSET_TYPE_AAPP
          || assetType == MmtSignalingParser.ASSET_TYPE_ASGD
          || assetType == MmtSignalingParser.ASSET_TYPE_AAGD;
    }

    private static void mergeRetainedAssetState(
        MmtSignalingParser.Asset previous, MmtSignalingParser.Asset updated) {
      if (updated.language == null) {
        updated.language = previous.language;
      }
      boolean sameAudioFormat =
          !updated.audioComponentDescriptorPresent
              || (previous.audioStreamContent == updated.audioStreamContent
                  && previous.audioStreamType == updated.audioStreamType);
      if (!updated.audioSpecificConfigDescriptorPresent && sameAudioFormat) {
        updated.audioSpecificConfigDescriptorPresent =
            previous.audioSpecificConfigDescriptorPresent;
        updated.audioSpecificConfig = previous.audioSpecificConfig;
      }
      if (!updated.audioComponentDescriptorPresent) {
        updated.audioComponentDescriptorPresent = previous.audioComponentDescriptorPresent;
        updated.audioStreamContent = previous.audioStreamContent;
        updated.audioStreamType = previous.audioStreamType;
        updated.audioSampleRate = previous.audioSampleRate;
      }
      if (updated.timescale == C.RATE_UNSET_INT) {
        updated.timescale = previous.timescale;
      }
      if (!updated.videoComponentDescriptorPresent) {
        updated.videoComponentDescriptorPresent = previous.videoComponentDescriptorPresent;
        updated.videoFrameRateNumerator = previous.videoFrameRateNumerator;
        updated.videoFrameRateDenominator = previous.videoFrameRateDenominator;
      }
      if (!updated.subtitleDescriptorPresent) {
        updated.subtitleDescriptorPresent = previous.subtitleDescriptorPresent;
        updated.subtitleTmd = previous.subtitleTmd;
        updated.subtitleResolution = previous.subtitleResolution;
        updated.subtitleSupported = previous.subtitleSupported;
        updated.superimpose = previous.superimpose;
        updated.hasSubtitleReferenceTime = previous.hasSubtitleReferenceTime;
        updated.subtitleReferenceTimeNtp = previous.subtitleReferenceTimeNtp;
      }
      TrackState.mergeTimestampMap(previous.presentationTimesNtp, updated.presentationTimesNtp);
      TrackState.mergeTimestampMap(previous.extendedTimestamps, updated.extendedTimestamps);
    }

    private void updateReaderFormat(MmtSignalingParser.Asset asset) {
      if (reader instanceof RawMpeg4AudioReader) {
        ((RawMpeg4AudioReader) reader).setAudioSpecificConfig(asset.audioSpecificConfig);
      }
    }

    private static <T> void mergeTimestampMap(
        LongSparseArray<T> previous, LongSparseArray<T> updated) {
      while (updated.size() > MAX_TIMESTAMP_DESCRIPTORS) {
        updated.removeAt(0);
      }
      for (int i = previous.size() - 1; i >= 0 && updated.size() < MAX_TIMESTAMP_DESCRIPTORS; i--) {
        long key = previous.keyAt(i);
        if (updated.indexOfKey(key) >= 0) {
          continue;
        }
        updated.put(key, previous.valueAt(i));
      }
    }

    public void updateSubtitleFormat() {
      if (subtitleOutput == null || !asset.subtitleSupported) {
        return;
      }
      Format updatedFormat =
          new Format.Builder()
              .setId(formatId)
              .setContainerMimeType(MimeTypes.VIDEO_MMT_TLV)
              .setSampleMimeType(MimeTypes.APPLICATION_TTML)
              .setLanguage(asset.language)
              .setLabel(asset.superimpose ? "Superimpose" : "Caption")
              .setRoleFlags(C.ROLE_FLAG_CAPTION)
              .setWidth(1920 << asset.subtitleResolution)
              .setHeight(1080 << asset.subtitleResolution)
              .build();
      if (!updatedFormat.equals(subtitleFormat)) {
        subtitleFormat = updatedFormat;
        subtitleOutput.format(updatedFormat);
      }
    }

    public void seek() {
      if (reader != null) {
        reader.seek();
      }
      discardFragment();
      hasLastPacketSequenceNumber = false;
      fallbackSampleTimeUs = 0L;
      hasFallbackSample = false;
      hasTimingMpuSequenceNumber = false;
      startedAtRandomAccessPoint = false;
    }

    public void endOfInputReached() {
      if (reader != null) {
        reader.endOfInputReached();
      }
    }

    public boolean onPacketSequenceNumber(long packetSequenceNumber) {
      if (!hasLastPacketSequenceNumber) {
        hasLastPacketSequenceNumber = true;
        lastPacketSequenceNumber = packetSequenceNumber;
        return true;
      }
      long expectedPacketSequenceNumber = (lastPacketSequenceNumber + 1) & 0xFFFFFFFFL;
      if (packetSequenceNumber == expectedPacketSequenceNumber) {
        lastPacketSequenceNumber = packetSequenceNumber;
        return true;
      }
      if (!TrackState.isSequenceAfter(packetSequenceNumber, lastPacketSequenceNumber)) {
        return false;
      }
      lastPacketSequenceNumber = packetSequenceNumber;
      discardFragment();
      return true;
    }

    private static boolean isSequenceAfter(long value, long reference) {
      long distance = (value - reference) & 0xFFFFFFFFL;
      return distance != 0 && distance < 0x80000000L;
    }

    public void discardFragment() {
      assembling = false;
      mfuBuffer.setPosition(0);
      mfuBuffer.setLimit(0);
    }

    public void consumeDataUnit(
        int fragmentationIndicator,
        boolean timed,
        long mpuSequenceNumber,
        long transmissionTimestamp,
        boolean randomAccessPoint,
        ParsableByteArray payload,
        int limit) {
      if (limit < payload.getPosition() || limit > payload.limit()) {
        discardFragment();
        return;
      }
      if (!payloadEnabled) {
        payload.setPosition(limit);
        discardFragment();
        return;
      }
      if ((reader != null || subtitleOutput != null)
          && !startedAtRandomAccessPoint
          && (!randomAccessPoint
              || (fragmentationIndicator != FI_COMPLETE && fragmentationIndicator != FI_FIRST))) {
        payload.setPosition(limit);
        return;
      }
      int headerSize = timed ? TIMED_MFU_HEADER_SIZE : NON_TIMED_MFU_HEADER_SIZE;
      if (payload.getPosition() > limit - headerSize) {
        payload.setPosition(limit);
        discardFragment();
        return;
      }
      long itemId = C.INDEX_UNSET;
      long movieFragmentSequenceNumber = C.INDEX_UNSET;
      long sampleNumber = C.INDEX_UNSET;
      long mfuOffset = C.INDEX_UNSET;
      int priority = C.INDEX_UNSET;
      int dependencyCounter = C.INDEX_UNSET;
      if (timed) {
        movieFragmentSequenceNumber = payload.readUnsignedInt();
        sampleNumber = payload.readUnsignedInt();
        mfuOffset = payload.readUnsignedInt();
        priority = payload.readUnsignedByte();
        dependencyCounter = payload.readUnsignedByte();
      } else {
        itemId = payload.readUnsignedInt();
      }
      if (!startedAtRandomAccessPoint) {
        startedAtRandomAccessPoint = true;
      }
      switch (fragmentationIndicator) {
        case FI_COMPLETE:
          {
            discardFragment();
            startDataUnit(
                timed,
                mpuSequenceNumber,
                itemId,
                movieFragmentSequenceNumber,
                sampleNumber,
                mfuOffset,
                priority,
                dependencyCounter,
                transmissionTimestamp,
                randomAccessPoint);
            appendData(payload, limit);
            emitMfu();
            break;
          }
        case FI_FIRST:
          {
            discardFragment();
            startDataUnit(
                timed,
                mpuSequenceNumber,
                itemId,
                movieFragmentSequenceNumber,
                sampleNumber,
                mfuOffset,
                priority,
                dependencyCounter,
                transmissionTimestamp,
                randomAccessPoint);
            appendData(payload, limit);
            break;
          }
        case FI_MIDDLE:
          {
            if (continueDataUnit(
                timed,
                mpuSequenceNumber,
                itemId,
                movieFragmentSequenceNumber,
                sampleNumber,
                mfuOffset,
                priority,
                dependencyCounter,
                transmissionTimestamp,
                randomAccessPoint)) {
              appendData(payload, limit);
              break;
            }
            payload.setPosition(limit);
            discardFragment();
            break;
          }
        case FI_LAST:
          {
            if (continueDataUnit(
                timed,
                mpuSequenceNumber,
                itemId,
                movieFragmentSequenceNumber,
                sampleNumber,
                mfuOffset,
                priority,
                dependencyCounter,
                transmissionTimestamp,
                randomAccessPoint)) {
              appendData(payload, limit);
              emitMfu();
              break;
            }
            payload.setPosition(limit);
            discardFragment();
            break;
          }
        default:
          {
            discardFragment();
          }
      }
    }

    private void startDataUnit(
        boolean timed,
        long mpuSequenceNumber,
        long itemId,
        long movieFragmentSequenceNumber,
        long sampleNumber,
        long mfuOffset,
        int priority,
        int dependencyCounter,
        long transmissionTimestamp,
        boolean randomAccessPoint) {
      assembling = true;
      assembledMpuSequenceNumber = mpuSequenceNumber;
      assembledItemId = itemId;
      assembledMovieFragmentSequenceNumber = movieFragmentSequenceNumber;
      assembledSampleNumber = sampleNumber;
      assembledMfuOffset = mfuOffset;
      assembledPriority = priority;
      assembledDependencyCounter = dependencyCounter;
      assembledTransmissionTimestamp = transmissionTimestamp;
      assembledTimed = timed;
      assembledRandomAccessPoint = randomAccessPoint;
    }

    private boolean continueDataUnit(
        boolean timed,
        long mpuSequenceNumber,
        long itemId,
        long movieFragmentSequenceNumber,
        long sampleNumber,
        long mfuOffset,
        int priority,
        int dependencyCounter,
        long transmissionTimestamp,
        boolean randomAccessPoint) {
      if (!assembling
          || timed != assembledTimed
          || mpuSequenceNumber != assembledMpuSequenceNumber
          || itemId != assembledItemId
          || movieFragmentSequenceNumber != assembledMovieFragmentSequenceNumber
          || sampleNumber != assembledSampleNumber
          || mfuOffset != assembledMfuOffset
          || priority != assembledPriority
          || dependencyCounter != assembledDependencyCounter) {
        return false;
      }
      assembledTransmissionTimestamp = transmissionTimestamp;
      assembledRandomAccessPoint |= randomAccessPoint;
      return true;
    }

    private void appendData(ParsableByteArray payload, int limit) {
      int length = limit - payload.getPosition();
      int currentLength = mfuBuffer.limit();
      if (length < 0 || currentLength > MAX_MFU_SIZE - length) {
        payload.setPosition(limit);
        discardFragment();
        return;
      }
      int requiredCapacity = currentLength + length;
      if (requiredCapacity > mfuBuffer.capacity()) {
        int grownCapacity =
            min(MAX_MFU_SIZE, max(max(mfuBuffer.capacity() * 2, 1024), requiredCapacity));
        if (!fragmentMemoryBudget.tryReserve(grownCapacity - mfuBuffer.capacity())) {
          payload.setPosition(limit);
          discardFragment();
          return;
        }
        byte[] grown = new byte[grownCapacity];
        System.arraycopy(mfuBuffer.getData(), 0, grown, 0, currentLength);
        mfuBuffer.reset(grown, currentLength);
      }
      payload.readBytes(mfuBuffer.getData(), currentLength, length);
      mfuBuffer.setLimit(requiredCapacity);
    }

    private void emitMfu() {
      if (!assembling || mfuBuffer.limit() == 0) {
        discardFragment();
        return;
      }
      assembling = false;
      mfuBuffer.setPosition(0);
      try {
        switch (asset.assetType) {
          case MmtSignalingParser.ASSET_TYPE_HEV1:
          case MmtSignalingParser.ASSET_TYPE_HVC1:
          case MmtSignalingParser.ASSET_TYPE_AVC1:
          case MmtSignalingParser.ASSET_TYPE_AVC3:
            if (reader != null) {
              emitVideoMfu();
            } else {
              emitDataMfu();
            }
            break;
          case MmtSignalingParser.ASSET_TYPE_MP4A:
            if (reader != null) {
              emitAudioMfu();
            } else {
              emitDataMfu();
            }
            break;
          case MmtSignalingParser.ASSET_TYPE_STPP:
            emitSubtitleMfu();
            break;
          case MmtSignalingParser.ASSET_TYPE_AAPP:
          case MmtSignalingParser.ASSET_TYPE_ASGD:
          case MmtSignalingParser.ASSET_TYPE_AAGD:
            emitDataMfu();
            break;
          default:
            emitDataMfu();
            break;
        }
      } catch (ParserException | RuntimeException e) {
        Log.w(TAG, "Discarding malformed MFU for packet_id " + asset.packetId, e);
      } finally {
        mfuBuffer.setPosition(0);
        mfuBuffer.setLimit(0);
      }
    }

    private void emitVideoMfu() throws ParserException {
      int accessUnitIndex = updateVideoAccessUnitIndex();
      if (reader == null
          || accessUnitIndex == C.INDEX_UNSET
          || !TrackState.convertLengthPrefixedNalUnitsToAnnexB(mfuBuffer)) {
        return;
      }
      int flags = FLAG_DATA_ALIGNMENT_INDICATOR;
      if (assembledRandomAccessPoint) {
        flags |= FLAG_RANDOM_ACCESS_INDICATOR;
      }
      reader.packetStarted(getSampleTimeUs(accessUnitIndex), flags);
      reader.consume(mfuBuffer);
      reader.packetFinished();
    }

    private void emitAudioMfu() throws ParserException {
      if (reader == null) {
        emitDataMfu();
        return;
      }
      boolean isAacLatm =
          asset.audioStreamContent == MmtSignalingParser.AUDIO_STREAM_CONTENT_AAC
              && asset.audioStreamType == MmtSignalingParser.AUDIO_STREAM_TYPE_LATM;
      if (isAacLatm && mfuBuffer.limit() >= 1 << 13) {
        return;
      }
      int accessUnitIndex = getNextAudioAccessUnitIndex();
      reader.packetStarted(getSampleTimeUs(accessUnitIndex), FLAG_DATA_ALIGNMENT_INDICATOR);
      if (isAacLatm) {
        int audioMuxLength = mfuBuffer.limit();
        byte[] loasFrame = new byte[audioMuxLength + 3];
        loasFrame[0] = 0x56;
        loasFrame[1] = (byte) (0xE0 | audioMuxLength >> 8);
        loasFrame[2] = (byte) audioMuxLength;
        System.arraycopy(mfuBuffer.getData(), 0, loasFrame, 3, audioMuxLength);
        reader.consume(new ParsableByteArray(loasFrame));
      } else {
        reader.consume(mfuBuffer);
      }
      reader.packetFinished();
    }

    private void emitSubtitleMfu() {
      if (mfuBuffer.bytesLeft() < 5) {
        return;
      }
      int mfuStartPosition = mfuBuffer.getPosition();
      int dataType = mfuBuffer.getData()[mfuBuffer.getPosition() + 4] >> 4 & 0x0F;
      if (dataType == 0 && (subtitleOutput == null || !asset.subtitleSupported)) {
        emitDataMfu();
        return;
      }
      mfuBuffer.skipBytes(2);
      int subsampleNumber = mfuBuffer.readUnsignedByte();
      int lastSubsampleNumber = mfuBuffer.readUnsignedByte();
      int flags = mfuBuffer.readUnsignedByte();
      boolean extendedLength = (flags & 0x08) != 0;
      boolean hasSubsampleInfo = (flags & 0x04) != 0;
      int lengthFieldSize = extendedLength ? 4 : 2;
      if (mfuBuffer.bytesLeft() < lengthFieldSize) {
        preserveMalformedSubtitleResource(dataType, mfuStartPosition);
        return;
      }
      long dataSize = extendedLength ? mfuBuffer.readUnsignedInt() : mfuBuffer.readUnsignedShort();
      if (subsampleNumber == 0 && lastSubsampleNumber > 0 && hasSubsampleInfo) {
        int entrySize = extendedLength ? 5 : 3;
        int infoSize = lastSubsampleNumber * entrySize;
        if (infoSize > mfuBuffer.bytesLeft()) {
          preserveMalformedSubtitleResource(dataType, mfuStartPosition);
          return;
        }
        mfuBuffer.skipBytes(infoSize);
      }
      if (dataSize > mfuBuffer.bytesLeft() || dataSize > Integer.MAX_VALUE) {
        preserveMalformedSubtitleResource(dataType, mfuStartPosition);
        return;
      }
      int sampleSize = (int) dataSize;
      if (dataType != 0) {
        emitSubtitleResourceMfu(dataType, subsampleNumber, lastSubsampleNumber, sampleSize);
        return;
      }
      subtitleOutput.sampleData(mfuBuffer, sampleSize);
      subtitleOutput.sampleMetadata(
          getSubtitleTimeUs(),
          C.BUFFER_FLAG_KEY_FRAME,
          sampleSize,
          /* offset= */ 0,
          /* cryptoData= */ null);
    }

    private void preserveMalformedSubtitleResource(int dataType, int mfuStartPosition) {
      if (dataType == 0) {
        return;
      }
      mfuBuffer.setPosition(mfuStartPosition);
      emitDataMfu();
    }

    private void emitSubtitleResourceMfu(
        int dataType, int subsampleNumber, int lastSubsampleNumber, int payloadSize) {
      if (dataOutput == null) {
        return;
      }
      byte[] sample = new byte[MMT_SUBTITLE_RESOURCE_HEADER_SIZE + payloadSize];
      int offset = writeMfuHeader(sample, MMT_DATA_FLAG_SUBTITLE_RESOURCE);
      writeIntBigEndian(sample, offset, dataType);
      offset += Integer.BYTES;
      writeIntBigEndian(sample, offset, subsampleNumber);
      offset += Integer.BYTES;
      writeIntBigEndian(sample, offset, lastSubsampleNumber);
      offset += Integer.BYTES;
      System.arraycopy(mfuBuffer.getData(), mfuBuffer.getPosition(), sample, offset, payloadSize);
      emitDataSample(sample, getSubtitleTimeUs());
    }

    private void emitDataMfu() {
      if (dataOutput == null) {
        return;
      }
      int payloadSize = mfuBuffer.bytesLeft();
      byte[] sample = new byte[MMT_DATA_HEADER_SIZE + payloadSize];
      int offset = writeMfuHeader(sample, /* additionalFlags= */ 0);
      System.arraycopy(mfuBuffer.getData(), mfuBuffer.getPosition(), sample, offset, payloadSize);
      emitDataSample(
          sample,
          asset.assetType == MmtSignalingParser.ASSET_TYPE_STPP
              ? getSubtitleTimeUs()
              : getSampleTimeUs(/* accessUnitIndex= */ 0));
    }

    private int writeMfuHeader(byte[] sample, int additionalFlags) {
      int offset = 0;
      writeIntBigEndian(sample, offset, asset.assetType);
      offset += Integer.BYTES;
      writeIntBigEndian(sample, offset, asset.packetId);
      offset += Integer.BYTES;
      writeIntBigEndian(sample, offset, (int) assembledMpuSequenceNumber);
      offset += Integer.BYTES;
      writeIntBigEndian(sample, offset, (assembledTimed ? 1 : 0) | additionalFlags);
      offset += Integer.BYTES;
      writeIntBigEndian(sample, offset, assembledTimed ? 0 : (int) assembledItemId);
      offset += Integer.BYTES;
      writeIntBigEndian(
          sample, offset, assembledTimed ? (int) assembledMovieFragmentSequenceNumber : 0);
      offset += Integer.BYTES;
      writeIntBigEndian(sample, offset, assembledTimed ? (int) assembledSampleNumber : 0);
      offset += Integer.BYTES;
      writeIntBigEndian(sample, offset, assembledTimed ? (int) assembledMfuOffset : 0);
      offset += Integer.BYTES;
      writeIntBigEndian(sample, offset, assembledTimed ? assembledPriority : 0);
      offset += Integer.BYTES;
      writeIntBigEndian(sample, offset, assembledTimed ? assembledDependencyCounter : 0);
      offset += Integer.BYTES;
      return offset;
    }

    private void emitDataSample(byte[] sample, long timeUs) {
      ParsableByteArray sampleData = new ParsableByteArray(sample);
      TrackOutput output = Objects.requireNonNull(dataOutput);
      output.sampleData(sampleData, sample.length);
      output.sampleMetadata(
          timeUs, C.BUFFER_FLAG_KEY_FRAME, sample.length, /* offset= */ 0, /* cryptoData= */ null);
    }

    private long getSampleTimeUs(int accessUnitIndex) {
      @Nullable
      Long presentationTimeNtp = asset.presentationTimesNtp.get(assembledMpuSequenceNumber);
      @Nullable
      MmtSignalingParser.ExtendedTimestampDescriptor extendedTimestamp =
          asset.extendedTimestamps.get(assembledMpuSequenceNumber);
      long fixedPtsOffset =
          extendedTimestamp == null || extendedTimestamp.ptsOffsetType != 0
              ? C.TIME_UNSET
              : getFixedPtsOffset();
      if (presentationTimeNtp != null
          && extendedTimestamp != null
          && asset.timescale > 0
          && accessUnitIndex >= 0
          && accessUnitIndex < extendedTimestamp.dtsPtsOffsets.length
          && (extendedTimestamp.ptsOffsetType != 0 || fixedPtsOffset != C.TIME_UNSET)) {
        long offset = -extendedTimestamp.decodingTimeOffset;
        for (int i = 0; i < accessUnitIndex; i++) {
          offset +=
              extendedTimestamp.ptsOffsetType == 0
                  ? fixedPtsOffset
                  : extendedTimestamp.ptsOffsets[i];
        }
        offset += extendedTimestamp.dtsPtsOffsets[accessUnitIndex];
        long offsetUs = (offset * C.MICROS_PER_SECOND) / asset.timescale;
        return timestampAdjuster.adjustNtpTimestamp(presentationTimeNtp, offsetUs);
      }
      if (assembledTimed && dataOutput != null && presentationTimeNtp != null) {
        return timestampAdjuster.adjustNtpTimestamp(presentationTimeNtp);
      }
      if (timestampAdjuster.hasNtpAnchor()) {
        return timestampAdjuster.adjustShortNtpTimestamp(
            assembledTransmissionTimestamp, timestampAdjuster.getNtpAnchor());
      }
      return getFallbackSampleTimeUs(accessUnitIndex);
    }

    public boolean canReuseFor(MmtSignalingParser.Asset asset) {
      if (!hasRequiredTrackConfiguration(asset)) {
        return false;
      }
      return !payloadEnabled && canAcceptAsset(asset);
    }

    public boolean canAcceptAsset(MmtSignalingParser.Asset asset) {
      if (!TrackState.isCompatibleAssetType(this.asset.assetType, asset.assetType)) {
        return false;
      }
      if (asset.assetType == MmtSignalingParser.ASSET_TYPE_MP4A) {
        if (!asset.audioComponentDescriptorPresent) {
          return true;
        }
        boolean sameAudioFormat =
            this.asset.audioStreamContent == asset.audioStreamContent
                && this.asset.audioStreamType == asset.audioStreamType;
        boolean supportedAudioAsset = isSupportedAudioAsset(asset);
        if (sameAudioFormat
            && isRawMpeg4Audio(asset)
            && !asset.audioSpecificConfigDescriptorPresent
            && this.asset.audioSpecificConfig != null
            && this.asset.audioSpecificConfig.length > 0) {
          supportedAudioAsset = true;
        }
        return sameAudioFormat && (reader != null) == supportedAudioAsset;
      }
      if (asset.assetType == MmtSignalingParser.ASSET_TYPE_STPP) {
        if (!asset.subtitleDescriptorPresent) {
          return true;
        }
        return (subtitleOutput != null) == asset.subtitleSupported;
      }
      return true;
    }

    private long getFixedPtsOffset() {
      if (asset.videoFrameRateNumerator > 0 && asset.videoFrameRateDenominator > 0) {
        long duration =
            ((long) asset.timescale * asset.videoFrameRateDenominator)
                / asset.videoFrameRateNumerator;
        return duration > 0 ? duration : C.TIME_UNSET;
      }
      if (asset.audioStreamContent == MmtSignalingParser.AUDIO_STREAM_CONTENT_AAC
          && asset.audioSampleRate > 0) {
        long duration = (1024L * asset.timescale) / asset.audioSampleRate;
        return duration > 0 ? duration : C.TIME_UNSET;
      }
      return C.TIME_UNSET;
    }

    private long getSubtitleTimeUs() {
      if (asset.hasSubtitleReferenceTime) {
        if (timestampAdjuster.hasNtpAnchor()) {
          timestampAdjuster.adjustShortNtpTimestamp(
              assembledTransmissionTimestamp, timestampAdjuster.getNtpAnchor());
        }
        return timestampAdjuster.adjustNtpTimestamp(asset.subtitleReferenceTimeNtp);
      }
      if (timestampAdjuster.hasNtpAnchor()) {
        return timestampAdjuster.adjustShortNtpTimestamp(
            assembledTransmissionTimestamp, timestampAdjuster.getNtpAnchor());
      }
      return getFallbackSampleTimeUs(/* accessUnitIndex= */ 0);
    }

    private long getFallbackSampleTimeUs(int accessUnitIndex) {
      boolean newSample =
          !hasFallbackSample
              || assembledMpuSequenceNumber != lastFallbackMpuSequenceNumber
              || accessUnitIndex != lastFallbackAccessUnitIndex;
      if (newSample) {
        if (hasFallbackSample) {
          fallbackSampleTimeUs += getFallbackSampleDurationUs();
        }
        lastFallbackMpuSequenceNumber = assembledMpuSequenceNumber;
        lastFallbackAccessUnitIndex = accessUnitIndex;
        hasFallbackSample = true;
      }
      return fallbackSampleTimeUs;
    }

    private long getFallbackSampleDurationUs() {
      if (asset.audioSampleRate > 0) {
        return 1024L * C.MICROS_PER_SECOND / asset.audioSampleRate;
      }
      if (asset.videoFrameRateNumerator > 0 && asset.videoFrameRateDenominator > 0) {
        return C.MICROS_PER_SECOND
            * asset.videoFrameRateDenominator
            / asset.videoFrameRateNumerator;
      }
      return asset.assetType == MmtSignalingParser.ASSET_TYPE_MP4A
          ? DEFAULT_AUDIO_SAMPLE_DURATION_US
          : DEFAULT_VIDEO_SAMPLE_DURATION_US;
    }

    private int getNextAudioAccessUnitIndex() {
      resetAccessUnitIndexesIfNeeded();
      return nextAudioAccessUnitIndex++;
    }

    private int updateVideoAccessUnitIndex() {
      int position;
      int nalLength;
      byte[] data = mfuBuffer.getData();
      int limit = mfuBuffer.limit();
      for (position = 0; position < limit; position += 4 + nalLength) {
        if (position > limit - 4) {
          return C.INDEX_UNSET;
        }
        nalLength = TrackState.readNalLength(data, position);
        if (nalLength <= 0 || nalLength > limit - position - 4) {
          return C.INDEX_UNSET;
        }
      }
      resetAccessUnitIndexesIfNeeded();
      boolean accessUnitStartedInMfu = false;
      position = 0;
      while (position < limit) {
        boolean firstSlice;
        int nalUnitType;
        int nalLength2 = TrackState.readNalLength(data, position);
        int nalOffset = position + 4;
        int nalLimit = nalOffset + nalLength2;
        if (asset.assetType == MmtSignalingParser.ASSET_TYPE_HEV1
            || asset.assetType == MmtSignalingParser.ASSET_TYPE_HVC1) {
          nalUnitType = data[nalOffset] >> 1 & 0x3F;
          firstSlice = nalUnitType <= 31 && nalLength2 >= 3 && (data[nalOffset + 2] & 0x80) != 0;
          if (nalUnitType == 35 && !accessUnitStartedInMfu) {
            currentVideoAccessUnitIndex++;
            accessUnitStartedInMfu = true;
            videoAccessUnitStartedByAud = true;
          } else if (firstSlice) {
            if (!accessUnitStartedInMfu && !videoAccessUnitStartedByAud) {
              currentVideoAccessUnitIndex++;
            }
            accessUnitStartedInMfu = true;
            videoAccessUnitStartedByAud = false;
          }
        } else {
          nalUnitType = data[nalOffset] & 0x1F;
          firstSlice = TrackState.isFirstH264Slice(data, nalOffset, nalLimit, nalUnitType);
          if (nalUnitType == 9 && !accessUnitStartedInMfu) {
            currentVideoAccessUnitIndex++;
            accessUnitStartedInMfu = true;
            videoAccessUnitStartedByAud = true;
          } else if (firstSlice) {
            if (!accessUnitStartedInMfu && !videoAccessUnitStartedByAud) {
              currentVideoAccessUnitIndex++;
            }
            accessUnitStartedInMfu = true;
            videoAccessUnitStartedByAud = false;
          }
        }
        position = nalLimit;
      }
      return max(currentVideoAccessUnitIndex, 0);
    }

    private static boolean isFirstH264Slice(
        byte[] data, int nalOffset, int nalLimit, int nalUnitType) {
      if (nalUnitType < 1 || nalUnitType > 5 || nalLimit <= nalOffset + 1) {
        return false;
      }
      ParsableNalUnitBitArray bitArray = new ParsableNalUnitBitArray(data, nalOffset, nalLimit);
      bitArray.skipBits(8);
      return bitArray.canReadExpGolombCodedNum() && bitArray.readUnsignedExpGolombCodedInt() == 0;
    }

    private static int readNalLength(byte[] data, int position) {
      return (data[position] & 0xFF) << 24
          | (data[position + 1] & 0xFF) << 16
          | (data[position + 2] & 0xFF) << 8
          | data[position + 3] & 0xFF;
    }

    private void resetAccessUnitIndexesIfNeeded() {
      if (!hasTimingMpuSequenceNumber || timingMpuSequenceNumber != assembledMpuSequenceNumber) {
        hasTimingMpuSequenceNumber = true;
        timingMpuSequenceNumber = assembledMpuSequenceNumber;
        nextAudioAccessUnitIndex = 0;
        currentVideoAccessUnitIndex = -1;
        videoAccessUnitStartedByAud = false;
      }
    }

    private static boolean convertLengthPrefixedNalUnitsToAnnexB(ParsableByteArray data) {
      int nalLength;
      byte[] bytes = data.getData();
      int limit = data.limit();
      for (int position = 0; position < limit; position += 4 + nalLength) {
        if (position > limit - 4) {
          return false;
        }
        nalLength = TrackState.readNalLength(bytes, position);
        if (nalLength <= 0 || nalLength > limit - position - 4) {
          return false;
        }
        bytes[position] = 0;
        bytes[position + 1] = 0;
        bytes[position + 2] = 0;
        bytes[position + 3] = 1;
      }
      data.setPosition(0);
      return true;
    }
  }
}
