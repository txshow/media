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
package androidx.media3.extractor.metadata.mmt;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Metadata;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.metadata.MetadataInputBuffer;
import androidx.media3.extractor.metadata.SimpleMetadataDecoder;
import java.nio.ByteBuffer;

/**
 * Decodes MFUs, TLV-SI packets and unparsed MMTP payloads emitted by the MMT/TLV extractor.
 *
 * <p>Each sample starts with ten big-endian 32-bit fields: asset type, packet ID, MPU sequence
 * number, flags, non-timed item ID, movie fragment sequence number, sample number, offset,
 * priority, and dependency counter. Bit 0 of flags identifies timed MFUs, which use the final five
 * fields and leave the item ID unused. Bits 1 and 2 carry the FEC type when bit 3 is set. When bit
 * 4 is set, three additional fields follow the standard header: ARIB-TTML resource data type,
 * subsample number, and last subsample number. Non-timed MFUs use the item ID and leave the final
 * five fields unused. For an unparsed MMTP payload, the MPU sequence and item ID fields contain the
 * packet sequence number and payload type. The remaining bytes contain the preserved payload.
 */
@UnstableApi
public final class MmtDataDecoder extends SimpleMetadataDecoder {

  private static final int SAMPLE_HEADER_SIZE = 10 * Integer.BYTES;
  private static final int FLAG_TIMED = 1;
  private static final int FLAG_FEC_TYPE_SHIFT = 1;
  private static final int FLAG_FEC_TYPE_PRESENT = 1 << 3;
  private static final int FLAG_SUBTITLE_RESOURCE = 1 << 4;
  private static final int SUBTITLE_RESOURCE_FIELDS_SIZE = 3 * Integer.BYTES;

  @Override
  @Nullable
  protected Metadata decode(MetadataInputBuffer inputBuffer, ByteBuffer buffer) {
    if (buffer.remaining() < SAMPLE_HEADER_SIZE) {
      return null;
    }
    int assetType = buffer.getInt();
    int packetId = buffer.getInt();
    long mpuSequenceNumber = Integer.toUnsignedLong(buffer.getInt());
    int flags = buffer.getInt();
    boolean isTimed = (flags & FLAG_TIMED) != 0;
    int fecType =
        (flags & FLAG_FEC_TYPE_PRESENT) != 0
            ? (flags >> FLAG_FEC_TYPE_SHIFT) & 0x03
            : C.INDEX_UNSET;
    int itemIdBits = buffer.getInt();
    long itemId = isTimed ? C.INDEX_UNSET : Integer.toUnsignedLong(itemIdBits);
    int movieFragmentSequenceNumberBits = buffer.getInt();
    int sampleNumberBits = buffer.getInt();
    int offsetBits = buffer.getInt();
    int priorityBits = buffer.getInt();
    int dependencyCounterBits = buffer.getInt();
    long movieFragmentSequenceNumber =
        isTimed ? Integer.toUnsignedLong(movieFragmentSequenceNumberBits) : C.INDEX_UNSET;
    long sampleNumber = isTimed ? Integer.toUnsignedLong(sampleNumberBits) : C.INDEX_UNSET;
    long offset = isTimed ? Integer.toUnsignedLong(offsetBits) : C.INDEX_UNSET;
    int priority = isTimed ? priorityBits : C.INDEX_UNSET;
    int dependencyCounter = isTimed ? dependencyCounterBits : C.INDEX_UNSET;
    boolean isSubtitleResource = (flags & FLAG_SUBTITLE_RESOURCE) != 0;
    if (isSubtitleResource && buffer.remaining() < SUBTITLE_RESOURCE_FIELDS_SIZE) {
      return null;
    }
    int subtitleDataType = isSubtitleResource ? buffer.getInt() : C.INDEX_UNSET;
    int subtitleSubsampleNumber = isSubtitleResource ? buffer.getInt() : C.INDEX_UNSET;
    int subtitleLastSubsampleNumber = isSubtitleResource ? buffer.getInt() : C.INDEX_UNSET;
    byte[] data = new byte[buffer.remaining()];
    buffer.get(data);
    return new Metadata(
        new MmtData(
            assetType,
            packetId,
            fecType,
            mpuSequenceNumber,
            isTimed,
            itemId,
            movieFragmentSequenceNumber,
            sampleNumber,
            offset,
            priority,
            dependencyCounter,
            isSubtitleResource,
            subtitleDataType,
            subtitleSubsampleNumber,
            subtitleLastSubsampleNumber,
            data));
  }
}
