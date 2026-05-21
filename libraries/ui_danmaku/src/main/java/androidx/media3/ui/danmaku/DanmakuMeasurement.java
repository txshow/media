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

import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.util.TypedValue;

/** Immutable text-measurement settings captured for one background job. */
final class DanmakuMeasurement {

  final int poolGeneration;
  final int configGeneration;
  private final float defaultSizePx;
  private final float textScale;
  private final DisplayMetrics displayMetrics;
  private final Typeface typeface;
  private final boolean bold;

  DanmakuMeasurement(
      int poolGeneration,
      int configGeneration,
      float defaultSizePx,
      float textScale,
      DisplayMetrics displayMetrics,
      Typeface typeface,
      boolean bold) {
    this.poolGeneration = poolGeneration;
    this.configGeneration = configGeneration;
    this.defaultSizePx = defaultSizePx;
    this.textScale = textScale;
    this.displayMetrics = new DisplayMetrics();
    this.displayMetrics.setTo(displayMetrics);
    this.typeface = typeface;
    this.bold = bold;
  }

  Paint createPaint() {
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    paint.setTypeface(typeface);
    paint.setFakeBoldText(bold);
    return paint;
  }

  float textSizePx(DanmakuRenderItem item) {
    return item.textSizeSp > 0
        ? TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, item.textSizeSp, displayMetrics)
            * textScale
        : defaultSizePx;
  }

  DanmakuMeasurement withPoolGeneration(int poolGeneration) {
    return new DanmakuMeasurement(
        poolGeneration, configGeneration, defaultSizePx, textScale, displayMetrics, typeface, bold);
  }
}
