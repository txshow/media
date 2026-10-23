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

package androidx.media3.effect;

import androidx.annotation.Nullable;
import androidx.media3.common.Effect;
import androidx.media3.common.util.UnstableApi;

/**
 * A video {@link Effect} that applies detail tuning (sharpness, threshold, shadow lift) to every
 * frame.
 *
 * <p>Like {@link ColorToneAdjustEffect}, this is a stateful holder for a {@link VideoAdjustment}.
 * The caller supplies the adjustment via {@link #setAdjustment(VideoAdjustment)}; the concrete
 * shader wiring is owned by the frame-processing pipeline.
 */
@UnstableApi
public final class DetailAdjustEffect implements Effect {

  @Nullable private volatile VideoAdjustment adjustment;

  /** Creates a new effect with no adjustment applied. */
  public DetailAdjustEffect() {}

  /**
   * Updates the detail adjustment applied by this effect.
   *
   * @param adjustment The new adjustment. Passing {@code null} clears the previously set one.
   */
  public void setAdjustment(@Nullable VideoAdjustment adjustment) {
    this.adjustment = adjustment;
  }

  /** Returns the currently applied adjustment, or {@code null} if none has been set. */
  @Nullable
  public VideoAdjustment getAdjustment() {
    return adjustment;
  }
}
