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
package androidx.media3.extractor.iso.dvd;

import androidx.media3.common.CacheDataReader;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.iso.IsoConstants;
import androidx.media3.extractor.iso.IsoFileEntry;
import androidx.media3.extractor.iso.udf.UdfFileSystem;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DvdIfoParser {

  private static final int SECTOR_SIZE = IsoConstants.SECTOR_SIZE;
  private static final int MAX_VOB_PARTS = 9;

  private static final int YUV_SCALE = 10000;
  private static final int YUV_CR_TO_R = 14075;
  private static final int YUV_CB_TO_G = 3455;
  private static final int YUV_CR_TO_G = 7169;
  private static final int YUV_CB_TO_B = 17790;

  private static final int VMGI_TT_SRPT_SECTOR_OFFSET = 0x00C4;

  private static final int TT_SRPT_HEADER_SIZE = 8;
  private static final int TT_SRPT_ENTRY_SIZE = 12;

  private static final int VTSI_PTT_SRPT_SECTOR_OFFSET = 0x00C8;
  private static final int VTSI_PGCIT_SECTOR_OFFSET = 0x00CC;
  private static final int VTSI_VIDEO_ATTR_0_OFFSET = 0x0200;
  private static final int VTSI_VIDEO_ATTR_1_OFFSET = 0x0201;
  private static final int VTSI_NUM_AUDIO_OFFSET = 0x0203;
  private static final int VTSI_AUDIO_ATTR_OFFSET = 0x0204;
  private static final int VTSI_AUDIO_ATTR_SIZE = 8;
  private static final int VTSI_NUM_SUBP_OFFSET = 0x0255;
  private static final int VTSI_SUBP_ATTR_OFFSET = 0x0256;
  private static final int VTSI_SUBP_ATTR_SIZE = 6;
  private static final int VTSI_ADMAP_SECTOR_OFFSET = 0x00E4;
  private static final int VTSI_MIN_SIZE_FOR_ADMAP = 0x00E8;

  private static final int MAX_AUDIO_STREAMS = 8;
  private static final int MAX_SUBP_STREAMS = 32;

  private static final int PGCIT_HEADER_SIZE = 8;
  private static final int TABLE_LAST_BYTE_OFFSET = 4;
  private static final int PGCIT_SRP_SIZE = 8;
  private static final int PGCIT_SRP_DATA_OFFSET = 4;

  private static final int PGC_READ_SECTORS = 4;
  private static final int PGC_NR_PROGRAMS_OFFSET = 2;
  private static final int PGC_NR_CELLS_OFFSET = 3;
  private static final int PGC_PROGRAM_MAP_OFFSET = 0xE6;
  private static final int PGC_CELL_PLAYBACK_OFFSET = 232;
  private static final int PGC_PALETTE_OFFSET = 0xA4;
  private static final int PGC_AUDIO_CTRL_OFFSET = 0x0C;
  private static final int PGC_SUBP_CTRL_OFFSET = 0x1C;

  private static final int CELL_PLAYBACK_ENTRY_SIZE = 24;
  private static final int CELL_DURATION_OFFSET = 4;
  private static final int CELL_FIRST_SECTOR_OFFSET = 8;
  private static final int CELL_LAST_SECTOR_OFFSET = 20;
  private static final int CELL_STC_DISCONTINUITY_FLAG = 0x0200;

  private static final int PTT_SRPT_HEADER_SIZE = 8;
  private static final int PTT_SRPT_ENTRY_SIZE = 4;
  private static final int MAX_IFO_TABLE_BYTES = 8 * 1024 * 1024;

  private static final int VOBU_ADMAP_HEADER_SIZE = 4;
  private static final int VOBU_ADMAP_ENTRY_SIZE = 4;

  private static final int PREFETCH_MAX_THREADS = 8;

  private final UdfFileSystem udf;
  private final CacheDataReader reader;
  private final HashMap<String, IsoFileEntry> ifoCache = new HashMap<>();

  public DvdIfoParser(CacheDataReader reader, UdfFileSystem udf) {
    this.reader = reader;
    this.udf = udf;
  }

  static long[][] vobSectorRangeToExtents(long[][] vobParts, long firstSector, long lastSector) {
    List<long[]> extents = new ArrayList<>();
    long sector = firstSector;
    for (long[] part : vobParts) {
      long partStart = part[0];
      long partByteOffset = part[1];
      long partSectors = part[2];
      long partEnd = partStart + partSectors;
      if (sector >= partStart && sector < partEnd) {
        long extentEndSector = Math.min(lastSector + 1, partEnd);
        extents.add(
            new long[] {
              partByteOffset == IsoFileEntry.UNRECORDED_EXTENT_OFFSET
                  ? IsoFileEntry.UNRECORDED_EXTENT_OFFSET
                  : partByteOffset + (sector - partStart) * SECTOR_SIZE,
              (extentEndSector - sector) * SECTOR_SIZE
            });
        sector = extentEndSector;
        if (sector > lastSector) {
          break;
        }
      }
    }
    if (sector <= lastSector) {
      return new long[0][];
    }
    return extents.toArray(new long[0][]);
  }

  private static int parseVtsNum(String ifoName) {
    try {
      int slash = ifoName.lastIndexOf('/');
      String base = slash >= 0 ? ifoName.substring(slash + 1) : ifoName;
      return Integer.parseInt(base.substring(4, 6));
    } catch (Exception e) {
      return 0;
    }
  }

  static long[] buildChapterTimes(
      ByteBuffer pgcBB,
      byte[] pgcData,
      long[] rawCellStartTimesUs,
      long[][] pttEntries,
      long pgcNumber) {
    int nrPrograms = pgcBB.get(PGC_NR_PROGRAMS_OFFSET) & 0xFF;
    int programMapOffset = pgcBB.getShort(PGC_PROGRAM_MAP_OFFSET) & 0xFFFF;
    if (nrPrograms == 0
        || programMapOffset == 0
        || programMapOffset + nrPrograms > pgcData.length) {
      return new long[0];
    }
    long[] chapterTimes = new long[pttEntries.length];
    int chapterCount = 0;
    for (long[] pttEntry : pttEntries) {
      if (pttEntry[0] != pgcNumber) {
        break;
      }
      int programIndex = (int) pttEntry[1] - 1;
      if (programIndex < 0 || programIndex >= nrPrograms) {
        continue;
      }
      int firstCell1Based = pgcData[programMapOffset + programIndex] & 0xFF;
      int firstCell0Based = firstCell1Based - 1;
      if (firstCell0Based >= 0 && firstCell0Based < rawCellStartTimesUs.length) {
        long chapterTimeUs = rawCellStartTimesUs[firstCell0Based];
        if (chapterCount == 0 || chapterTimeUs > chapterTimes[chapterCount - 1]) {
          chapterTimes[chapterCount++] = chapterTimeUs;
        }
      }
    }
    return Arrays.copyOf(chapterTimes, chapterCount);
  }

  private static long decodeDvdTime(byte[] data, int offset) {
    int hour = bcd(data[offset]);
    int min = bcd(data[offset + 1]);
    int sec = bcd(data[offset + 2]);
    int frameU = data[offset + 3] & 0xFF;
    int frameRate = (frameU >> 6) & 0x3;
    int frames = bcd((byte) (frameU & 0x3F));
    double fps;
    if (frameRate == 1) {
      fps = 25.0;
    } else if (frameRate == 3) {
      fps = 30000.0 / 1001.0;
    } else {
      fps = 0;
    }
    long us = ((long) hour * 3600 + (long) min * 60 + sec) * 1_000_000L;
    if (fps > 0) {
      us += (long) (frames * 1_000_000.0 / fps);
    }
    return us;
  }

  private static int bcd(byte b) {
    int v = b & 0xFF;
    return (v >> 4) * 10 + (v & 0xF);
  }

  private static String langCodeToString(byte[] data, int offset) {
    if (offset + 2 > data.length) {
      return "";
    }
    int hi = data[offset] & 0xFF;
    int lo = data[offset + 1] & 0xFF;
    boolean hiOk = (hi >= 0x41 && hi <= 0x5A) || (hi >= 0x61 && hi <= 0x7A);
    boolean loOk = (lo >= 0x41 && lo <= 0x5A) || (lo >= 0x61 && lo <= 0x7A);
    if (!hiOk || !loOk) {
      return "";
    }
    return new String(new char[] {(char) (hi | 0x20), (char) (lo | 0x20)});
  }

  private static int vobsubYcbcrToRgb(int y, int cr, int cb) {
    int r = y + YUV_CR_TO_R * (cr - 128) / YUV_SCALE;
    int g = y - YUV_CB_TO_G * (cb - 128) / YUV_SCALE - YUV_CR_TO_G * (cr - 128) / YUV_SCALE;
    int b = y + YUV_CB_TO_B * (cb - 128) / YUV_SCALE;
    r = Util.constrainValue(r, 0, 255);
    g = Util.constrainValue(g, 0, 255);
    b = Util.constrainValue(b, 0, 255);
    return (r << 16) | (g << 8) | b;
  }

  public DvdStructure parse() throws IOException {
    byte[] vmgi = readSectorsFromIfo("VIDEO_TS/VIDEO_TS.IFO", 0, 1);
    ByteBuffer bb = ByteBuffer.wrap(vmgi).order(ByteOrder.BIG_ENDIAN);
    long ttSrptSector = bb.getInt(VMGI_TT_SRPT_SECTOR_OFFSET) & 0xFFFFFFFFL;
    byte[] ttSrpt = readSectorsFromIfo("VIDEO_TS/VIDEO_TS.IFO", ttSrptSector, 1);
    ByteBuffer tsbb = ByteBuffer.wrap(ttSrpt).order(ByteOrder.BIG_ENDIAN);
    int numTitles = tsbb.getShort(0) & 0xFFFF;
    LinkedHashSet<String> ifoNames = new LinkedHashSet<>();
    for (int t = 0; t < numTitles; t++) {
      int offset = TT_SRPT_HEADER_SIZE + t * TT_SRPT_ENTRY_SIZE;
      if (offset + TT_SRPT_ENTRY_SIZE > ttSrpt.length) {
        break;
      }
      int vtsNum = tsbb.get(offset + 6) & 0xFF;
      ifoNames.add(String.format(Locale.US, "VIDEO_TS/VTS_%02d_0.IFO", vtsNum));
    }
    prefetchIfoSectors(ifoNames);
    List<DvdTitle> allTitles = new ArrayList<>();
    DvdTitle mainTitle = null;
    long maxDuration = -1;
    for (int t = 0; t < numTitles; t++) {
      int offset = TT_SRPT_HEADER_SIZE + t * TT_SRPT_ENTRY_SIZE;
      if (offset + TT_SRPT_ENTRY_SIZE > ttSrpt.length) {
        break;
      }
      int vtsNum = tsbb.get(offset + 6) & 0xFF;
      int vtsTtn = tsbb.get(offset + 7) & 0xFF;
      String ifoName = String.format(Locale.US, "VIDEO_TS/VTS_%02d_0.IFO", vtsNum);
      try {
        DvdTitle title = parseVtsTitle(ifoName, vtsTtn);
        allTitles.add(title);
        long dur = title.getTotalDurationUs();
        if (dur > maxDuration) {
          maxDuration = dur;
          mainTitle = title;
        }
      } catch (IOException e) {
        String[] emptyAudio = new String[MAX_AUDIO_STREAMS];
        String[] emptySubp = new String[MAX_SUBP_STREAMS];
        Arrays.fill(emptyAudio, "");
        Arrays.fill(emptySubp, "");
        int[] defaultActiveAudio = new int[MAX_AUDIO_STREAMS];
        int[] defaultActiveSubp = new int[MAX_SUBP_STREAMS];
        Arrays.fill(defaultActiveAudio, -1);
        Arrays.fill(defaultActiveSubp, -1);
        allTitles.add(
            new DvdTitle(
                new ArrayList<>(),
                emptyAudio,
                emptySubp,
                null,
                defaultActiveAudio,
                defaultActiveSubp,
                new long[0],
                new long[0]));
      }
    }
    if (mainTitle == null && !allTitles.isEmpty()) {
      mainTitle = allTitles.get(0);
    }
    if (mainTitle == null) {
      throw new IOException("DVD: no playable titles found");
    }
    return new DvdStructure(allTitles, mainTitle);
  }

  private DvdTitle parseVtsTitle(String ifoName, int vtsTtn) throws IOException {
    byte[] vtsi = readSectorsFromIfo(ifoName, 0, 1);
    ByteBuffer bb = ByteBuffer.wrap(vtsi).order(ByteOrder.BIG_ENDIAN);
    long pgcitSector = bb.getInt(VTSI_PGCIT_SECTOR_OFFSET) & 0xFFFFFFFFL;
    long pttSrptSector = bb.getInt(VTSI_PTT_SRPT_SECTOR_OFFSET) & 0xFFFFFFFFL;
    int vtsNum = parseVtsNum(ifoName);
    long[][] vobParts = buildVobPartMap(vtsNum);
    long[][] pttEntries = getPgcAndProgramsForTtn(ifoName, pttSrptSector, vtsTtn);
    long pgcNumber = pttEntries[0][0];
    int startProgram = (int) pttEntries[0][1];
    byte[] pgcitData = readIfoTable(ifoName, pgcitSector, "PGCIT");
    ByteBuffer pgcitBB = ByteBuffer.wrap(pgcitData).order(ByteOrder.BIG_ENDIAN);
    int numPgcs = pgcitBB.getShort(0) & 0xFFFF;
    int pgcIndex = (int) (pgcNumber - 1);
    if (pgcIndex < 0 || pgcIndex >= numPgcs) {
      pgcIndex = 0;
    }
    int pgcSrpOffset = PGCIT_HEADER_SIZE + pgcIndex * PGCIT_SRP_SIZE;
    if (pgcSrpOffset + PGCIT_SRP_SIZE > pgcitData.length) {
      throw new IOException("DVD: PGCIT entry out of range");
    }
    int pgcStartByte = (int) (pgcitBB.getInt(pgcSrpOffset + PGCIT_SRP_DATA_OFFSET) & 0xFFFFFFFFL);
    if (pgcStartByte < 0 || pgcStartByte >= pgcitData.length) {
      throw new IOException("DVD: PGC offset out of range");
    }
    byte[] pgcData = new byte[PGC_READ_SECTORS * SECTOR_SIZE];
    System.arraycopy(
        pgcitData,
        pgcStartByte,
        pgcData,
        0,
        Math.min(PGC_READ_SECTORS * SECTOR_SIZE, pgcitData.length - pgcStartByte));
    ByteBuffer pgcBB = ByteBuffer.wrap(pgcData).order(ByteOrder.BIG_ENDIAN);
    int nrCells = pgcBB.get(PGC_NR_CELLS_OFFSET) & 0xFF;
    int nrPrograms = pgcBB.get(PGC_NR_PROGRAMS_OFFSET) & 0xFF;
    startProgram = Util.constrainValue(startProgram, 1, Math.max(1, nrPrograms));
    int programMapOffset = pgcBB.getShort(PGC_PROGRAM_MAP_OFFSET) & 0xFFFF;
    int startCellIndex = 0;
    if (programMapOffset > 0
        && programMapOffset + startProgram <= pgcData.length
        && nrPrograms > 0) {
      startCellIndex = Math.max(0, (pgcData[programMapOffset + startProgram - 1] & 0xFF) - 1);
    }
    int cellPlaybackOffset = pgcBB.getShort(PGC_CELL_PLAYBACK_OFFSET) & 0xFFFF;
    List<DvdCell> cells = new ArrayList<>();
    long[] rawCellStartTimesUs = new long[nrCells + 1];
    long playbackDurationUs = 0;
    for (int c = 0; c < nrCells; c++) {
      rawCellStartTimesUs[c] = playbackDurationUs;
      int cpOffset = cellPlaybackOffset + c * CELL_PLAYBACK_ENTRY_SIZE;
      if (cpOffset + CELL_PLAYBACK_ENTRY_SIZE > pgcData.length) {
        break;
      }
      int flags = pgcBB.getShort(cpOffset) & 0xFFFF;
      int blockMode = (flags >> 14) & 0x3;
      int blockType = (flags >> 12) & 0x3;
      if (c < startCellIndex || (blockType == 1 && blockMode != 1)) {
        rawCellStartTimesUs[c + 1] = playbackDurationUs;
        continue;
      }
      long durationUs = decodeDvdTime(pgcData, cpOffset + CELL_DURATION_OFFSET);
      long firstSector = pgcBB.getInt(cpOffset + CELL_FIRST_SECTOR_OFFSET) & 0xFFFFFFFFL;
      long lastSector = pgcBB.getInt(cpOffset + CELL_LAST_SECTOR_OFFSET) & 0xFFFFFFFFL;
      if (lastSector < firstSector) {
        rawCellStartTimesUs[c + 1] = playbackDurationUs;
        continue;
      }
      long length = (lastSector - firstSector + 1) * SECTOR_SIZE;
      long[][] cellExtents = vobSectorRangeToExtents(vobParts, firstSector, lastSector);
      if (cellExtents.length == 0) {
        rawCellStartTimesUs[c + 1] = playbackDurationUs;
        continue;
      }
      long[] cellExtentOffsets = new long[cellExtents.length];
      long[] cellExtentLengths = new long[cellExtents.length];
      for (int i = 0; i < cellExtents.length; i++) {
        cellExtentOffsets[i] = cellExtents[i][0];
        cellExtentLengths[i] = cellExtents[i][1];
      }
      cells.add(
          new DvdCell(
              cellExtentOffsets,
              cellExtentLengths,
              length,
              durationUs,
              firstSector,
              lastSector,
              parseCellStcDiscontinuity(flags)));
      playbackDurationUs += durationUs;
      rawCellStartTimesUs[c + 1] = playbackDurationUs;
    }
    String[] audioLanguages = new String[MAX_AUDIO_STREAMS];
    int[] audioFormats = new int[MAX_AUDIO_STREAMS];
    int[] audioChannels = new int[MAX_AUDIO_STREAMS];
    String[] subpLanguages = new String[MAX_SUBP_STREAMS];
    int nAudio = vtsi.length > VTSI_NUM_AUDIO_OFFSET ? (vtsi[VTSI_NUM_AUDIO_OFFSET] & 0xFF) : 0;
    for (int i = 0; i < MAX_AUDIO_STREAMS; i++) {
      int attrBase = VTSI_AUDIO_ATTR_OFFSET + i * VTSI_AUDIO_ATTR_SIZE;
      if (i < nAudio && attrBase + 4 <= vtsi.length) {
        audioLanguages[i] = langCodeToString(vtsi, attrBase + 2);
        audioFormats[i] = (vtsi[attrBase] >> 5) & 0x7;
        audioChannels[i] = (vtsi[attrBase + 1] & 0x7) + 1;
      } else {
        audioLanguages[i] = "";
      }
    }
    int nSubp = vtsi.length > VTSI_NUM_SUBP_OFFSET ? (vtsi[VTSI_NUM_SUBP_OFFSET] & 0xFF) : 0;
    for (int i = 0; i < MAX_SUBP_STREAMS; i++) {
      int attrBase = VTSI_SUBP_ATTR_OFFSET + i * VTSI_SUBP_ATTR_SIZE;
      if (i < nSubp && attrBase + 4 <= vtsi.length) {
        subpLanguages[i] = langCodeToString(vtsi, attrBase + 2);
      } else {
        subpLanguages[i] = "";
      }
    }
    int videoAttr0 =
        vtsi.length > VTSI_VIDEO_ATTR_0_OFFSET ? (vtsi[VTSI_VIDEO_ATTR_0_OFFSET] & 0xFF) : 0;
    int videoAttr1 =
        vtsi.length > VTSI_VIDEO_ATTR_1_OFFSET ? (vtsi[VTSI_VIDEO_ATTR_1_OFFSET] & 0xFF) : 0;
    int videoFormat = (videoAttr0 >> 4) & 0x3;
    boolean isWidescreen = ((videoAttr0 >> 2) & 0x3) == 0x3;
    int pictureSize = (videoAttr1 >> 2) & 0x3;
    int videoWidth;
    int videoHeight;
    if (pictureSize == 0) {
      videoWidth = 720;
      videoHeight = (videoFormat == 1) ? 576 : 480;
    } else if (pictureSize == 1) {
      videoWidth = 704;
      videoHeight = (videoFormat == 1) ? 576 : 480;
    } else if (pictureSize == 2) {
      videoWidth = 352;
      videoHeight = (videoFormat == 1) ? 576 : 480;
    } else {
      videoWidth = 352;
      videoHeight = (videoFormat == 1) ? 288 : 240;
    }
    String vobsubIdx;
    StringBuilder sb = new StringBuilder();
    sb.append("size: ").append(videoWidth).append('x').append(videoHeight).append('\n');
    sb.append("palette:");
    for (int i = 0; i < 16; i++) {
      int yuvWord = pgcBB.getInt(PGC_PALETTE_OFFSET + i * 4);
      int y = (yuvWord >> 16) & 0xFF;
      int cr = (yuvWord >> 8) & 0xFF;
      int cb = yuvWord & 0xFF;
      int rgb = vobsubYcbcrToRgb(y, cr, cb);
      sb.append(i == 0 ? " " : ", ");
      sb.append(String.format("%06x", rgb));
    }
    sb.append('\n');
    vobsubIdx = sb.toString();
    int[] activeAudioStreams = new int[MAX_AUDIO_STREAMS];
    int[] activeSubpStreams = new int[MAX_SUBP_STREAMS];
    Arrays.fill(activeAudioStreams, -1);
    Arrays.fill(activeSubpStreams, -1);
    for (int i = 0; i < MAX_AUDIO_STREAMS; i++) {
      int ctrl = pgcBB.getShort(PGC_AUDIO_CTRL_OFFSET + i * 2) & 0xFFFF;
      activeAudioStreams[i] = parsePgcAudioStreamNumber(ctrl);
    }
    for (int i = 0; i < MAX_SUBP_STREAMS; i++) {
      int ctrl = pgcBB.getInt(PGC_SUBP_CTRL_OFFSET + i * 4);
      activeSubpStreams[i] = parsePgcSubpictureStreamNumber(ctrl, isWidescreen);
    }
    long[] vobuSectors = readVobuAdmap(ifoName, vtsi);
    long[] chapterTimesUs =
        buildChapterTimes(pgcBB, pgcData, rawCellStartTimesUs, pttEntries, pgcNumber);
    return new DvdTitle(
        cells,
        audioLanguages,
        subpLanguages,
        vobsubIdx,
        audioFormats,
        audioChannels,
        activeAudioStreams,
        activeSubpStreams,
        vobuSectors,
        chapterTimesUs);
  }

  private long[][] buildVobPartMap(int vtsNum) {
    List<long[]> parts = new ArrayList<>();
    long cumSectors = 0;
    for (int part = 1; part <= MAX_VOB_PARTS; part++) {
      String vobName = String.format(Locale.US, "VIDEO_TS/VTS_%02d_%d.VOB", vtsNum, part);
      try {
        IsoFileEntry entry = udf.findFile(vobName);
        if (entry == null || entry.length == 0) {
          break;
        }
        for (int i = 0; i < entry.extentOffsets.length; i++) {
          long partSectors = entry.extentLengths[i] / SECTOR_SIZE;
          if (partSectors == 0) {
            continue;
          }
          parts.add(new long[] {cumSectors, entry.extentOffsets[i], partSectors});
          cumSectors += partSectors;
        }
      } catch (IOException e) {
        break;
      }
    }
    return parts.toArray(new long[0][]);
  }

  private long[][] getPgcAndProgramsForTtn(String ifoName, long pttSrptSector, int vtsTtn) {
    try {
      byte[] data = readIfoTable(ifoName, pttSrptSector, "PTT_SRPT");
      return parsePgcAndProgramsForTtn(data, vtsTtn);
    } catch (IOException ignored) {
    }
    return new long[][] {{1, 1}};
  }

  static long[][] parsePgcAndProgramsForTtn(byte[] data, int vtsTtn) {
    if (data.length < PTT_SRPT_HEADER_SIZE) {
      return new long[][] {{1, 1}};
    }
    ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
    int numTtns = bb.getShort(0) & 0xFFFF;
    if (vtsTtn < 1 || vtsTtn > numTtns) {
      return new long[][] {{1, 1}};
    }
    long pointerTableEnd = PTT_SRPT_HEADER_SIZE + (long) numTtns * PTT_SRPT_ENTRY_SIZE;
    if (pointerTableEnd > data.length) {
      return new long[][] {{1, 1}};
    }
    int ptrOffset = PTT_SRPT_HEADER_SIZE + (vtsTtn - 1) * PTT_SRPT_ENTRY_SIZE;
    if (ptrOffset + PTT_SRPT_ENTRY_SIZE > data.length) {
      return new long[][] {{1, 1}};
    }
    long tableLength = (bb.getInt(TABLE_LAST_BYTE_OFFSET) & 0xFFFFFFFFL) + 1;
    if (tableLength < pointerTableEnd || tableLength > data.length) {
      return new long[][] {{1, 1}};
    }
    long entriesStart = bb.getInt(ptrOffset) & 0xFFFFFFFFL;
    long entriesEnd =
        vtsTtn < numTtns ? bb.getInt(ptrOffset + PTT_SRPT_ENTRY_SIZE) & 0xFFFFFFFFL : tableLength;
    if (entriesStart < pointerTableEnd
        || entriesEnd < entriesStart + PTT_SRPT_ENTRY_SIZE
        || entriesEnd > tableLength
        || (entriesEnd - entriesStart) % PTT_SRPT_ENTRY_SIZE != 0) {
      return new long[][] {{1, 1}};
    }
    int entryCount = (int) ((entriesEnd - entriesStart) / PTT_SRPT_ENTRY_SIZE);
    long[][] entries = new long[entryCount][2];
    int validEntryCount = 0;
    for (int i = 0; i < entryCount; i++) {
      int entryOffset = (int) entriesStart + i * PTT_SRPT_ENTRY_SIZE;
      int pgcNumber = bb.getShort(entryOffset) & 0xFFFF;
      int programNumber = bb.getShort(entryOffset + 2) & 0xFFFF;
      if (pgcNumber == 0 || programNumber == 0) {
        continue;
      }
      entries[validEntryCount++] = new long[] {pgcNumber, programNumber};
    }
    return validEntryCount > 0 ? Arrays.copyOf(entries, validEntryCount) : new long[][] {{1, 1}};
  }

  static int parsePgcAudioStreamNumber(int control) {
    return (control & 0x8000) != 0 ? (control >>> 8) & 0x07 : -1;
  }

  static boolean parseCellStcDiscontinuity(int flags) {
    return (flags & CELL_STC_DISCONTINUITY_FLAG) != 0;
  }

  static int parsePgcSubpictureStreamNumber(int control, boolean isWidescreen) {
    if ((control & 0x80000000) == 0) {
      return -1;
    }
    return isWidescreen ? (control >>> 16) & 0x1F : (control >>> 24) & 0x1F;
  }

  private byte[] readIfoTable(String ifoName, long sector, String tableName) throws IOException {
    byte[] header = readSectorsFromIfo(ifoName, sector, 1);
    if (header.length < PGCIT_HEADER_SIZE) {
      throw new IOException("DVD: short " + tableName + " header");
    }
    long tableLength =
        (ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).getInt(TABLE_LAST_BYTE_OFFSET)
                & 0xFFFFFFFFL)
            + 1;
    if (tableLength < PGCIT_HEADER_SIZE || tableLength > MAX_IFO_TABLE_BYTES) {
      throw new IOException("DVD: invalid " + tableName + " length: " + tableLength);
    }
    return readSectorsFromIfo(ifoName, sector, Util.ceilDivide((int) tableLength, SECTOR_SIZE));
  }

  private long[] readVobuAdmap(String ifoName, byte[] vtsi) {
    if (vtsi.length < VTSI_MIN_SIZE_FOR_ADMAP) {
      return new long[0];
    }
    ByteBuffer bb = ByteBuffer.wrap(vtsi).order(ByteOrder.BIG_ENDIAN);
    long admapSector = bb.getInt(VTSI_ADMAP_SECTOR_OFFSET) & 0xFFFFFFFFL;
    if (admapSector == 0) {
      return new long[0];
    }
    try {
      byte[] header = readSectorsFromIfo(ifoName, admapSector, 1);
      ByteBuffer hBB = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
      long lastByte = hBB.getInt(0) & 0xFFFFFFFFL;
      long tableLength = lastByte + 1;
      if (tableLength < VOBU_ADMAP_HEADER_SIZE
          || tableLength > MAX_IFO_TABLE_BYTES
          || (tableLength - VOBU_ADMAP_HEADER_SIZE) % VOBU_ADMAP_ENTRY_SIZE != 0) {
        return new long[0];
      }
      int entryCount = (int) ((tableLength - VOBU_ADMAP_HEADER_SIZE) / VOBU_ADMAP_ENTRY_SIZE);
      if (entryCount <= 0) {
        return new long[0];
      }
      int totalBytes = VOBU_ADMAP_HEADER_SIZE + entryCount * VOBU_ADMAP_ENTRY_SIZE;
      int numSectors = Util.ceilDivide(totalBytes, SECTOR_SIZE);
      byte[] admapData = readSectorsFromIfo(ifoName, admapSector, numSectors);
      ByteBuffer aBB = ByteBuffer.wrap(admapData).order(ByteOrder.BIG_ENDIAN);
      long[] sectors = new long[entryCount];
      for (int i = 0; i < entryCount; i++) {
        sectors[i] = aBB.getInt(VOBU_ADMAP_HEADER_SIZE + i * VOBU_ADMAP_ENTRY_SIZE) & 0xFFFFFFFFL;
      }
      return sectors;
    } catch (IOException e) {
      return new long[0];
    }
  }

  private void prefetchIfoSectors(Set<String> ifoNames) throws IOException {
    List<Long> offsets = new ArrayList<>();
    for (String ifoPath : ifoNames) {
      IsoFileEntry entry = udf.findFile(ifoPath);
      if (entry != null) {
        ifoCache.put(ifoPath, entry);
        for (long extentOffset : entry.extentOffsets) {
          if (extentOffset != IsoFileEntry.UNRECORDED_EXTENT_OFFSET) {
            offsets.add(extentOffset);
          }
        }
      }
    }
    if (offsets.isEmpty()) {
      return;
    }
    ExecutorService exec =
        Executors.newFixedThreadPool(Math.min(offsets.size(), PREFETCH_MAX_THREADS));
    try {
      CountDownLatch latch = new CountDownLatch(offsets.size());
      for (long offset : offsets) {
        exec.execute(
            () -> {
              try {
                reader.prefetch(offset);
              } finally {
                latch.countDown();
              }
            });
      }
      try {
        latch.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    } finally {
      exec.shutdownNow();
    }
  }

  private byte[] readSectorsFromIfo(String ifoPath, long sectorOffset, int numSectors)
      throws IOException {
    IsoFileEntry entry = ifoCache.get(ifoPath);
    if (entry == null) {
      entry = udf.findFile(ifoPath);
      if (entry == null) {
        throw new IOException("DVD: IFO not found: " + ifoPath);
      }
      ifoCache.put(ifoPath, entry);
    }
    return readEntryRange(entry, sectorOffset * SECTOR_SIZE, numSectors * SECTOR_SIZE);
  }

  private byte[] readEntryRange(IsoFileEntry entry, long logicalOffset, int length)
      throws IOException {
    if (logicalOffset < 0 || logicalOffset + length > entry.length) {
      throw new IOException("DVD: IFO read out of range: " + entry.name);
    }
    byte[] data = new byte[length];
    int pos = 0;
    long extentLogicalStart = 0;
    for (int i = 0; i < entry.extentOffsets.length && pos < length; i++) {
      long extentLength = entry.extentLengths[i];
      long extentLogicalEnd = extentLogicalStart + extentLength;
      if (logicalOffset < extentLogicalEnd) {
        long offsetInExtent = Math.max(0, logicalOffset - extentLogicalStart);
        int extentReadLength = (int) Math.min(extentLength - offsetInExtent, length - pos);
        if (entry.extentOffsets[i] == IsoFileEntry.UNRECORDED_EXTENT_OFFSET) {
          Arrays.fill(data, pos, pos + extentReadLength, (byte) 0);
          pos += extentReadLength;
          logicalOffset += extentReadLength;
          extentLogicalStart = extentLogicalEnd;
          continue;
        }
        int extentRead = 0;
        while (extentRead < extentReadLength) {
          int n =
              reader.read(
                  entry.extentOffsets[i] + offsetInExtent + extentRead,
                  data,
                  pos,
                  extentReadLength - extentRead);
          if (n <= 0) {
            throw new IOException("DVD: short IFO read: " + entry.name);
          }
          extentRead += n;
          pos += n;
        }
        logicalOffset += extentReadLength;
      }
      extentLogicalStart = extentLogicalEnd;
    }
    if (pos != length) {
      throw new IOException("DVD: short IFO read: " + entry.name);
    }
    return data;
  }
}
