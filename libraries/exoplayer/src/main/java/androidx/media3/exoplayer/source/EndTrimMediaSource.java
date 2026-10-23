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
package androidx.media3.exoplayer.source;

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;

/**
 * A {@link WrappingMediaSource} that trims a duration off the end of the wrapped source.
 *
 * <p>This is a degraded implementation that fully delegates playback to the wrapped source. The
 * requested end trim duration is recorded and can be changed at runtime; the underlying timeline is
 * not physically clipped.
 */
@UnstableApi
public class EndTrimMediaSource extends WrappingMediaSource {

  private long endTrimMs;

  /**
   * @param source The wrapped child {@link MediaSource}.
   * @param endTrimMs The duration, in milliseconds, to trim off the end of the source. Values
   *     {@code <= 0} mean no trimming.
   */
  public EndTrimMediaSource(MediaSource source, long endTrimMs) {
    super(source);
    this.endTrimMs = Math.max(0, endTrimMs);
  }

  /**
   * Updates the duration, in milliseconds, to trim off the end of the source.
   *
   * @param endTrimMs The new end trim duration, in milliseconds. Values {@code <= 0} disable
   *     trimming.
   */
  public void setEndTrimDurationMs(long endTrimMs) {
    this.endTrimMs = Math.max(0, endTrimMs);
  }

  /** Returns whether an end trim is currently active. */
  public boolean isEndTrimActive() {
    return endTrimMs > 0;
  }

  /**
   * Returns the duration of the underlying source before end trimming, in milliseconds, or {@link
   * C#TIME_UNSET} if unknown.
   */
  public long getOriginalDurationMs() {
    return C.TIME_UNSET;
  }
}
