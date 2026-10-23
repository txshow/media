/*
 * Copyright (C) 2026 The Android Open Source Project
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
package androidx.media3.exoplayer.libass;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads metadata from font files (TrueType / OpenType / TrueType Collection).
 *
 * <p>This is a partial implementation: it extracts the typographic / font family name from the
 * sfnt {@code name} table (nameID 16 preferred, falling back to nameID 1, Windows Unicode BMP
 * English-US records). Other metadata is not read.
 */
@UnstableApi
public final class LibassFontFile {

  private static final int NAME_TABLE_TAG = 0x6E616D65; // 'name'
  private static final int NAME_ID_FAMILY = 1;
  private static final int NAME_ID_TYPOGRAPHIC_FAMILY = 16;
  private static final int PLATFORM_ID_WINDOWS = 3;
  private static final int ENCODING_ID_WINDOWS_UNICODE_BMP = 1;
  private static final int LANGUAGE_ID_ENGLISH_US = 0x0409;

  private LibassFontFile() {}

  /**
   * Returns the family name of the given font file, or {@code null} if it cannot be determined.
   *
   * <p>The parser handles TTF, OTF and TTC (first font in the collection). It looks for the
   * typographic family name (nameID 16) first, then falls back to the font family name (nameID 1).
   *
   * @param file The font file.
   * @return The family name, or {@code null} if not found / unreadable.
   * @throws IOException If reading the file fails.
   */
  @Nullable
  public static String getFamilyName(File file) throws IOException {
    if (file == null || !file.isFile() || file.length() <= 0) {
      return null;
    }
    try (InputStream input = new FileInputStream(file)) {
      return readFamilyName(input);
    }
  }

  @Nullable
  private static String readFamilyName(InputStream rawInput) throws IOException {
    DataInputStream input = new DataInputStream(rawInput);
    int sfntVersion = input.readInt();
    // TTC: 0x74746366 ('ttcf'). We only read the first font.
    if (sfntVersion == 0x74746366) {
      input.readInt(); // majorVersion
      input.readInt(); // minorVersion
      int numFonts = input.readInt();
      if (numFonts <= 0) {
        return null;
      }
      // Read the offset table for the first font.
      input.readInt(); // offsetTable[0]
      // Now we're at the start of the first font's offset table.
      sfntVersion = input.readInt();
    }
    // Accept TrueType (0x00010000) or OpenType ('OTTO' = 0x4F54544F).
    if (sfntVersion != 0x00010000 && sfntVersion != 0x4F54544F) {
      return null;
    }
    int numTables = input.readUnsignedShort();
    input.readUnsignedShort(); // searchRange
    input.readUnsignedShort(); // entrySelector
    input.readUnsignedShort(); // rangeShift

    long nameTableOffset = -1;
    int nameTableLength = -1;
    for (int i = 0; i < numTables; i++) {
      int tag = input.readInt();
      input.readInt(); // checksum
      long offset = input.readInt() & 0xFFFFFFFFL;
      int length = input.readInt();
      if (tag == NAME_TABLE_TAG) {
        nameTableOffset = offset;
        nameTableLength = length;
      }
    }
    if (nameTableOffset < 0 || nameTableLength <= 0) {
      return null;
    }
    return readNameTable(input, nameTableOffset, nameTableLength);
  }

  @Nullable
  private static String readNameTable(DataInputStream input, long tableOffset, int tableLength)
      throws IOException {
    // Read the whole name table into memory (it's typically small).
    byte[] table = new byte[tableLength];
    // Skip to the table offset.
    long skipped = 0;
    while (skipped < tableOffset) {
      long skippedNow = input.skip(tableOffset - skipped);
      if (skippedNow <= 0) {
        return null;
      }
      skipped += skippedNow;
    }
    int read = 0;
    while (read < tableLength) {
      int count = input.read(table, read, tableLength - read);
      if (count < 0) {
        return null;
      }
      read += count;
    }

    DataInputStream tableInput = new DataInputStream(new ByteArrayInputStream(table));
    tableInput.readUnsignedShort(); // format
    int count = tableInput.readUnsignedShort();
    int stringOffset = tableInput.readUnsignedShort();

    String typographicFamily = null;
    String family = null;
    for (int i = 0; i < count; i++) {
      int platformID = tableInput.readUnsignedShort();
      int encodingID = tableInput.readUnsignedShort();
      int languageID = tableInput.readUnsignedShort();
      int nameID = tableInput.readUnsignedShort();
      int length = tableInput.readUnsignedShort();
      int offset = tableInput.readUnsignedShort();
      if (platformID != PLATFORM_ID_WINDOWS
          || encodingID != ENCODING_ID_WINDOWS_UNICODE_BMP
          || languageID != LANGUAGE_ID_ENGLISH_US) {
        continue;
      }
      if (nameID != NAME_ID_FAMILY && nameID != NAME_ID_TYPOGRAPHIC_FAMILY) {
        continue;
      }
      if (stringOffset + offset + length > table.length) {
        continue;
      }
      String value =
          new String(
              table,
              stringOffset + offset,
              length,
              StandardCharsets.UTF_16BE);
      if (nameID == NAME_ID_TYPOGRAPHIC_FAMILY) {
        typographicFamily = value;
      } else if (nameID == NAME_ID_FAMILY) {
        family = value;
      }
    }
    if (typographicFamily != null && !typographicFamily.trim().isEmpty()) {
      return typographicFamily.trim();
    }
    if (family != null && !family.trim().isEmpty()) {
      return family.trim();
    }
    return null;
  }
}
