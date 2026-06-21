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
package androidx.media3.extractor.iso;

import java.util.Arrays;

public final class IsoFileEntry {

  /** Extent offset indicating that the logical extent contains zero-filled unrecorded data. */
  public static final long UNRECORDED_EXTENT_OFFSET = -1;

  // File name within the UDF directory.
  public final String name;

  // Byte offset of the file's data within the ISO image.
  public final long byteOffset;

  // Length of the file's data in bytes.
  public final long length;

  // Physical byte offsets of the file's allocation extents within the ISO image, or
  // {@link #UNRECORDED_EXTENT_OFFSET} for zero-filled unrecorded extents.
  public final long[] extentOffsets;

  // Logical byte lengths corresponding to {@link #extentOffsets}.
  public final long[] extentLengths;

  public IsoFileEntry(String name, long byteOffset, long length) {
    this(name, new long[] {byteOffset}, new long[] {length}, length);
  }

  public IsoFileEntry(String name, long[] extentOffsets, long[] extentLengths, long length) {
    if (extentOffsets.length == 0 || extentOffsets.length != extentLengths.length) {
      throw new IllegalArgumentException("Invalid ISO file extents");
    }
    this.name = name;
    this.byteOffset = extentOffsets[0];
    this.length = length;
    this.extentOffsets = Arrays.copyOf(extentOffsets, extentOffsets.length);
    this.extentLengths = Arrays.copyOf(extentLengths, extentLengths.length);
  }
}
