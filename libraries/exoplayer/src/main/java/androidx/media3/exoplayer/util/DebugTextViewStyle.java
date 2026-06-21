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
package androidx.media3.exoplayer.util;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.Nullable;

final class DebugTextViewStyle {

  private static final float REFERENCE_HEIGHT = 720.0f;
  private static final float FONT_SIZE_PX = 14.0f;
  private static final float BORDER_SIZE_PX = 1.155f;
  private static final float PADDING_DP = 8.0f;
  private static final String[] LABELS = {
    "File:",
    "Title:",
    "Duration:",
    "Edition:",
    "Chapter:",
    "Size:",
    "Format/Protocol:",
    "Buffered:",
    "Display:",
    "Resolution:",
    "Refresh Rate:",
    "Dropped Frames:",
    "Video:",
    "Decoder:",
    "HW:",
    "Buffers:",
    "Offset:",
    "Frame Rate:",
    "Encoded Format:",
    "Bit Depth:",
    "Color:",
    "Bitrate:",
    "Audio:",
    "Output:",
    "Channels:",
    "Sample Rate:",
    "Volume:",
    "Device Volume:",
    "Audio Delay:",
    "Errors:",
    "Player Error:",
    "Load Error:",
    "Audio Error:",
    "Video Error:"
  };

  static void apply(TextView textView, @Nullable View referenceView) {
    float scale = getScale(textView, referenceView);
    int padding = dpToPx(textView, PADDING_DP);
    if (textView.getCurrentTextColor() != Color.WHITE) {
      textView.setTextColor(Color.WHITE);
    }
    textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, FONT_SIZE_PX * scale);
    textView.setTypeface(Typeface.DEFAULT);
    textView.setShadowLayer(BORDER_SIZE_PX * scale, 0.0f, 0.0f, Color.BLACK);
    textView.setLineSpacing(0.0f, 1.0f);
    textView.setIncludeFontPadding(false);
    textView.setPadding(padding, padding, padding, padding);
    if (referenceView != null && referenceView.getWidth() > 0 && referenceView.getHeight() > 0) {
      if (textView.getMaxWidth() != referenceView.getWidth()) {
        textView.setMaxWidth(referenceView.getWidth());
      }
      if (textView.getMaxHeight() != referenceView.getHeight()) {
        textView.setMaxHeight(referenceView.getHeight());
      }
    }
  }

  static CharSequence style(String text) {
    SpannableString styledText = new SpannableString(text);
    for (String label : LABELS) {
      int labelStart = text.indexOf(label);
      while (labelStart != -1) {
        if (isLabelBoundary(text, labelStart)) {
          styledText.setSpan(
              new StyleSpan(Typeface.BOLD),
              labelStart,
              labelStart + label.length(),
              Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        labelStart = text.indexOf(label, labelStart + label.length());
      }
    }
    return styledText;
  }

  private static boolean isLabelBoundary(String text, int labelStart) {
    if (labelStart == 0 || text.charAt(labelStart - 1) == '\n') {
      return true;
    }
    if (text.charAt(labelStart - 1) != ' ') {
      return false;
    }
    int precedingIndex = labelStart - 2;
    return precedingIndex < 0
        || text.charAt(precedingIndex) == ' '
        || text.charAt(precedingIndex) == '\n';
  }

  private static float getScale(TextView textView, @Nullable View referenceView) {
    int referenceHeight = referenceView == null ? 0 : referenceView.getHeight();
    if (referenceHeight <= 0) {
      referenceHeight = textView.getResources().getDisplayMetrics().heightPixels;
    }
    if (referenceHeight <= 0) {
      return 1.0f;
    }
    return referenceHeight / REFERENCE_HEIGHT;
  }

  private static int dpToPx(View view, float dp) {
    return Math.round(
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, view.getResources().getDisplayMetrics()));
  }
}
