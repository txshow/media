/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.common.text;

import android.os.Bundle;
import android.text.TextPaint;
import android.text.style.MetricAffectingSpan;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;

/** A span that adds space between characters in pixels or em. */
@UnstableApi
public final class LetterSpacingSpan extends MetricAffectingSpan {

  private static final String FIELD_LETTER_SPACING_PIXELS = Util.intToStringMaxRadix(0);
  private static final String FIELD_LETTER_SPACING_EM = Util.intToStringMaxRadix(1);

  /** The extra spacing between characters in pixels, or {@link Float#NaN} if specified in em. */
  public final float letterSpacingPixels;

  private final float letterSpacingEm;

  public LetterSpacingSpan(float letterSpacingPixels) {
    this(letterSpacingPixels, Float.NaN);
  }

  private LetterSpacingSpan(float letterSpacingPixels, float letterSpacingEm) {
    this.letterSpacingPixels = letterSpacingPixels;
    this.letterSpacingEm = letterSpacingEm;
  }

  /** Creates a span with extra spacing measured in em. */
  public static LetterSpacingSpan createFromEm(float letterSpacingEm) {
    return new LetterSpacingSpan(Float.NaN, letterSpacingEm);
  }

  @Override
  public void updateDrawState(TextPaint textPaint) {
    apply(textPaint);
  }

  @Override
  public void updateMeasureState(TextPaint textPaint) {
    apply(textPaint);
  }

  public Bundle toBundle() {
    Bundle bundle = new Bundle();
    if (Float.isNaN(letterSpacingEm)) {
      bundle.putFloat(FIELD_LETTER_SPACING_PIXELS, letterSpacingPixels);
    } else {
      bundle.putFloat(FIELD_LETTER_SPACING_EM, letterSpacingEm);
    }
    return bundle;
  }

  public static LetterSpacingSpan fromBundle(Bundle bundle) {
    return bundle.containsKey(FIELD_LETTER_SPACING_EM)
        ? createFromEm(bundle.getFloat(FIELD_LETTER_SPACING_EM))
        : new LetterSpacingSpan(bundle.getFloat(FIELD_LETTER_SPACING_PIXELS));
  }

  private void apply(TextPaint textPaint) {
    if (!Float.isNaN(letterSpacingEm)) {
      textPaint.setLetterSpacing(letterSpacingEm);
      return;
    }
    float textSize = textPaint.getTextSize();
    if (textSize > 0) {
      textPaint.setLetterSpacing(letterSpacingPixels / textSize);
    }
  }
}
