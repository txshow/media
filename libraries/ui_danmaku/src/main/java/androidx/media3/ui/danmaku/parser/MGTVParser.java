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
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.danmaku.Danmaku;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Parses MGTV JSON danmaku data. */
@UnstableApi
public final class MGTVParser implements Parser {

  /** Shared instance. */
  public static final MGTVParser INSTANCE = new MGTVParser();

  private MGTVParser() {}

  @Nullable
  private static Danmaku parseItem(JSONObject item) {
    try {
      long timeMs = (long) item.getDouble("time");
      String text = item.optString("content", "");
      if (text.isEmpty()) {
        return null;
      }
      int color = 0xFFFFFFFF;
      JSONObject v2Color = item.optJSONObject("v2_color");
      if (v2Color != null) {
        JSONObject colorLeft = v2Color.optJSONObject("color_left");
        JSONObject colorRight = v2Color.optJSONObject("color_right");
        if (colorLeft != null && colorRight != null) {
          int leftPacked = packRgb(colorLeft);
          int rightPacked = packRgb(colorRight);
          color = 0xFF000000 | averageRgb(leftPacked, rightPacked);
        } else if (colorLeft != null) {
          color = 0xFF000000 | packRgb(colorLeft);
        }
      }
      return new Danmaku(text, timeMs, Danmaku.TYPE_SCROLL, color, 0f);
    } catch (JSONException e) {
      return null;
    }
  }

  private static int packRgb(JSONObject rgb) {
    int r = (int) Math.round(rgb.optDouble("r", 255));
    int g = (int) Math.round(rgb.optDouble("g", 255));
    int b = (int) Math.round(rgb.optDouble("b", 255));
    return ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
  }

  private static int averageRgb(int left, int right) {
    int r = (((left >> 16) & 0xFF) + ((right >> 16) & 0xFF) + 1) / 2;
    int g = (((left >> 8) & 0xFF) + ((right >> 8) & 0xFF) + 1) / 2;
    int b = ((left & 0xFF) + (right & 0xFF) + 1) / 2;
    return (r << 16) | (g << 8) | b;
  }

  @Override
  public boolean sniff(InputStream inputStream, int sniffLength) throws IOException {
    String header = ParserUtil.readSniffHeader(inputStream, sniffLength);
    return header.trim().startsWith("{") && header.contains("\"items\"");
  }

  @Override
  public List<Danmaku> parse(InputStream inputStream) throws IOException {
    String json = ParserUtil.readToString(inputStream);
    List<Danmaku> result = new ArrayList<>();
    try {
      JSONArray items = new JSONObject(json).getJSONObject("data").getJSONArray("items");
      for (int i = 0; i < items.length(); i++) {
        @Nullable Danmaku danmaku = parseItem(items.getJSONObject(i));
        if (danmaku != null) {
          result.add(danmaku);
        }
      }
    } catch (JSONException e) {
      throw new IOException("Failed to parse MGTV danmaku JSON", e);
    }
    Collections.sort(result, Danmaku.BY_TIME);
    return result;
  }
}
