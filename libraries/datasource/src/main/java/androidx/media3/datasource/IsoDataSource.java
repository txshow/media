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
package androidx.media3.datasource;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class IsoDataSource extends BaseDataSource {

  private static final int TS_PACKET_SIZE = 188;
  private static final int M2TS_PACKET_SIZE = 192;
  private static final int M2TS_HEADER_SIZE = M2TS_PACKET_SIZE - TS_PACKET_SIZE;

  private static final int SACD_SECTOR_SIZE = 2048;
  private static final int SACD_HEADER_SIZE = 4;
  private static final int SACD_PAYLOAD_SIZE = SACD_SECTOR_SIZE - SACD_HEADER_SIZE;
  private static final long ZERO_FILLED_EXTENT_OFFSET = -1;

  private final byte[] m2tsBuf = new byte[M2TS_PACKET_SIZE];
  private final byte[] sacdBuf = new byte[SACD_SECTOR_SIZE];
  private final DataSource upstream;
  private final long[] extentByteOffsets;
  private final long[] extentByteLengths;
  private final long clipLogicalByteOffset;
  private final long clipByteLength;
  private final boolean stripM2tsHeaders;
  private final boolean stripSacdHeaders;

  @Nullable private Uri uri;
  private long bytesRemaining;
  private int m2tsBufPos;
  private int m2tsBufLimit;
  private int sacdBufPos;
  private int sacdBufLimit;
  private boolean upstreamOpened;
  private boolean opened;
  @Nullable private DataSpec openedDataSpec;
  private long rawReadPosition;
  private long rawReadEnd;
  private long currentExtentBytesRemaining;
  private boolean currentExtentIsZeroFilled;

  public IsoDataSource(
      DataSource upstream,
      long[] extentByteOffsets,
      long[] extentByteLengths,
      long clipLogicalByteOffset,
      long clipByteLength,
      boolean stripM2tsHeaders,
      boolean stripSacdHeaders) {
    super(false);
    if (extentByteOffsets.length == 0 || extentByteOffsets.length != extentByteLengths.length) {
      throw new IllegalArgumentException("Invalid ISO extents");
    }
    this.upstream = upstream;
    this.extentByteOffsets = Arrays.copyOf(extentByteOffsets, extentByteOffsets.length);
    this.extentByteLengths = Arrays.copyOf(extentByteLengths, extentByteLengths.length);
    long totalExtentLength = 0;
    for (int i = 0; i < extentByteOffsets.length; i++) {
      if ((extentByteOffsets[i] < 0 && extentByteOffsets[i] != ZERO_FILLED_EXTENT_OFFSET)
          || extentByteLengths[i] < 0) {
        throw new IllegalArgumentException("Invalid ISO extent");
      }
      totalExtentLength = Math.addExact(totalExtentLength, extentByteLengths[i]);
    }
    if (clipLogicalByteOffset < 0
        || clipByteLength < 0
        || clipLogicalByteOffset > totalExtentLength
        || clipByteLength > totalExtentLength - clipLogicalByteOffset) {
      throw new IllegalArgumentException("ISO clip is outside its extents");
    }
    this.clipLogicalByteOffset = clipLogicalByteOffset;
    this.clipByteLength = clipByteLength;
    this.stripM2tsHeaders = stripM2tsHeaders;
    this.stripSacdHeaders = stripSacdHeaders;
  }

  @Override
  public long open(DataSpec dataSpec) throws IOException {
    uri = dataSpec.uri;
    long clipPosition = dataSpec.position;
    long clipVirtualLength;
    if (stripM2tsHeaders) {
      clipVirtualLength = (clipByteLength / M2TS_PACKET_SIZE) * TS_PACKET_SIZE;
    } else if (stripSacdHeaders) {
      clipVirtualLength = (clipByteLength / SACD_SECTOR_SIZE) * SACD_PAYLOAD_SIZE;
    } else {
      clipVirtualLength = clipByteLength;
    }
    if (clipPosition > clipVirtualLength) {
      throw new DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
    }
    long rawClipPosition;
    long rawLength;
    long packetOffset = 0;
    if (stripM2tsHeaders) {
      long packetIndex = clipPosition / TS_PACKET_SIZE;
      packetOffset = clipPosition % TS_PACKET_SIZE;
      rawClipPosition = packetIndex * M2TS_PACKET_SIZE;
      long virtualRemaining = clipVirtualLength - clipPosition;
      bytesRemaining =
          dataSpec.length == C.LENGTH_UNSET
              ? virtualRemaining
              : Math.min(dataSpec.length, virtualRemaining);
      long packetsNeeded = (bytesRemaining + packetOffset + TS_PACKET_SIZE - 1) / TS_PACKET_SIZE;
      rawLength = packetsNeeded * M2TS_PACKET_SIZE;
    } else if (stripSacdHeaders) {
      long sectorIndex = clipPosition / SACD_PAYLOAD_SIZE;
      packetOffset = clipPosition % SACD_PAYLOAD_SIZE;
      rawClipPosition = sectorIndex * SACD_SECTOR_SIZE;
      long virtualRemaining = clipVirtualLength - clipPosition;
      bytesRemaining =
          dataSpec.length == C.LENGTH_UNSET
              ? virtualRemaining
              : Math.min(dataSpec.length, virtualRemaining);
      long sectorsNeeded =
          (bytesRemaining + packetOffset + SACD_PAYLOAD_SIZE - 1) / SACD_PAYLOAD_SIZE;
      rawLength = sectorsNeeded * SACD_SECTOR_SIZE;
    } else {
      rawClipPosition = clipPosition;
      long rawRemaining = clipVirtualLength - clipPosition;
      bytesRemaining =
          dataSpec.length == C.LENGTH_UNSET
              ? rawRemaining
              : Math.min(dataSpec.length, rawRemaining);
      rawLength = bytesRemaining;
    }
    m2tsBufPos = 0;
    m2tsBufLimit = 0;
    sacdBufPos = 0;
    sacdBufLimit = 0;
    openedDataSpec = dataSpec;
    rawReadPosition = clipLogicalByteOffset + rawClipPosition;
    rawReadEnd = Math.min(clipLogicalByteOffset + clipByteLength, rawReadPosition + rawLength);
    currentExtentBytesRemaining = 0;
    currentExtentIsZeroFilled = false;
    transferInitializing(dataSpec);
    if (rawReadPosition < rawReadEnd) {
      openExtentForCurrentPosition();
    }
    opened = true;
    transferStarted(dataSpec);
    if (stripM2tsHeaders && packetOffset > 0) {
      int got = readFully(m2tsBuf, M2TS_PACKET_SIZE);
      if (got == M2TS_PACKET_SIZE) {
        m2tsBufPos = M2TS_HEADER_SIZE + (int) packetOffset;
        m2tsBufLimit = M2TS_PACKET_SIZE;
      }
    } else if (stripSacdHeaders && packetOffset > 0) {
      int got = readFully(sacdBuf, SACD_SECTOR_SIZE);
      if (got == SACD_SECTOR_SIZE) {
        sacdBufPos = SACD_HEADER_SIZE + (int) packetOffset;
        sacdBufLimit = SACD_SECTOR_SIZE;
      }
    }
    return dataSpec.length != C.LENGTH_UNSET ? dataSpec.length : bytesRemaining;
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    if (length == 0) {
      return 0;
    }
    if (bytesRemaining == 0) {
      return C.RESULT_END_OF_INPUT;
    }
    int toRead = (int) Math.min(length, bytesRemaining);
    int totalRead = 0;
    if (stripM2tsHeaders) {
      while (totalRead < toRead) {
        if (m2tsBufPos >= m2tsBufLimit) {
          int got = readFully(m2tsBuf, M2TS_PACKET_SIZE);
          if (got < M2TS_PACKET_SIZE) {
            if (bytesRemaining > TS_PACKET_SIZE) {
              throw new IOException(
                  "Unexpected end of M2TS stream: " + bytesRemaining + " bytes remaining");
            }
            break;
          }
          m2tsBufPos = M2TS_HEADER_SIZE;
          m2tsBufLimit = M2TS_PACKET_SIZE;
        }
        int available = m2tsBufLimit - m2tsBufPos;
        int copy = Math.min(toRead - totalRead, available);
        System.arraycopy(m2tsBuf, m2tsBufPos, buffer, offset + totalRead, copy);
        m2tsBufPos += copy;
        totalRead += copy;
      }
    } else if (stripSacdHeaders) {
      while (totalRead < toRead) {
        if (sacdBufPos >= sacdBufLimit) {
          int got = readFully(sacdBuf, SACD_SECTOR_SIZE);
          if (got < SACD_SECTOR_SIZE) {
            break;
          }
          sacdBufPos = SACD_HEADER_SIZE;
          sacdBufLimit = SACD_SECTOR_SIZE;
        }
        int available = sacdBufLimit - sacdBufPos;
        int copy = Math.min(toRead - totalRead, available);
        System.arraycopy(sacdBuf, sacdBufPos, buffer, offset + totalRead, copy);
        sacdBufPos += copy;
        totalRead += copy;
      }
    } else {
      totalRead = readRaw(buffer, offset, toRead);
      if (totalRead == C.RESULT_END_OF_INPUT) {
        totalRead = 0;
      }
    }
    if (totalRead > 0) {
      bytesRemaining -= totalRead;
      bytesTransferred(totalRead);
    }
    return totalRead == 0 ? C.RESULT_END_OF_INPUT : totalRead;
  }

  @Nullable
  @Override
  public Uri getUri() {
    if (upstreamOpened) {
      @Nullable Uri upstreamUri = upstream.getUri();
      if (upstreamUri != null) {
        return upstreamUri;
      }
    }
    return uri;
  }

  @Override
  public Map<String, List<String>> getResponseHeaders() {
    return upstreamOpened ? upstream.getResponseHeaders() : Collections.emptyMap();
  }

  @Override
  public void close() throws IOException {
    uri = null;
    openedDataSpec = null;
    try {
      if (upstreamOpened) {
        upstreamOpened = false;
        upstream.close();
      }
    } finally {
      if (opened) {
        opened = false;
        transferEnded();
      }
    }
  }

  private int readFully(byte[] buf, int length) throws IOException {
    int total = 0;
    while (total < length) {
      int read = readRaw(buf, total, length - total);
      if (read == C.RESULT_END_OF_INPUT) {
        break;
      }
      total += read;
    }
    return total;
  }

  private int readRaw(byte[] buffer, int offset, int length) throws IOException {
    int totalRead = 0;
    while (totalRead < length && rawReadPosition < rawReadEnd) {
      if (currentExtentBytesRemaining == 0) {
        openExtentForCurrentPosition();
      }
      int toRead =
          (int)
              Math.min(
                  Math.min((long) length - totalRead, currentExtentBytesRemaining),
                  rawReadEnd - rawReadPosition);
      int read;
      if (currentExtentIsZeroFilled) {
        Arrays.fill(buffer, offset + totalRead, offset + totalRead + toRead, (byte) 0);
        read = toRead;
      } else {
        read = upstream.read(buffer, offset + totalRead, toRead);
      }
      if (read == C.RESULT_END_OF_INPUT) {
        break;
      }
      if (read == 0) {
        throw new IOException("ISO upstream made no read progress");
      }
      totalRead += read;
      rawReadPosition += read;
      currentExtentBytesRemaining -= read;
    }
    return totalRead == 0 ? C.RESULT_END_OF_INPUT : totalRead;
  }

  private void openExtentForCurrentPosition() throws IOException {
    if (upstreamOpened) {
      upstream.close();
      upstreamOpened = false;
    }
    currentExtentIsZeroFilled = false;
    long extentLogicalStart = 0;
    for (int i = 0; i < extentByteOffsets.length; i++) {
      long extentLength = extentByteLengths[i];
      long extentLogicalEnd = extentLogicalStart + extentLength;
      if (rawReadPosition >= extentLogicalStart && rawReadPosition < extentLogicalEnd) {
        long offsetInExtent = rawReadPosition - extentLogicalStart;
        currentExtentBytesRemaining =
            Math.min(extentLength - offsetInExtent, rawReadEnd - rawReadPosition);
        if (extentByteOffsets[i] == ZERO_FILLED_EXTENT_OFFSET) {
          currentExtentIsZeroFilled = true;
          return;
        }
        DataSpec dataSpec = openedDataSpec;
        if (dataSpec == null) {
          throw new IOException("ISO data source is not open");
        }
        DataSpec upstreamSpec =
            dataSpec
                .buildUpon()
                .setPosition(extentByteOffsets[i] + offsetInExtent)
                .setLength(currentExtentBytesRemaining)
                .build();
        upstreamOpened = true;
        upstream.open(upstreamSpec);
        return;
      }
      extentLogicalStart = extentLogicalEnd;
    }
    throw new DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
  }

  public static final class Factory implements DataSource.Factory {

    private final DataSource.Factory upstreamFactory;
    private final long[] extentByteOffsets;
    private final long[] extentByteLengths;
    private final long clipLogicalByteOffset;
    private final long clipByteLength;
    private final boolean stripM2tsHeaders;
    private final boolean stripSacdHeaders;

    public Factory(
        DataSource.Factory upstreamFactory,
        long byteOffset,
        long byteLength,
        boolean stripM2tsHeaders) {
      this(upstreamFactory, byteOffset, byteLength, stripM2tsHeaders, false);
    }

    public Factory(
        DataSource.Factory upstreamFactory,
        long byteOffset,
        long byteLength,
        boolean stripM2tsHeaders,
        boolean stripSacdHeaders) {
      this(
          upstreamFactory,
          new long[] {byteOffset},
          new long[] {byteLength},
          /* clipLogicalByteOffset= */ 0,
          byteLength,
          stripM2tsHeaders,
          stripSacdHeaders);
    }

    public Factory(
        DataSource.Factory upstreamFactory,
        long[] extentByteOffsets,
        long[] extentByteLengths,
        long clipLogicalByteOffset,
        long clipByteLength,
        boolean stripM2tsHeaders,
        boolean stripSacdHeaders) {
      if (extentByteOffsets.length == 0 || extentByteOffsets.length != extentByteLengths.length) {
        throw new IllegalArgumentException("Invalid ISO extents");
      }
      this.upstreamFactory = upstreamFactory;
      this.extentByteOffsets = Arrays.copyOf(extentByteOffsets, extentByteOffsets.length);
      this.extentByteLengths = Arrays.copyOf(extentByteLengths, extentByteLengths.length);
      this.clipLogicalByteOffset = clipLogicalByteOffset;
      this.clipByteLength = clipByteLength;
      this.stripM2tsHeaders = stripM2tsHeaders;
      this.stripSacdHeaders = stripSacdHeaders;
    }

    @Override
    public DataSource createDataSource() {
      return new IsoDataSource(
          upstreamFactory.createDataSource(),
          extentByteOffsets,
          extentByteLengths,
          clipLogicalByteOffset,
          clipByteLength,
          stripM2tsHeaders,
          stripSacdHeaders);
    }
  }
}
