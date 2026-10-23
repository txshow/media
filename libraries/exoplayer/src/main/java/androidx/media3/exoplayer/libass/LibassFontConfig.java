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

import androidx.media3.common.util.UnstableApi;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Writes a minimal fontconfig {@code fonts.conf} file that points libass (via fontconfig) at the
 * required font directories and cache.
 *
 * <p>The generated XML is a valid subset of the fontconfig configuration format: it declares the
 * {@code <dir>} entries that fontconfig should scan for fonts, a {@code <cachedir>} for the
 * fontconfig cache, and a couple of standard aliases so that the default family fallback works.
 */
@UnstableApi
public final class LibassFontConfig {

  private LibassFontConfig() {}

  /**
   * Writes a {@code fonts.conf} configuration to {@code output}.
   *
   * @param output The file to write (parent directories will be created if needed).
   * @param cacheDirectory The directory where fontconfig should store its cache.
   * @param fontDirectories The directories to scan for font files.
   * @throws IOException If writing the file fails.
   */
  public static void write(File output, File cacheDirectory, List<File> fontDirectories)
      throws IOException {
    File parentFile = output.getParentFile();
    if (parentFile != null && !parentFile.exists()) {
      parentFile.mkdirs();
    }
    try (BufferedWriter writer =
        new BufferedWriter(
            new OutputStreamWriter(new FileOutputStream(output), StandardCharsets.UTF_8))) {
      writer.write("<?xml version=\"1.0\"?>");
      writer.newLine();
      writer.write("<!DOCTYPE fontconfig SYSTEM \"fonts.dtd\">");
      writer.newLine();
      writer.write("<fontconfig>");
      writer.newLine();

      writer.append("  <dir>").append(escape(cacheDirectory.getAbsolutePath())).append("</dir>");
      writer.newLine();
      writer.append("  <cachedir>")
          .append(escape(cacheDirectory.getAbsolutePath()))
          .append("</cachedir>");
      writer.newLine();

      if (fontDirectories != null) {
        for (File dir : fontDirectories) {
          if (dir == null) {
            continue;
          }
          writer.append("  <dir>").append(escape(dir.getAbsolutePath())).append("</dir>");
          writer.newLine();
        }
      }

      // Standard sans-serif/serif/monospace aliases so generic family requests resolve.
      writer.write("  <alias>");
      writer.newLine();
      writer.write("    <family>sans-serif</family>");
      writer.newLine();
      writer.write("    <prefer><family>Noto Sans</family></prefer>");
      writer.newLine();
      writer.write("  </alias>");
      writer.newLine();

      writer.write("</fontconfig>");
      writer.newLine();
    }
  }

  private static String escape(String value) {
    StringBuilder sb = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '<':
          sb.append("&lt;");
          break;
        case '>':
          sb.append("&gt;");
          break;
        case '&':
          sb.append("&amp;");
          break;
        case '"':
          sb.append("&quot;");
          break;
        case '\'':
          sb.append("&apos;");
          break;
        default:
          sb.append(c);
      }
    }
    return sb.toString();
  }
}
