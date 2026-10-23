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
package androidx.media3.mpvplayer;

import androidx.annotation.Nullable;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;

/**
 * Inspects the user's mpv configuration directory and {@code mpv.conf} content.
 *
 * <p>Parsing is deliberately lenient: lines are trimmed, comments (starting with {@code #} or
 * {@code ;}) are stripped, and the option name is taken either before the first {@code =} or as
 * the whole bare token (which mpv uses for {@code yes}/{@code no} flag lines).
 */
public final class MpvConfigFileInspector {

  private static final String MPV_CONF_FILE_NAME = "mpv.conf";
  private static final String SUB_FONTS_DIR_OPTION = "sub-fonts-dir";

  private final File mpvDirectory;

  public MpvConfigFileInspector(File mpvDirectory) {
    this.mpvDirectory = mpvDirectory;
  }

  /**
   * Reads the subtitle fonts directory configured in {@code mpv.conf} ({@code sub-fonts-dir}).
   *
   * <p>Relative paths are resolved against the mpv configuration directory. Returns {@code null} if
   * the option is unset or points to a non-directory.
   */
  @Nullable
  public File readSubtitleFontsDirectory() {
    File configFile = new File(mpvDirectory, MPV_CONF_FILE_NAME);
    if (!configFile.isFile()) {
      return null;
    }
    try (Scanner scanner = new Scanner(configFile, StandardCharsets.UTF_8.name())) {
      while (scanner.hasNextLine()) {
        String line = stripComment(scanner.nextLine()).trim();
        if (line.isEmpty()) {
          continue;
        }
        int separator = line.indexOf('=');
        String key = (separator >= 0 ? line.substring(0, separator) : line).trim();
        if (!SUB_FONTS_DIR_OPTION.equals(key)) {
          continue;
        }
        String value = separator >= 0 ? line.substring(separator + 1).trim() : "";
        value = unwrapQuotes(value);
        if (value.isEmpty()) {
          return null;
        }
        File fontsDirectory = new File(value);
        if (!fontsDirectory.isAbsolute()) {
          fontsDirectory = new File(mpvDirectory, value);
        }
        return fontsDirectory.isDirectory() ? fontsDirectory : null;
      }
    } catch (Exception e) {
      return null;
    }
    return null;
  }

  /** Returns the effective option names contained in the given {@code mpv.conf} content. */
  public Set<String> getEffectiveOptionNames(CharSequence content) {
    Set<String> optionNames = new HashSet<>();
    if (content == null) {
      return optionNames;
    }
    String[] lines = content.toString().split("\r?\n");
    for (String rawLine : lines) {
      String line = stripComment(rawLine).trim();
      if (line.isEmpty()) {
        continue;
      }
      int separator = line.indexOf('=');
      String optionName = (separator >= 0 ? line.substring(0, separator) : line).trim();
      if (!optionName.isEmpty()) {
        optionNames.add(optionName);
      }
    }
    return optionNames;
  }

  private static String stripComment(String line) {
    int commentStart = line.indexOf('#');
    int legacyCommentStart = line.indexOf(';');
    if (legacyCommentStart >= 0
        && (commentStart < 0 || legacyCommentStart < commentStart)) {
      commentStart = legacyCommentStart;
    }
    return commentStart >= 0 ? line.substring(0, commentStart) : line;
  }

  private static String unwrapQuotes(String value) {
    if (value.length() >= 2
        && ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'")))) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }
}
