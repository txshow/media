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
package androidx.media3.extractor.iso.udf;

import androidx.annotation.Nullable;
import androidx.media3.common.CacheDataReader;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.iso.IsoConstants;
import androidx.media3.extractor.iso.IsoFileEntry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class UdfFileSystem {

  private static final int SECTOR_SIZE = IsoConstants.SECTOR_SIZE;
  private static final int PD_PARTITION_START_OFFSET = 188;
  private static final int PD_ACCESS_TYPE_OFFSET = 22;

  private static final long AVDP_SECTOR_MAIN = 256L;
  private static final long AVDP_SECTOR_RESERVE = 512L;
  private static final int AVDP_MVDS_LENGTH_OFFSET = 16;
  private static final int AVDP_MVDS_LOCATION_OFFSET = 20;
  private static final int AVDP_RVDS_LENGTH_OFFSET = 24;
  private static final int AVDP_RVDS_LOCATION_OFFSET = 28;

  private static final int VDS_DEFAULT_SECTORS = 32;
  private static final int FSD_SCAN_SECTOR_COUNT = 64;
  private static final int FSD_ROOT_ICB_OFFSET = 404;

  private static final int LVD_NUM_PARTITION_MAPS_OFFSET = 268;
  private static final int LVD_PARTITION_MAPS_START = 440;
  private static final int PM_TYPE2_ID_OFFSET = 5;
  private static final int PM_TYPE2_ID_LENGTH = 8;
  private static final int PM_META_FILOC_OFFSET = 40;

  private static final int FE_INFO_LENGTH_OFFSET = 56;
  private static final int FE_ICB_TAG_FLAGS_OFFSET = 34;
  private static final int FE_EA_LENGTH_OFFSET = 168;
  private static final int FE_AD_LENGTH_OFFSET = 172;
  private static final int FE_AD_START_OFFSET = 176;
  private static final int EFE_EA_LENGTH_OFFSET = 208;
  private static final int EFE_AD_LENGTH_OFFSET = 212;
  private static final int EFE_AD_START_OFFSET = 216;

  private static final int OSTA_CS0_UTF16BE = 16;

  private static final int TAG_FSD = 256;
  private static final int TAG_FID = 257;
  private static final int TAG_AED = 258;
  private static final int TAG_FE = 261;
  private static final int TAG_EFE = 266;
  private static final int TAG_AVDP = 2;
  private static final int TAG_PD = 5;
  private static final int TAG_LVD = 6;
  private static final int TAG_TD = 8;

  private static final int AD_SHORT = 0;
  private static final int AD_LONG = 1;
  private static final int AD_EXTENDED = 2;
  private static final int AD_EMBEDDED = 3;

  private static final int AD_SHORT_SIZE = 8;
  private static final int AD_LONG_SIZE = 16;
  private static final int AD_EXTENDED_SIZE = 20;
  private static final int AED_AD_LENGTH_OFFSET = 20;
  private static final int AED_AD_START_OFFSET = 24;
  private static final int MAX_ALLOCATION_DESCRIPTOR_BYTES = 1024 * 1024;
  private static final int MAX_ALLOCATION_DESCRIPTOR_CONTINUATIONS = 64;

  private static final int FID_MIN_SIZE = 38;
  private static final int FID_FILE_CHARS_OFFSET = 18;
  private static final int FID_FI_LENGTH_OFFSET = 19;
  private static final int FID_ICB_LBN_OFFSET = 24;
  private static final int FID_IMPL_USE_LEN_OFFSET = 36;

  private final byte[] sectorBuf = new byte[SECTOR_SIZE];
  private final ByteBuffer sectorBB = ByteBuffer.wrap(sectorBuf).order(ByteOrder.LITTLE_ENDIAN);

  private CacheDataReader reader;
  private long metadataPartitionSectors;
  private long fsdPhysicalSector;
  private long partition0Base;
  private long rootIcbLbn;

  public void open(CacheDataReader reader) throws IOException {
    this.reader = reader;
    findFsdAndPartitionBase();
  }

  @Nullable
  public IsoFileEntry findFile(String path) throws IOException {
    String normalized = path.replace('\\', '/');
    int lastSlash = normalized.lastIndexOf('/');
    String dirPath = lastSlash > 0 ? normalized.substring(0, lastSlash) : "";
    String fileName = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
    if (fileName.isEmpty()) {
      return null;
    }
    long dirLbn;
    if (dirPath.isEmpty()) {
      dirLbn = rootIcbLbn;
    } else {
      dirLbn = findDirLbn(dirPath);
      if (dirLbn < 0) {
        return null;
      }
    }
    Map<String, DirectoryEntry> entries = readDirectoryEntries(dirLbn);
    String nameLower = fileName.toLowerCase(Locale.US);
    for (Map.Entry<String, DirectoryEntry> e : entries.entrySet()) {
      if (e.getKey().toLowerCase(Locale.US).equals(nameLower)) {
        DirectoryEntry entry = e.getValue();
        if (entry.isDirectory || entry.extentOffsets.length == 0) {
          return null;
        }
        return new IsoFileEntry(e.getKey(), entry.extentOffsets, entry.extentLengths, entry.length);
      }
    }
    return null;
  }

  public List<String> listFiles(String dirPath) throws IOException {
    long dirLbn;
    if (dirPath == null || dirPath.isEmpty() || dirPath.equals("/")) {
      dirLbn = rootIcbLbn;
    } else {
      dirLbn = findDirLbn(dirPath);
      if (dirLbn < 0) {
        return new ArrayList<>();
      }
    }
    return listDirectory(dirLbn);
  }

  private void findFsdAndPartitionBase() throws IOException {
    long totalLength = reader.length();
    long totalSectors = totalLength > 0 ? totalLength / SECTOR_SIZE : -1;
    reader.prefetchRange(AVDP_SECTOR_MAIN * SECTOR_SIZE, (AVDP_SECTOR_RESERVE + 1) * SECTOR_SIZE);
    long mvdsLocation = -1;
    long mvdsLengthBytes = (long) VDS_DEFAULT_SECTORS * SECTOR_SIZE;
    long rvdsLocation = -1;
    long rvdsLengthBytes = 0;
    long[] avdpCandidates =
        totalSectors > AVDP_SECTOR_MAIN
            ? new long[] {AVDP_SECTOR_MAIN, AVDP_SECTOR_RESERVE, totalSectors - AVDP_SECTOR_MAIN}
            : new long[] {AVDP_SECTOR_MAIN, AVDP_SECTOR_RESERVE};
    for (long avdpSector : avdpCandidates) {
      if (!tryReadSector(avdpSector)) {
        continue;
      }
      if (readTag() == TAG_AVDP) {
        mvdsLengthBytes = sectorBB.getInt(AVDP_MVDS_LENGTH_OFFSET) & 0xFFFFFFFFL;
        mvdsLocation = sectorBB.getInt(AVDP_MVDS_LOCATION_OFFSET) & 0xFFFFFFFFL;
        rvdsLengthBytes = sectorBB.getInt(AVDP_RVDS_LENGTH_OFFSET) & 0xFFFFFFFFL;
        rvdsLocation = sectorBB.getInt(AVDP_RVDS_LOCATION_OFFSET) & 0xFFFFFFFFL;
        break;
      }
    }
    if (mvdsLocation < 0) {
      mvdsLocation = VDS_DEFAULT_SECTORS;
    }
    VdsResult vdsResult = scanVds(mvdsLocation, mvdsLengthBytes);
    if (vdsResult.partitionStart == 0 && rvdsLocation > 0) {
      vdsResult = scanVds(rvdsLocation, rvdsLengthBytes);
    }
    long partitionStart = vdsResult.partitionStart;
    long metaFileLoc = vdsResult.metadataFileLocation;
    if (partitionStart == 0) {
      throw new IOException("UDF: PartitionDescriptor not found in VDS at sector " + mvdsLocation);
    }
    this.partition0Base = partitionStart;
    if (metaFileLoc >= 0) {
      long metaEfeSector = partition0Base + metaFileLoc;
      if (tryReadSector(metaEfeSector)) {
        int tid = readTag();
        if (tid == TAG_FE || tid == TAG_EFE) {
          long infoLen = sectorBB.getLong(FE_INFO_LENGTH_OFFSET);
          metadataPartitionSectors = infoLen / SECTOR_SIZE;
        }
      }
    }
    fsdPhysicalSector = scanForTag(partitionStart, FSD_SCAN_SECTOR_COUNT);
    if (fsdPhysicalSector == 0) {
      throw new IOException("UDF: FSD not found near partitionStart=" + partitionStart);
    }
    readSector(fsdPhysicalSector);
    rootIcbLbn = sectorBB.getInt(FSD_ROOT_ICB_OFFSET) & 0xFFFFFFFFL;
  }

  private VdsResult scanVds(long location, long lengthBytes) {
    long sectors = Math.max(1, Util.ceilDivide(lengthBytes, SECTOR_SIZE));
    reader.prefetchRange(location * SECTOR_SIZE, (location + sectors) * SECTOR_SIZE);
    long partitionStart = 0;
    long metaFileLoc = -1;
    for (long s = location; s < location + sectors; s++) {
      if (!tryReadSector(s)) {
        continue;
      }
      int tag = readTag();
      if (tag == TAG_PD) {
        long start = sectorBB.getInt(PD_PARTITION_START_OFFSET) & 0xFFFFFFFFL;
        if ((sectorBB.getShort(PD_ACCESS_TYPE_OFFSET) & 0xFFFF) == 0 || partitionStart == 0) {
          partitionStart = start;
        }
      } else if (tag == TAG_LVD) {
        metaFileLoc = extractMetaFileLoc();
      } else if (tag == TAG_TD) {
        break;
      }
    }
    return new VdsResult(partitionStart, metaFileLoc);
  }

  private long scanForTag(long start, int count) {
    reader.prefetchRange(start * SECTOR_SIZE, (start + count) * SECTOR_SIZE);
    for (long s = start; s < start + count; s++) {
      if (tryReadSector(s) && readTag() == TAG_FSD) {
        return s;
      }
    }
    return 0;
  }

  private int readTag() {
    return sectorBB.getShort(0) & 0xFFFF;
  }

  private long extractMetaFileLoc() {
    int numMaps = sectorBB.getInt(LVD_NUM_PARTITION_MAPS_OFFSET);
    int pos = LVD_PARTITION_MAPS_START;
    for (int i = 0; i < numMaps; i++) {
      if (pos + 2 > SECTOR_SIZE) {
        break;
      }
      int pmType = sectorBuf[pos] & 0xFF;
      int pmLen = sectorBuf[pos + 1] & 0xFF;
      if (pmLen == 0) {
        break;
      }
      int requiredMapLength = PM_META_FILOC_OFFSET + 4;
      if (pmType == 2 && pmLen >= requiredMapLength && pos <= SECTOR_SIZE - pmLen) {
        String id8 =
            new String(
                sectorBuf,
                pos + PM_TYPE2_ID_OFFSET,
                PM_TYPE2_ID_LENGTH,
                StandardCharsets.ISO_8859_1);
        if (id8.equals("*UDF Met")) {
          return sectorBB.getInt(pos + PM_META_FILOC_OFFSET) & 0xFFFFFFFFL;
        }
      }
      pos += pmLen;
    }
    return -1;
  }

  private long findDirLbn(String dirPath) throws IOException {
    String[] parts = dirPath.replace('\\', '/').split("/");
    long dirLbn = rootIcbLbn;
    for (String part : parts) {
      if (part.isEmpty()) {
        continue;
      }
      Map<String, DirectoryEntry> entries = readDirectoryEntries(dirLbn);
      String nameLower = part.toLowerCase(Locale.US);
      @Nullable DirectoryEntry found = null;
      for (Map.Entry<String, DirectoryEntry> e : entries.entrySet()) {
        if (e.getKey().toLowerCase(Locale.US).equals(nameLower)) {
          found = e.getValue();
          break;
        }
      }
      if (found == null || !found.isDirectory) {
        return -1;
      }
      dirLbn = found.logicalBlockNumber;
    }
    return dirLbn;
  }

  private List<String> listDirectory(long dirLbn) throws IOException {
    return new ArrayList<>(readDirectoryEntries(dirLbn).keySet());
  }

  private Map<String, DirectoryEntry> readDirectoryEntries(long dirLbn) throws IOException {
    readSector(fsdPhysicalSector + dirLbn);
    int tagId = readTag();
    if (tagId != TAG_FE && tagId != TAG_EFE) {
      throw new IOException("UDF: expected FE/EFE at lbn=" + dirLbn + ", got tag=" + tagId);
    }
    FileEntryLayout layout = readFileEntryLayout(tagId);
    byte[] feSector = sectorBuf.clone();
    Map<String, DirectoryEntry> result = new HashMap<>();
    if (layout.allocationDescriptorType == AD_EMBEDDED) {
      parseDirectoryData(
          feSector, layout.allocationDescriptorOffset, layout.allocationDescriptorLength, result);
    } else {
      if (layout.informationLength > Integer.MAX_VALUE) {
        throw new IOException("UDF: directory is too large");
      }
      byte[] dirData =
          readAllocDesc(
              feSector,
              layout.allocationDescriptorOffset,
              layout.allocationDescriptorLength,
              layout.allocationDescriptorType,
              (int) layout.informationLength);
      parseDirectoryData(dirData, 0, dirData.length, result);
    }
    return result;
  }

  private byte[] readAllocDesc(
      byte[] feSector, int adOffset, int adLength, int adType, int totalLength) throws IOException {
    byte[] data = new byte[totalLength];
    int dataPos = 0;
    List<AllocationDescriptor> allocationDescriptors =
        readExtentAds(feSector, adOffset, adLength, adType, /* resolvePartRef= */ false);
    for (AllocationDescriptor descriptor : allocationDescriptors) {
      if (dataPos >= totalLength) {
        break;
      }
      if (descriptor.allocationLength == 0) {
        continue;
      }
      if (descriptor.extentType == 1 || descriptor.extentType == 2) {
        int zeroLength = (int) Math.min(descriptor.informationLength, totalLength - dataPos);
        Arrays.fill(data, dataPos, dataPos + zeroLength, (byte) 0);
        dataPos += zeroLength;
        continue;
      }
      if (descriptor.extentType != 0) {
        throw new IOException("UDF: unsupported directory extent type " + descriptor.extentType);
      }
      if (descriptor.recordedLength != descriptor.informationLength
          || descriptor.recordedLength > descriptor.allocationLength) {
        throw new IOException("UDF: unsupported directory extent lengths");
      }
      if (descriptor.informationLength == 0) {
        continue;
      }
      if (descriptor.informationLength > Integer.MAX_VALUE) {
        throw new IOException("UDF: directory extent is too large");
      }
      int extentInformationLength = (int) descriptor.informationLength;
      int numSectors = Util.ceilDivide(extentInformationLength, SECTOR_SIZE);
      int extentBytesRead = 0;
      for (int s = 0;
          s < numSectors && dataPos < totalLength && extentBytesRead < extentInformationLength;
          s++) {
        readSector(descriptor.partitionStart + descriptor.logicalBlockNumber + s);
        int toCopy =
            Math.min(
                Math.min(SECTOR_SIZE, extentInformationLength - extentBytesRead),
                totalLength - dataPos);
        System.arraycopy(sectorBuf, 0, data, dataPos, toCopy);
        dataPos += toCopy;
        extentBytesRead += toCopy;
      }
    }
    return data;
  }

  private void parseDirectoryData(
      byte[] data, int offset, int length, Map<String, DirectoryEntry> out) throws IOException {
    if (offset < 0 || length < 0 || offset > data.length - length) {
      throw new IOException("UDF: invalid directory data range");
    }
    ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    int pos = offset;
    int end = offset + length;
    while (pos + FID_MIN_SIZE <= end) {
      int tagId = bb.getShort(pos) & 0xFFFF;
      if (tagId != TAG_FID) {
        break;
      }
      int fileChars = data[pos + FID_FILE_CHARS_OFFSET] & 0xFF;
      int fiLength = data[pos + FID_FI_LENGTH_OFFSET] & 0xFF;
      long icbLbn = bb.getInt(pos + FID_ICB_LBN_OFFSET) & 0xFFFFFFFFL;
      int implUseLength = bb.getShort(pos + FID_IMPL_USE_LEN_OFFSET) & 0xFFFF;
      if (implUseLength > end - pos - FID_MIN_SIZE) {
        throw new IOException("UDF: truncated file identifier implementation data");
      }
      int nameOffset = pos + FID_MIN_SIZE + implUseLength;
      if (fiLength > end - nameOffset) {
        throw new IOException("UDF: truncated file identifier name");
      }
      boolean isDir = (fileChars & 0x02) != 0;
      boolean isParent = (fileChars & 0x08) != 0;
      if (!isParent && fiLength > 0) {
        String name = decodeOsta(data, nameOffset, fiLength);
        if (isDir) {
          out.put(name, DirectoryEntry.forDirectory(icbLbn));
        } else {
          try {
            out.put(name, resolveFileEntry(icbLbn));
          } catch (IOException ignored) {
          }
        }
      }
      int fidSize = FID_MIN_SIZE + implUseLength + fiLength;
      int padding = (4 - (fidSize % 4)) % 4;
      pos += fidSize + padding;
    }
  }

  private DirectoryEntry resolveFileEntry(long icbLbn) throws IOException {
    readSector(fsdPhysicalSector + icbLbn);
    int tagId = readTag();
    if (tagId != TAG_FE && tagId != TAG_EFE) {
      throw new IOException("UDF: expected FE/EFE at icbLbn=" + icbLbn + ", got tag=" + tagId);
    }
    FileEntryLayout layout = readFileEntryLayout(tagId);
    if (layout.allocationDescriptorType == AD_EMBEDDED || layout.allocationDescriptorLength == 0) {
      return DirectoryEntry.forUnresolvedFile(layout.informationLength);
    }
    byte[] feSector = sectorBuf.clone();
    List<Long> extentOffsets = new ArrayList<>();
    List<Long> extentLengths = new ArrayList<>();
    long remainingLength = layout.informationLength;
    List<AllocationDescriptor> allocationDescriptors =
        readExtentAds(
            feSector,
            layout.allocationDescriptorOffset,
            layout.allocationDescriptorLength,
            layout.allocationDescriptorType,
            /* resolvePartRef= */ true);
    for (AllocationDescriptor descriptor : allocationDescriptors) {
      if (remainingLength <= 0) {
        break;
      }
      if (descriptor.allocationLength == 0) {
        continue;
      }
      if (descriptor.extentType == 1 || descriptor.extentType == 2) {
        long logicalExtentLength = Math.min(descriptor.informationLength, remainingLength);
        if (logicalExtentLength > 0) {
          extentOffsets.add(IsoFileEntry.UNRECORDED_EXTENT_OFFSET);
          extentLengths.add(logicalExtentLength);
          remainingLength -= logicalExtentLength;
        }
        continue;
      }
      if (descriptor.extentType != 0
          || descriptor.recordedLength != descriptor.informationLength
          || descriptor.recordedLength > descriptor.allocationLength) {
        return DirectoryEntry.forUnresolvedFile(layout.informationLength);
      }
      if (descriptor.informationLength == 0) {
        continue;
      }
      long physicalSector = descriptor.partitionStart + descriptor.logicalBlockNumber;
      long logicalExtentLength = Math.min(descriptor.informationLength, remainingLength);
      extentOffsets.add(physicalSector * SECTOR_SIZE);
      extentLengths.add(logicalExtentLength);
      remainingLength -= logicalExtentLength;
    }
    if (extentOffsets.isEmpty() || remainingLength > 0) {
      return DirectoryEntry.forUnresolvedFile(layout.informationLength);
    }
    long[] offsetArray = new long[extentOffsets.size()];
    long[] lengthArray = new long[extentLengths.size()];
    for (int i = 0; i < extentOffsets.size(); i++) {
      offsetArray[i] = extentOffsets.get(i);
      lengthArray[i] = extentLengths.get(i);
    }
    return DirectoryEntry.forFile(layout.informationLength, offsetArray, lengthArray);
  }

  List<AllocationDescriptor> readExtentAds(
      byte[] initialData, int initialOffset, int initialLength, int adType, boolean resolvePartRef)
      throws IOException {
    if (initialOffset < 0
        || initialLength < 0
        || initialOffset > initialData.length - initialLength) {
      throw new IOException("UDF: invalid allocation descriptor range");
    }
    List<AllocationDescriptor> allocationDescriptors = new ArrayList<>();
    Set<Long> continuationOffsets = new HashSet<>();
    byte[] descriptorData = initialData;
    int position = initialOffset;
    int end = initialOffset + initialLength;
    int descriptorSize = getAllocationDescriptorSize(adType);
    int continuationCount = 0;
    while (true) {
      ByteBuffer descriptorBuffer = ByteBuffer.wrap(descriptorData).order(ByteOrder.LITTLE_ENDIAN);
      boolean hasContinuation = false;
      while (position < end) {
        if (position + descriptorSize > end) {
          throw new IOException("UDF: truncated allocation descriptor");
        }
        AllocationDescriptor allocationDescriptor =
            readExtentAd(descriptorBuffer, position, adType, resolvePartRef);
        position = allocationDescriptor.nextPosition;
        if (allocationDescriptor.allocationLength == 0) {
          return allocationDescriptors;
        }
        if (allocationDescriptor.extentType != 3) {
          allocationDescriptors.add(allocationDescriptor);
          continue;
        }
        if (++continuationCount > MAX_ALLOCATION_DESCRIPTOR_CONTINUATIONS) {
          throw new IOException("UDF: too many allocation descriptor continuations");
        }
        long continuationOffset;
        try {
          continuationOffset =
              Math.multiplyExact(
                  Math.addExact(
                      allocationDescriptor.partitionStart, allocationDescriptor.logicalBlockNumber),
                  SECTOR_SIZE);
        } catch (ArithmeticException e) {
          throw new IOException("UDF: allocation descriptor continuation overflow", e);
        }
        if (!continuationOffsets.add(continuationOffset)) {
          throw new IOException("UDF: cyclic allocation descriptor continuation");
        }
        if (allocationDescriptor.allocationLength < AED_AD_START_OFFSET
            || allocationDescriptor.allocationLength > MAX_ALLOCATION_DESCRIPTOR_BYTES) {
          throw new IOException(
              "UDF: invalid allocation descriptor continuation length: "
                  + allocationDescriptor.allocationLength);
        }
        descriptorData = readBytes(continuationOffset, allocationDescriptor.allocationLength);
        descriptorBuffer = ByteBuffer.wrap(descriptorData).order(ByteOrder.LITTLE_ENDIAN);
        if ((descriptorBuffer.getShort(0) & 0xFFFF) != TAG_AED) {
          throw new IOException("UDF: expected allocation extent descriptor");
        }
        int continuationAdLength = descriptorBuffer.getInt(AED_AD_LENGTH_OFFSET);
        if (continuationAdLength < 0
            || continuationAdLength > descriptorData.length - AED_AD_START_OFFSET) {
          throw new IOException("UDF: invalid continued allocation descriptor length");
        }
        position = AED_AD_START_OFFSET;
        end = AED_AD_START_OFFSET + continuationAdLength;
        hasContinuation = true;
        break;
      }
      if (!hasContinuation) {
        return allocationDescriptors;
      }
    }
  }

  private static int getAllocationDescriptorSize(int adType) throws IOException {
    switch (adType) {
      case AD_LONG:
        return AD_LONG_SIZE;
      case AD_EXTENDED:
        return AD_EXTENDED_SIZE;
      case AD_SHORT:
        return AD_SHORT_SIZE;
      default:
        throw new IOException("UDF: unsupported allocation descriptor type " + adType);
    }
  }

  private byte[] readBytes(long byteOffset, int length) throws IOException {
    byte[] data = new byte[length];
    int totalRead = 0;
    while (totalRead < length) {
      int bytesRead = reader.read(byteOffset + totalRead, data, totalRead, length - totalRead);
      if (bytesRead <= 0) {
        throw new IOException("UDF: short allocation descriptor continuation read");
      }
      totalRead += bytesRead;
    }
    return data;
  }

  private FileEntryLayout readFileEntryLayout(int tagId) throws IOException {
    long infoLength = sectorBB.getLong(FE_INFO_LENGTH_OFFSET);
    int icbTagFlags = sectorBB.getShort(FE_ICB_TAG_FLAGS_OFFSET) & 0xFFFF;
    int adType = icbTagFlags & 0x7;
    int eaLength, adLength, adStart;
    if (tagId == TAG_FE) {
      eaLength = sectorBB.getInt(FE_EA_LENGTH_OFFSET);
      adLength = sectorBB.getInt(FE_AD_LENGTH_OFFSET);
      adStart = FE_AD_START_OFFSET;
    } else {
      eaLength = sectorBB.getInt(EFE_EA_LENGTH_OFFSET);
      adLength = sectorBB.getInt(EFE_AD_LENGTH_OFFSET);
      adStart = EFE_AD_START_OFFSET;
    }
    if (infoLength < 0
        || eaLength < 0
        || adLength < 0
        || eaLength > SECTOR_SIZE - adStart
        || adLength > SECTOR_SIZE - adStart - eaLength) {
      throw new IOException("UDF: invalid file entry layout");
    }
    return new FileEntryLayout(adStart + eaLength, adLength, adType, infoLength);
  }

  AllocationDescriptor readExtentAd(ByteBuffer bb, int pos, int adType, boolean resolvePartRef) {
    int rawExtentLength = bb.getInt(pos);
    int extentType = rawExtentLength >>> 30;
    int allocationLength = rawExtentLength & 0x3FFFFFFF;
    long recordedLength = allocationLength;
    long informationLength = allocationLength;
    long lbn;
    long base = fsdPhysicalSector;
    int nextPos;
    switch (adType) {
      case AD_LONG:
        lbn = bb.getInt(pos + 4) & 0xFFFFFFFFL;
        if (resolvePartRef) {
          int partRef = bb.getShort(pos + 8) & 0xFFFF;
          base = (partRef == 0) ? partition0Base : fsdPhysicalSector;
        }
        nextPos = pos + AD_LONG_SIZE;
        break;
      case AD_EXTENDED:
        recordedLength = bb.getInt(pos + 4) & 0x3FFFFFFFL;
        informationLength = bb.getInt(pos + 8) & 0xFFFFFFFFL;
        lbn = bb.getInt(pos + 12) & 0xFFFFFFFFL;
        if (resolvePartRef) {
          int partRef = bb.getShort(pos + 16) & 0xFFFF;
          base = (partRef == 0) ? partition0Base : fsdPhysicalSector;
        }
        nextPos = pos + AD_EXTENDED_SIZE;
        break;
      case AD_SHORT:
        lbn = bb.getInt(pos + 4) & 0xFFFFFFFFL;
        if (metadataPartitionSectors > 0 && lbn < metadataPartitionSectors) {
          base = fsdPhysicalSector;
        } else {
          base = partition0Base;
        }
        nextPos = pos + AD_SHORT_SIZE;
        break;
      default:
        throw new IllegalArgumentException("Unsupported allocation descriptor type " + adType);
    }
    return new AllocationDescriptor(
        lbn, base, informationLength, nextPos, extentType, recordedLength, allocationLength);
  }

  static final class AllocationDescriptor {

    final long logicalBlockNumber;
    final long partitionStart;
    final long informationLength;
    final int nextPosition;
    final int extentType;
    final long recordedLength;
    final int allocationLength;

    private AllocationDescriptor(
        long logicalBlockNumber,
        long partitionStart,
        long informationLength,
        int nextPosition,
        int extentType,
        long recordedLength,
        int allocationLength) {
      this.logicalBlockNumber = logicalBlockNumber;
      this.partitionStart = partitionStart;
      this.informationLength = informationLength;
      this.nextPosition = nextPosition;
      this.extentType = extentType;
      this.recordedLength = recordedLength;
      this.allocationLength = allocationLength;
    }
  }

  private static final class FileEntryLayout {

    final int allocationDescriptorOffset;
    final int allocationDescriptorLength;
    final int allocationDescriptorType;
    final long informationLength;

    FileEntryLayout(
        int allocationDescriptorOffset,
        int allocationDescriptorLength,
        int allocationDescriptorType,
        long informationLength) {
      this.allocationDescriptorOffset = allocationDescriptorOffset;
      this.allocationDescriptorLength = allocationDescriptorLength;
      this.allocationDescriptorType = allocationDescriptorType;
      this.informationLength = informationLength;
    }
  }

  private static final class VdsResult {

    final long partitionStart;
    final long metadataFileLocation;

    VdsResult(long partitionStart, long metadataFileLocation) {
      this.partitionStart = partitionStart;
      this.metadataFileLocation = metadataFileLocation;
    }
  }

  private static final class DirectoryEntry {

    final long logicalBlockNumber;
    final long length;
    final boolean isDirectory;
    final long[] extentOffsets;
    final long[] extentLengths;

    static DirectoryEntry forDirectory(long logicalBlockNumber) {
      return new DirectoryEntry(
          logicalBlockNumber, /* length= */ 0, /* isDirectory= */ true, new long[0], new long[0]);
    }

    static DirectoryEntry forUnresolvedFile(long length) {
      return forFile(length, new long[0], new long[0]);
    }

    static DirectoryEntry forFile(long length, long[] extentOffsets, long[] extentLengths) {
      return new DirectoryEntry(
          /* logicalBlockNumber= */ 0,
          length,
          /* isDirectory= */ false,
          extentOffsets,
          extentLengths);
    }

    private DirectoryEntry(
        long logicalBlockNumber,
        long length,
        boolean isDirectory,
        long[] extentOffsets,
        long[] extentLengths) {
      this.logicalBlockNumber = logicalBlockNumber;
      this.length = length;
      this.isDirectory = isDirectory;
      this.extentOffsets = extentOffsets;
      this.extentLengths = extentLengths;
    }
  }

  private String decodeOsta(byte[] data, int offset, int length) {
    if (length == 0) {
      return "";
    }
    int compressionId = data[offset] & 0xFF;
    if (compressionId == OSTA_CS0_UTF16BE) {
      try {
        return new String(data, offset + 1, length - 1, StandardCharsets.UTF_16BE);
      } catch (Exception e) {
        return "";
      }
    }
    return new String(data, offset + 1, length - 1, StandardCharsets.ISO_8859_1);
  }

  private void readSector(long sectorNumber) throws IOException {
    long byteOffset = sectorNumber * SECTOR_SIZE;
    int total = 0;
    while (total < SECTOR_SIZE) {
      int n = reader.read(byteOffset + total, sectorBuf, total, SECTOR_SIZE - total);
      if (n == -1) {
        break;
      }
      total += n;
    }
    if (total < SECTOR_SIZE) {
      Arrays.fill(sectorBuf, total, SECTOR_SIZE, (byte) 0);
    }
    sectorBB.rewind();
  }

  private boolean tryReadSector(long sectorNumber) {
    try {
      readSector(sectorNumber);
      return true;
    } catch (IOException e) {
      return false;
    }
  }
}
