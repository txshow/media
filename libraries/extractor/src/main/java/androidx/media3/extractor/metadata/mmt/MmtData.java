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
import androidx.media3.common.util.Util;
import java.util.Arrays;

/** One complete MFU, TLV-SI packet or unparsed MMTP payload emitted by the MMT/TLV extractor. */
@UnstableApi
public final class MmtData implements Metadata.Entry {

  /**
   * FourCC of the MMT data, such as {@code aapp}, {@code asgd}, {@code aagd}, {@code mmtp} or
   * {@code tlvs}.
   */
  public final int assetType;

  /** Unsigned 16-bit MMTP packet ID carrying this asset, or {@code 0xFFFF} for TLV-SI. */
  public final int packetId;

  /** MMTP FEC type for a raw MMTP payload, or {@link C#INDEX_UNSET} for an MFU. */
  public final int fecType;

  /**
   * Unsigned 32-bit MPU sequence number, MMTP packet sequence number for an unparsed MMTP payload,
   * or extractor sequence number for TLV-SI.
   */
  public final long mpuSequenceNumber;

  /** Whether this MFU carries timed media data. */
  public final boolean isTimed;

  /**
   * Unsigned 32-bit item ID from the non-timed MFU header, MMTP payload type for an unparsed MMTP
   * payload, TLV-SI table ID for TLV-SI, or {@link C#INDEX_UNSET} for a timed MFU.
   */
  public final long itemId;

  /**
   * Unsigned 32-bit movie fragment sequence number from the timed MFU header, or {@link
   * C#INDEX_UNSET} for a non-timed MFU.
   */
  public final long movieFragmentSequenceNumber;

  /**
   * Unsigned 32-bit sample number from the timed MFU header, or {@link C#INDEX_UNSET} for a
   * non-timed MFU.
   */
  public final long sampleNumber;

  /**
   * Unsigned 32-bit media data unit offset within the referenced sample, or {@link C#INDEX_UNSET}
   * for a non-timed MFU.
   */
  public final long offset;

  /** Timed MFU priority, or {@link C#INDEX_UNSET} for a non-timed MFU. */
  public final int priority;

  /** Timed MFU dependency counter, or {@link C#INDEX_UNSET} for a non-timed MFU. */
  public final int dependencyCounter;

  /** Whether this entry contains an ARIB-TTML resource rather than a TTML document. */
  public final boolean isSubtitleResource;

  /** ARIB-TTML resource data type, or {@link C#INDEX_UNSET} for other entries. */
  public final int subtitleDataType;

  /** ARIB-TTML resource subsample number, or {@link C#INDEX_UNSET} for other entries. */
  public final int subtitleSubsampleNumber;

  /** ARIB-TTML resource last subsample number, or {@link C#INDEX_UNSET} for other entries. */
  public final int subtitleLastSubsampleNumber;

  /** Complete data after the extractor-specific metadata header. */
  public final byte[] data;

  public MmtData(
      int assetType,
      int packetId,
      int fecType,
      long mpuSequenceNumber,
      boolean isTimed,
      long itemId,
      long movieFragmentSequenceNumber,
      long sampleNumber,
      long offset,
      int priority,
      int dependencyCounter,
      boolean isSubtitleResource,
      int subtitleDataType,
      int subtitleSubsampleNumber,
      int subtitleLastSubsampleNumber,
      byte[] data) {
    this.assetType = assetType;
    this.packetId = packetId;
    this.fecType = fecType;
    this.mpuSequenceNumber = mpuSequenceNumber;
    this.isTimed = isTimed;
    this.itemId = itemId;
    this.movieFragmentSequenceNumber = movieFragmentSequenceNumber;
    this.sampleNumber = sampleNumber;
    this.offset = offset;
    this.priority = priority;
    this.dependencyCounter = dependencyCounter;
    this.isSubtitleResource = isSubtitleResource;
    this.subtitleDataType = subtitleDataType;
    this.subtitleSubsampleNumber = subtitleSubsampleNumber;
    this.subtitleLastSubsampleNumber = subtitleLastSubsampleNumber;
    this.data = data;
  }

  /** Returns the four-character asset type. */
  public String getAssetTypeString() {
    return Util.toFourccString(assetType);
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    MmtData other = (MmtData) obj;
    return assetType == other.assetType
        && packetId == other.packetId
        && fecType == other.fecType
        && mpuSequenceNumber == other.mpuSequenceNumber
        && isTimed == other.isTimed
        && itemId == other.itemId
        && movieFragmentSequenceNumber == other.movieFragmentSequenceNumber
        && sampleNumber == other.sampleNumber
        && offset == other.offset
        && priority == other.priority
        && dependencyCounter == other.dependencyCounter
        && isSubtitleResource == other.isSubtitleResource
        && subtitleDataType == other.subtitleDataType
        && subtitleSubsampleNumber == other.subtitleSubsampleNumber
        && subtitleLastSubsampleNumber == other.subtitleLastSubsampleNumber
        && Arrays.equals(data, other.data);
  }

  @Override
  public int hashCode() {
    int result = 31 * assetType + packetId;
    result = 31 * result + fecType;
    result = 31 * result + (int) (mpuSequenceNumber ^ (mpuSequenceNumber >>> 32));
    result = 31 * result + (isTimed ? 1 : 0);
    result = 31 * result + (int) (itemId ^ (itemId >>> 32));
    result =
        31 * result + (int) (movieFragmentSequenceNumber ^ (movieFragmentSequenceNumber >>> 32));
    result = 31 * result + (int) (sampleNumber ^ (sampleNumber >>> 32));
    result = 31 * result + (int) (offset ^ (offset >>> 32));
    result = 31 * result + priority;
    result = 31 * result + dependencyCounter;
    result = 31 * result + (isSubtitleResource ? 1 : 0);
    result = 31 * result + subtitleDataType;
    result = 31 * result + subtitleSubsampleNumber;
    result = 31 * result + subtitleLastSubsampleNumber;
    return 31 * result + Arrays.hashCode(data);
  }

  @Override
  public String toString() {
    return "MmtData(assetType="
        + getAssetTypeString()
        + ", packetId="
        + packetId
        + ", fecType="
        + fecType
        + ", mpuSequenceNumber="
        + mpuSequenceNumber
        + ", isTimed="
        + isTimed
        + ", itemId="
        + itemId
        + ", movieFragmentSequenceNumber="
        + movieFragmentSequenceNumber
        + ", sampleNumber="
        + sampleNumber
        + ", offset="
        + offset
        + ", priority="
        + priority
        + ", dependencyCounter="
        + dependencyCounter
        + ", isSubtitleResource="
        + isSubtitleResource
        + ", subtitleDataType="
        + subtitleDataType
        + ", subtitleSubsampleNumber="
        + subtitleSubsampleNumber
        + ", subtitleLastSubsampleNumber="
        + subtitleLastSubsampleNumber
        + ", size="
        + data.length
        + ")";
  }
}
