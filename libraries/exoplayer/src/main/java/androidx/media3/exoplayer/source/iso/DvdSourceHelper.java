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
package androidx.media3.exoplayer.source.iso;

import android.net.Uri;
import androidx.media3.common.CacheDataReader;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.IsoDataSource;
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.IndexSeekMap;
import androidx.media3.extractor.iso.IsoConstants;
import androidx.media3.extractor.iso.dvd.DvdCell;
import androidx.media3.extractor.iso.dvd.DvdIfoParser;
import androidx.media3.extractor.iso.dvd.DvdStructure;
import androidx.media3.extractor.iso.dvd.DvdTitle;
import androidx.media3.extractor.iso.udf.UdfFileSystem;
import androidx.media3.extractor.ts.DvdPrivateStreamReader;
import androidx.media3.extractor.ts.PsExtractor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class DvdSourceHelper {

  private static final long MIN_PROMISING_CELL_DURATION_US = 1_000_000;

  static DvdStructure parseStructure(CacheDataReader isoReader, UdfFileSystem udf)
      throws IOException {
    return new DvdIfoParser(isoReader, udf).parse();
  }

  static MediaSource buildSource(
      MediaItem mediaItem,
      DataSource.Factory dataSourceFactory,
      Uri isoUri,
      int editionIndex,
      DvdStructure dvd,
      LoadErrorHandlingPolicy loadErrorHandlingPolicy)
      throws IOException {
    DvdTitle main;
    if (editionIndex >= 0 && editionIndex < dvd.titles.size()) {
      main = dvd.titles.get(editionIndex);
    } else {
      main = dvd.mainTitle;
    }
    byte[] vobsubIdxBytes = main.vobsubIdx != null ? Util.getUtf8Bytes(main.vobsubIdx) : null;
    List<CellGroup> cellGroups = buildCellGroups(main.cells);
    List<IndexSeekMap> cellGroupSeekMaps = buildCellGroupSeekMaps(cellGroups, main.vobuSectors);
    ConcatenatingMediaSource2.Builder builder = new ConcatenatingMediaSource2.Builder();
    builder.setMediaItem(mediaItem);
    for (int groupIndex = 0; groupIndex < cellGroups.size(); groupIndex++) {
      CellGroup cellGroup = cellGroups.get(groupIndex);
      long durationMs = cellGroup.durationUs / 1000;
      final IndexSeekMap seekMap = cellGroupSeekMaps.get(groupIndex);
      IsoDataSource.Factory clipFactory =
          new IsoDataSource.Factory(
              dataSourceFactory,
              cellGroup.extentOffsets,
              cellGroup.extentLengths,
              /* clipLogicalByteOffset= */ 0,
              cellGroup.length,
              /* stripM2tsHeaders= */ false,
              /* stripSacdHeaders= */ false);
      ProgressiveMediaSource.Factory psFactory =
          new ProgressiveMediaSource.Factory(
              clipFactory,
              () ->
                  new Extractor[] {
                    new PsExtractor(
                        new TimestampAdjuster(0),
                        new DvdPrivateStreamReader(
                            main.audioLanguages,
                            main.subpLanguages,
                            vobsubIdxBytes,
                            main.activeAudioStreams,
                            main.activeSubpStreams),
                        seekMap)
                  });
      psFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy);
      MediaItem cellItem =
          new MediaItem.Builder()
              .setUri(isoUri)
              .setCustomCacheKey(
                  IsoUtil.childCacheKey(mediaItem, "dvd:" + editionIndex + ":" + groupIndex))
              .build();
      builder.add(psFactory.createMediaSource(cellItem), durationMs);
    }
    if (cellGroups.isEmpty()) {
      throw new IOException("DVD: no playable cells found");
    }
    return builder.build();
  }

  static List<CellGroup> buildCellGroups(List<DvdCell> cells) {
    List<CellGroup> result = new ArrayList<>();
    List<DvdCell> currentGroup = new ArrayList<>();
    boolean currentGroupHasPromisingCell = false;
    for (DvdCell cell : cells) {
      if (cell.durationUs <= 0) {
        continue;
      }
      boolean isPromisingCell = cell.durationUs >= MIN_PROMISING_CELL_DURATION_US;
      if (isPromisingCell && cell.stcDiscontinuity && currentGroupHasPromisingCell) {
        result.add(new CellGroup(currentGroup));
        currentGroup = new ArrayList<>();
        currentGroupHasPromisingCell = false;
      }
      currentGroup.add(cell);
      currentGroupHasPromisingCell |= isPromisingCell;
    }
    if (currentGroupHasPromisingCell) {
      result.add(new CellGroup(currentGroup));
    }
    return result;
  }

  private static List<IndexSeekMap> buildCellGroupSeekMaps(
      List<CellGroup> cellGroups, long[] vobuSectors) {
    List<IndexSeekMap> result = new ArrayList<>(cellGroups.size());
    for (CellGroup cellGroup : cellGroups) {
      List<Long> positions = new ArrayList<>();
      List<Long> timesUs = new ArrayList<>();
      long groupByteOffset = 0;
      long groupTimeUs = 0;
      for (DvdCell cell : cellGroup.cells) {
        if (cell.lastSector > cell.firstSector && cell.durationUs > 0) {
          long sectorSpan = cell.lastSector - cell.firstSector + 1;
          int endIdx = binarySearchFirstGe(vobuSectors, cell.firstSector);
          while (endIdx < vobuSectors.length && vobuSectors[endIdx] <= cell.lastSector) {
            long relativeSector = vobuSectors[endIdx] - cell.firstSector;
            positions.add(groupByteOffset + relativeSector * IsoConstants.SECTOR_SIZE);
            timesUs.add(groupTimeUs + relativeSector * cell.durationUs / sectorSpan);
            endIdx++;
          }
        }
        groupByteOffset += cell.length;
        groupTimeUs += cell.durationUs;
      }
      if (positions.isEmpty()) {
        result.add(null);
        continue;
      }
      long[] positionArray = new long[positions.size()];
      long[] timeArray = new long[timesUs.size()];
      for (int i = 0; i < positions.size(); i++) {
        positionArray[i] = positions.get(i);
        timeArray[i] = timesUs.get(i);
      }
      result.add(new IndexSeekMap(positionArray, timeArray, cellGroup.durationUs));
    }
    return result;
  }

  private static int binarySearchFirstGe(long[] arr, long target) {
    int lo = 0, hi = arr.length;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (arr[mid] < target) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }

  static final class CellGroup {
    final List<DvdCell> cells;
    final long[] extentOffsets;
    final long[] extentLengths;
    final long length;
    final long durationUs;

    CellGroup(List<DvdCell> cells) {
      this.cells = new ArrayList<>(cells);
      int extentCount = 0;
      long length = 0;
      long durationUs = 0;
      for (DvdCell cell : cells) {
        extentCount += cell.extentOffsets.length;
        length += cell.length;
        durationUs += cell.durationUs;
      }
      extentOffsets = new long[extentCount];
      extentLengths = new long[extentCount];
      int extentIndex = 0;
      for (DvdCell cell : cells) {
        System.arraycopy(
            cell.extentOffsets, 0, extentOffsets, extentIndex, cell.extentOffsets.length);
        System.arraycopy(
            cell.extentLengths, 0, extentLengths, extentIndex, cell.extentLengths.length);
        extentIndex += cell.extentOffsets.length;
      }
      this.length = length;
      this.durationUs = durationUs;
    }
  }
}
