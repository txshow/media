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
package androidx.media3.extractor.ts;

import android.util.SparseArray;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;
import java.util.Arrays;

@UnstableApi
public final class DvdPrivateStreamReader implements ElementaryStreamReader {

  private static final int AC3_MIN = 0x80;
  private static final int AC3_MAX = 0x87;
  private static final int DTS_MIN = 0x88;
  private static final int DTS_MAX = 0x8F;
  private static final int DTS_EXT_MIN = 0x98;
  private static final int DTS_EXT_MAX = 0x9F;
  private static final int LPCM_MIN = 0xA0;
  private static final int LPCM_MAX = 0xAF;
  private static final int SUB_MIN = 0x20;
  private static final int SUB_MAX = 0x3F;
  private static final int RAW_AC3_SUB_ID = 0x0B;
  private static final int AC3_SYNC_SECOND_BYTE = 0x77;
  private static final int LPCM_DYNAMIC_RANGE_OFF = 0x80;
  private static final int DVD_AUDIO_HEADER_MAX_SUB_ID = 0xCF;
  private static final int SUBP_STREAM_COUNT = SUB_MAX - SUB_MIN + 1;

  private final SparseArray<ElementaryStreamReader> subReaders = new SparseArray<>();
  private final String[] audioLanguages;
  private final String[] subpLanguages;
  private final int[] activeAudioStreams;
  private final int[] activeSubpStreams;
  private final int[] audioStreamIndexBySubStreamIndex;
  private final int[] subpictureStreamIndexBySubStreamIndex;
  private final boolean hasActiveAudioStreams;
  private final boolean hasActiveSubpictureStreams;
  @Nullable private final byte[] vobsubIdxBytes;

  @Nullable private ExtractorOutput extractorOutput;
  private int nextTrackId;
  private long timeUs;

  public DvdPrivateStreamReader(
      String[] audioLanguages,
      String[] subpLanguages,
      @Nullable byte[] vobsubIdxBytes,
      int[] activeAudioStreams,
      int[] activeSubpStreams) {
    this.audioLanguages = audioLanguages;
    this.subpLanguages = subpLanguages;
    this.vobsubIdxBytes = vobsubIdxBytes;
    this.activeAudioStreams = activeAudioStreams;
    this.activeSubpStreams = activeSubpStreams;
    audioStreamIndexBySubStreamIndex =
        buildStreamIndexBySubStreamIndex(activeAudioStreams, LPCM_MAX - LPCM_MIN + 1);
    subpictureStreamIndexBySubStreamIndex =
        buildStreamIndexBySubStreamIndex(activeSubpStreams, SUBP_STREAM_COUNT);
    hasActiveAudioStreams = hasActiveStreams(activeAudioStreams);
    hasActiveSubpictureStreams = hasActiveStreams(activeSubpStreams);
    timeUs = C.TIME_UNSET;
  }

  public DvdPrivateStreamReader(@Nullable String language) {
    this(
        defaultLanguageArray(language, 8),
        defaultLanguageArray(language, 32),
        null,
        filledIntArray(8, -1),
        filledIntArray(32, -1));
  }

  private static String[] defaultLanguageArray(@Nullable String lang, int size) {
    String[] arr = new String[size];
    Arrays.fill(arr, lang != null ? lang : "");
    return arr;
  }

  private static int[] filledIntArray(int size, int value) {
    int[] arr = new int[size];
    Arrays.fill(arr, value);
    return arr;
  }

  @Nullable
  private static String languageOrNull(String[] languages, int idx) {
    if (idx < 0 || idx >= languages.length) {
      return null;
    }
    String lang = languages[idx];
    return lang.isEmpty() ? null : lang;
  }

  private static int[] buildStreamIndexBySubStreamIndex(int[] activeStreams, int subStreamCount) {
    int[] streamIndexes = filledIntArray(subStreamCount, -1);
    for (int streamIndex = 0; streamIndex < activeStreams.length; streamIndex++) {
      int subStreamIndex = activeStreams[streamIndex];
      if (subStreamIndex >= 0
          && subStreamIndex < subStreamCount
          && streamIndexes[subStreamIndex] == -1) {
        streamIndexes[subStreamIndex] = streamIndex;
      }
    }
    return streamIndexes;
  }

  private static boolean hasActiveStreams(int[] activeStreams) {
    for (int streamIndex : activeStreams) {
      if (streamIndex >= 0) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void seek() {
    timeUs = C.TIME_UNSET;
    for (int i = 0; i < subReaders.size(); i++) {
      subReaders.valueAt(i).seek();
    }
  }

  @Override
  public void endOfInputReached() {
    for (int i = 0; i < subReaders.size(); i++) {
      subReaders.valueAt(i).endOfInputReached();
    }
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    subReaders.clear();
    this.extractorOutput = extractorOutput;
    idGenerator.generateNewId();
    this.nextTrackId = idGenerator.getTrackId();
    createKnownSubpictureTracks();
  }

  private void createKnownSubpictureTracks() {
    for (int subStreamIndex : activeSubpStreams) {
      if (subStreamIndex < 0 || subStreamIndex >= SUBP_STREAM_COUNT) {
        continue;
      }
      maybeCreateReader(SUB_MIN + subStreamIndex);
    }
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    timeUs = pesTimeUs;
  }

  @Override
  public void consume(ParsableByteArray data) throws ParserException {
    if (extractorOutput == null || timeUs == C.TIME_UNSET) {
      return;
    }
    if (data.bytesLeft() < 1) {
      return;
    }
    int subStreamId = data.getData()[data.getPosition()] & 0xFF;
    boolean isRawAc3 =
        subStreamId == RAW_AC3_SUB_ID
            && data.bytesLeft() >= 2
            && (data.getData()[data.getPosition() + 1] & 0xFF) == AC3_SYNC_SECOND_BYTE;
    if (isRawAc3) {
      subStreamId = AC3_MIN;
    } else {
      data.skipBytes(1);
      if (subStreamId >= AC3_MIN && subStreamId <= DVD_AUDIO_HEADER_MAX_SUB_ID) {
        if (data.bytesLeft() < 3) {
          return;
        }
        data.skipBytes(3);
      }
    }
    if (isLpcmStreamId(subStreamId) && !isDvdLpcmPacket(data)) {
      return;
    }
    if (!maybeCreateReader(subStreamId)) {
      return;
    }
    int readerIndex = subReaders.indexOfKey(subStreamId);
    ElementaryStreamReader activeReader = subReaders.valueAt(readerIndex);
    activeReader.packetStarted(timeUs, TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR);
    activeReader.consume(data);
    activeReader.packetFinished();
  }

  @Nullable
  private ElementaryStreamReader createReaderForSubStream(int subStreamId) {
    if (subStreamId >= AC3_MIN && subStreamId <= AC3_MAX) {
      int idx = subStreamId - AC3_MIN;
      return isAudioStreamExcluded(idx)
          ? null
          : new Ac3Reader(audioLanguageForSubStreamIndex(idx), 0, MimeTypes.VIDEO_PS);
    }
    if (isDtsStreamId(subStreamId)) {
      int idx = subStreamId >= DTS_EXT_MIN ? subStreamId - DTS_EXT_MIN : subStreamId - DTS_MIN;
      return isAudioStreamExcluded(idx)
          ? null
          : new DtsReader(
              audioLanguageForSubStreamIndex(idx),
              0,
              DtsReader.EXTSS_HEADER_SIZE_MAX,
              MimeTypes.VIDEO_PS);
    }
    if (isLpcmStreamId(subStreamId)) {
      int idx = subStreamId - LPCM_MIN;
      return isAudioStreamExcluded(idx)
          ? null
          : new DvdLpcmReader(audioLanguageForSubStreamIndex(idx));
    }
    if (subStreamId >= SUB_MIN && subStreamId <= SUB_MAX) {
      int idx = subStreamId - SUB_MIN;
      return isSubpictureStreamExcluded(idx)
          ? null
          : new DvdSubtitleReader(subpictureLanguageForSubStreamIndex(idx), vobsubIdxBytes);
    }
    return null;
  }

  private boolean maybeCreateReader(int subStreamId) {
    if (subReaders.indexOfKey(subStreamId) >= 0) {
      return true;
    }
    if (extractorOutput == null) {
      return false;
    }
    ElementaryStreamReader reader = createReaderForSubStream(subStreamId);
    if (reader == null) {
      return false;
    }
    subReaders.put(subStreamId, reader);
    TrackIdGenerator idGenerator = new TrackIdGenerator(nextTrackId, 1);
    nextTrackId++;
    reader.createTracks(extractorOutput, idGenerator);
    return true;
  }

  private @Nullable String audioLanguageForSubStreamIndex(int subStreamIndex) {
    int streamIndex =
        streamIndexForSubStreamIndex(audioStreamIndexBySubStreamIndex, subStreamIndex);
    return languageOrNull(audioLanguages, streamIndex >= 0 ? streamIndex : subStreamIndex);
  }

  private @Nullable String subpictureLanguageForSubStreamIndex(int subStreamIndex) {
    int streamIndex =
        streamIndexForSubStreamIndex(subpictureStreamIndexBySubStreamIndex, subStreamIndex);
    return languageOrNull(subpLanguages, streamIndex >= 0 ? streamIndex : subStreamIndex);
  }

  private boolean isAudioStreamExcluded(int subStreamIndex) {
    return hasActiveAudioStreams
        && streamIndexForSubStreamIndex(audioStreamIndexBySubStreamIndex, subStreamIndex) < 0;
  }

  private boolean isSubpictureStreamExcluded(int subStreamIndex) {
    return hasActiveSubpictureStreams
        && streamIndexForSubStreamIndex(subpictureStreamIndexBySubStreamIndex, subStreamIndex) < 0;
  }

  private static int streamIndexForSubStreamIndex(
      int[] streamIndexBySubStreamIndex, int subStreamIndex) {
    if (subStreamIndex >= 0 && subStreamIndex < streamIndexBySubStreamIndex.length) {
      return streamIndexBySubStreamIndex[subStreamIndex];
    }
    return -1;
  }

  private static boolean isDtsStreamId(int subStreamId) {
    return (subStreamId >= DTS_MIN && subStreamId <= DTS_MAX)
        || (subStreamId >= DTS_EXT_MIN && subStreamId <= DTS_EXT_MAX);
  }

  private static boolean isLpcmStreamId(int subStreamId) {
    return subStreamId >= LPCM_MIN && subStreamId <= LPCM_MAX;
  }

  private static boolean isDvdLpcmPacket(ParsableByteArray data) {
    return data.bytesLeft() >= 3
        && (data.getData()[data.getPosition() + 2] & 0xFF) == LPCM_DYNAMIC_RANGE_OFF;
  }
}
