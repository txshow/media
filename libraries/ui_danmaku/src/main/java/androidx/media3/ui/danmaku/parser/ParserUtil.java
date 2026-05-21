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
 *
 */
package androidx.media3.ui.danmaku.parser;

import androidx.annotation.Nullable;
import androidx.media3.common.util.Util;
import androidx.media3.ui.danmaku.Danmaku;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONException;

final class ParserUtil {

  static final float SMALL_SP = 12f;
  static final float DEFAULT_SP = 0f;
  static final float LARGE_SP = 18f;
  private static final int BILI_SIZE_SMALL = 18;
  private static final int BILI_SIZE_LARGE = 36;
  private static final int BILI_POSITION_X_INDEX = 0;
  private static final int BILI_POSITION_Y_INDEX = 1;
  private static final int BILI_POSITION_DURATION_INDEX = 3;
  private static final int BILI_POSITION_TEXT_INDEX = 4;
  private static final double BILI_PLAYER_WIDTH = 672.0;
  private static final double BILI_PLAYER_HEIGHT = 438.0;

  static String readSniffHeader(InputStream inputStream, int sniffLength) throws IOException {
    byte[] buffer = new byte[sniffLength];
    int totalBytesRead = 0;
    int bytesRead;
    while (totalBytesRead < sniffLength
        && (bytesRead = inputStream.read(buffer, totalBytesRead, sniffLength - totalBytesRead))
            != -1) {
      totalBytesRead += bytesRead;
    }
    int start = 0;
    if (totalBytesRead >= 3
        && (buffer[0] & 0xFF) == 0xEF
        && (buffer[1] & 0xFF) == 0xBB
        && (buffer[2] & 0xFF) == 0xBF) {
      start = 3;
    }
    return new String(buffer, start, totalBytesRead - start, StandardCharsets.UTF_8);
  }

  static String skipXmlDeclaration(String header) {
    String trimmed = header.trim();
    if (trimmed.startsWith("<?xml")) {
      int close = trimmed.indexOf("?>");
      if (close >= 0) {
        trimmed = trimmed.substring(close + 2).trim();
      }
    }
    return trimmed;
  }

  @Nullable
  static Danmaku parsePAttr(String pAttr, String text) {
    String[] parts = pAttr.split(",");
    if (parts.length < 4) {
      return null;
    }
    float timeSec;
    int mode;
    int rawSize;
    long rawColor;
    try {
      timeSec = Float.parseFloat(parts[0]);
      mode = Integer.parseInt(parts[1]);
      rawSize = Integer.parseInt(parts[2]);
      rawColor = Long.parseLong(parts[3]);
    } catch (NumberFormatException e) {
      return null;
    }
    long timeMs = (long) (timeSec * 1000);
    int color = 0xFF000000 | (int) (rawColor & 0xFFFFFF);
    float textSizeSp = mapBiliTextSize(rawSize);
    if (mode == 7) {
      return parsePositionedDanmaku(text, timeMs, color, textSizeSp);
    }
    int type = mapBiliMode(mode);
    if (type < 0) {
      return null;
    }
    int pool = Danmaku.POOL_NORMAL;
    if (parts.length >= 6) {
      try {
        int rawPool = Integer.parseInt(parts[5]);
        if (rawPool == Danmaku.POOL_SUBTITLE || rawPool == Danmaku.POOL_SPECIAL) {
          pool = rawPool;
        }
      } catch (NumberFormatException ignored) {
      }
    }
    String userId = parts.length >= 7 ? parts[6] : "";
    long rowId = 0;
    if (parts.length >= 8) {
      try {
        rowId = Long.parseLong(parts[7]);
      } catch (NumberFormatException ignored) {
      }
    }
    @Nullable
    int[] sourceGradientColors =
        parts.length >= 9 && !parts[8].isEmpty() ? parseHexColorStrings(parts[8].split(":")) : null;
    return new Danmaku(
        text, timeMs, type, color, textSizeSp, pool, userId, rowId, sourceGradientColors);
  }

  static int mapBiliMode(int mode) {
    switch (mode) {
      case 1:
      case 2:
      case 3:
        return Danmaku.TYPE_SCROLL;
      case 4:
        return Danmaku.TYPE_BOTTOM;
      case 5:
        return Danmaku.TYPE_TOP;
      case 6:
        return Danmaku.TYPE_REVERSE;
      default:
        return -1;
    }
  }

  static float mapBiliTextSize(int rawSize) {
    if (rawSize <= BILI_SIZE_SMALL) {
      return SMALL_SP;
    }
    if (rawSize >= BILI_SIZE_LARGE) {
      return LARGE_SP;
    }
    return DEFAULT_SP;
  }

  @Nullable
  static Danmaku parsePositionedDanmaku(String json, long timeMs, int color, float textSizeSp) {
    try {
      JSONArray values = new JSONArray(json);
      if (values.length() <= BILI_POSITION_TEXT_INDEX) {
        return null;
      }
      String text = values.optString(BILI_POSITION_TEXT_INDEX, "");
      if (text.isEmpty()) {
        return null;
      }
      float x =
          normalizeBiliCoordinate(
              values.optDouble(BILI_POSITION_X_INDEX, BILI_PLAYER_WIDTH / 2), BILI_PLAYER_WIDTH);
      float y =
          normalizeBiliCoordinate(
              values.optDouble(BILI_POSITION_Y_INDEX, BILI_PLAYER_HEIGHT / 2), BILI_PLAYER_HEIGHT);
      double rawDuration = values.optDouble(BILI_POSITION_DURATION_INDEX, 0);
      long durationMs = rawDuration > 0 ? (long) (rawDuration * 1000) : 0;
      return new Danmaku(
          text, timeMs, x, y, color, textSizeSp, durationMs, Danmaku.POOL_SPECIAL, "", 0);
    } catch (JSONException e) {
      return null;
    }
  }

  private static float normalizeBiliCoordinate(double coordinate, double playerSize) {
    return (float) (coordinate >= 0.0 && coordinate <= 1.0 ? coordinate : coordinate / playerSize);
  }

  static int parseHexColor(@Nullable String hex, int defaultColor) {
    if (hex == null || hex.isEmpty()) {
      return defaultColor;
    }
    String normalizedHex = hex.trim();
    if (normalizedHex.startsWith("#")) {
      normalizedHex = normalizedHex.substring(1);
    }
    if (normalizedHex.length() != 6) {
      return defaultColor;
    }
    try {
      return 0xFF000000 | Integer.parseInt(normalizedHex, 16);
    } catch (NumberFormatException e) {
      return defaultColor;
    }
  }

  @Nullable
  static int[] parseHexColorStrings(@Nullable String[] hexStrings) {
    if (hexStrings == null || hexStrings.length < 2) {
      return null;
    }
    int[] colors = new int[hexStrings.length];
    int validCount = 0;
    for (String hex : hexStrings) {
      int parsed = parseHexColor(hex, 0);
      if (parsed != 0) {
        colors[validCount++] = parsed;
      }
    }
    return validCount >= 2 ? Arrays.copyOf(colors, validCount) : null;
  }

  @Nullable
  static int[] parseGradientHexColors(@Nullable JSONArray array) {
    if (array == null || array.length() < 2) {
      return null;
    }
    String[] hexStrings = new String[array.length()];
    for (int i = 0; i < array.length(); i++) {
      hexStrings[i] = array.optString(i, "");
    }
    return parseHexColorStrings(hexStrings);
  }

  @Nullable
  static int[] parseGradientDecimalColors(@Nullable JSONArray array) {
    if (array == null || array.length() < 2) {
      return null;
    }
    int[] colors = new int[array.length()];
    int validCount = 0;
    for (int i = 0; i < array.length(); i++) {
      try {
        long colorValue = Math.round(array.getDouble(i));
        colors[validCount++] = 0xFF000000 | (int) (colorValue & 0xFFFFFF);
      } catch (JSONException ignored) {
      }
    }
    return validCount >= 2 ? Arrays.copyOf(colors, validCount) : null;
  }

  static String readToString(InputStream inputStream) throws IOException {
    return Util.fromUtf8Bytes(ByteStreams.toByteArray(inputStream));
  }
}
