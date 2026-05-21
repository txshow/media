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
package androidx.media3.ui.danmaku;

import android.graphics.Shader;
import androidx.annotation.Nullable;

/** View-owned mutable rendering state copied from a danmaku source entry. */
final class DanmakuRenderItem {

  final String text;
  final long timeMs;
  final @Danmaku.Type int type;
  final int color;
  final float textSizeSp;
  final int pool;
  final float x;
  final float y;
  final long durationMs;
  final int textHash;
  final boolean forceGeneratedWhite;
  @Nullable final int[] sourceGradientColors;
  float measuredWidth;
  int trackIndex = -1;
  int trackSpan = 1;
  long activatedTimeMs = -1;
  boolean active;
  @Nullable Shader gradientShader;
  float gradientShaderWidth;
  int gradientShaderColorMode = -1;
  int generatedColor;
  boolean generatedColorResolved;

  DanmakuRenderItem(Danmaku danmaku) {
    text = danmaku.text;
    timeMs = danmaku.timeMs;
    type = danmaku.type;
    color = danmaku.color;
    textSizeSp = danmaku.textSizeSp;
    pool = danmaku.pool;
    x = danmaku.x;
    y = danmaku.y;
    durationMs = danmaku.durationMs;
    textHash = text.hashCode();
    forceGeneratedWhite = Math.abs(textHash) % 5 == 0;
    sourceGradientColors = danmaku.getSourceGradientColors();
  }
}
