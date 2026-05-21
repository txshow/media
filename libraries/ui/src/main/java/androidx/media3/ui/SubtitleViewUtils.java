/*
 * Copyright (C) 2020 The Android Open Source Project
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
package androidx.media3.ui;

import static com.google.common.base.Preconditions.checkNotNull;

import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.RelativeSizeSpan;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.LanguageFeatureSpan;
import com.google.common.base.Predicate;

/** Utility class for subtitle layout logic. */
/* package */ final class SubtitleViewUtils {

  /**
   * Returns the text size in px, derived from {@code textSize} and {@code textSizeType}.
   *
   * <p>Returns {@link Cue#DIMEN_UNSET} if {@code textSize == Cue.DIMEN_UNSET} or {@code
   * textSizeType == Cue.TYPE_UNSET}.
   */
  public static float resolveTextSize(
      @Cue.TextSizeType int textSizeType,
      float textSize,
      int rawViewHeight,
      int viewHeightMinusPadding) {
    if (textSize == Cue.DIMEN_UNSET) {
      return Cue.DIMEN_UNSET;
    }
    switch (textSizeType) {
      case Cue.TEXT_SIZE_TYPE_ABSOLUTE:
        return textSize;
      case Cue.TEXT_SIZE_TYPE_FRACTIONAL:
        return textSize * viewHeightMinusPadding;
      case Cue.TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING:
        return textSize * rawViewHeight;
      case Cue.TYPE_UNSET:
      default:
        return Cue.DIMEN_UNSET;
    }
  }

  /** Scales a bitmap cue around its visual center. */
  public static Cue scaleBitmapCue(Cue cue, float scale) {
    if (cue.bitmap == null) {
      return cue;
    }
    boolean hasWidth = cue.size != Cue.DIMEN_UNSET;
    boolean hasHeight = cue.bitmapHeight != Cue.DIMEN_UNSET;
    if (!hasWidth && !hasHeight) {
      return cue;
    }
    Cue.Builder builder = cue.buildUpon();
    if (hasWidth) {
      float scaledSize = cue.size * scale;
      builder.setSize(scaledSize);
      if (cue.position != Cue.DIMEN_UNSET) {
        float offset = (cue.size - scaledSize) / 2.0f;
        if (cue.positionAnchor == Cue.ANCHOR_TYPE_START) {
          builder.setPosition(cue.position + offset);
        } else if (cue.positionAnchor == Cue.ANCHOR_TYPE_END) {
          builder.setPosition(cue.position - offset);
        }
      }
    }
    if (hasHeight) {
      float scaledHeight = cue.bitmapHeight * scale;
      builder.setBitmapHeight(scaledHeight);
      if (cue.line != Cue.DIMEN_UNSET && cue.lineType == Cue.LINE_TYPE_FRACTION) {
        float offset = (cue.bitmapHeight - scaledHeight) / 2.0f;
        if (cue.lineAnchor == Cue.ANCHOR_TYPE_START) {
          builder.setLine(cue.line + offset, cue.lineType);
        } else if (cue.lineAnchor == Cue.ANCHOR_TYPE_END) {
          builder.setLine(cue.line - offset, cue.lineType);
        }
      }
    }
    return builder.build();
  }

  /** Removes all styling information from {@code cue}. */
  public static void removeAllEmbeddedStyling(Cue.Builder cue) {
    cue.clearWindowColor();
    if (cue.getText() instanceof Spanned) {
      if (!(cue.getText() instanceof Spannable)) {
        cue.setText(SpannableString.valueOf(cue.getText()));
      }
      removeSpansIf(
          (Spannable) checkNotNull(cue.getText()), span -> !(span instanceof LanguageFeatureSpan));
    }
    removeEmbeddedFontSizes(cue);
  }

  /**
   * Removes all font size information from {@code cue}.
   *
   * <p>This involves:
   *
   * <ul>
   *   <li>Clearing {@link Cue.Builder#setTextSize(float, int)}.
   *   <li>Removing all {@link AbsoluteSizeSpan} and {@link RelativeSizeSpan} spans from {@link
   *       Cue#text}.
   * </ul>
   */
  public static void removeEmbeddedFontSizes(Cue.Builder cue) {
    cue.setTextSize(Cue.DIMEN_UNSET, Cue.TYPE_UNSET);
    if (cue.getText() instanceof Spanned) {
      if (!(cue.getText() instanceof Spannable)) {
        cue.setText(SpannableString.valueOf(cue.getText()));
      }
      removeSpansIf(
          (Spannable) checkNotNull(cue.getText()),
          span -> span instanceof AbsoluteSizeSpan || span instanceof RelativeSizeSpan);
    }
  }

  /** Scales all embedded absolute font sizes in {@code cue}. */
  public static void scaleEmbeddedFontSizes(Cue.Builder cue, float scale) {
    if (cue.getTextSize() != Cue.DIMEN_UNSET) {
      cue.setTextSize(cue.getTextSize() * scale, cue.getTextSizeType());
    }
    CharSequence text = cue.getText();
    if (!(text instanceof Spanned)) {
      return;
    }
    Spannable spannable = new SpannableString(text);
    cue.setText(spannable);
    AbsoluteSizeSpan[] spans = spannable.getSpans(0, spannable.length(), AbsoluteSizeSpan.class);
    for (AbsoluteSizeSpan span : spans) {
      int start = spannable.getSpanStart(span);
      int end = spannable.getSpanEnd(span);
      int flags = spannable.getSpanFlags(span);
      spannable.removeSpan(span);
      spannable.setSpan(
          new AbsoluteSizeSpan(Math.max(1, Math.round(span.getSize() * scale)), span.getDip()),
          start,
          end,
          flags);
    }
  }

  private static void removeSpansIf(Spannable spannable, Predicate<Object> removeFilter) {
    Object[] spans = spannable.getSpans(0, spannable.length(), Object.class);
    for (Object span : spans) {
      if (removeFilter.apply(span)) {
        spannable.removeSpan(span);
      }
    }
  }

  private SubtitleViewUtils() {}
}
