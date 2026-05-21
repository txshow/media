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

/** Caches the last color and alpha applied to a paint. */
final class PaintColorState {

  private int color;
  private int alpha = -1;

  void apply(Paint paint, int color, int alpha) {
    if (this.color != color) {
      paint.setColor(color);
      this.color = color;
      this.alpha = -1;
    }
    if (this.alpha != alpha) {
      paint.setAlpha(alpha);
      this.alpha = alpha;
    }
  }

  void reset() {
    color = 0;
    alpha = -1;
  }
}
