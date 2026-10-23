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
package androidx.media3.common;

import androidx.media3.common.util.UnstableApi;

/**
 * The current state of the disc (DVD/Blu-ray/ISO) menu of a {@link Player}.
 *
 * <p>This is an immutable-ish state snapshot held as public fields so that player implementations
 * can publish updated instances whenever the state changes.
 */
@UnstableApi
public final class DiscMenuState {

  /** The disc menu is available for the current media. */
  public static final int AVAILABILITY_AVAILABLE = 1;

  /** The disc menu is idle (closed). */
  public static final int PHASE_IDLE = 0;

  /** The disc menu is open and interactive. */
  public static final int PHASE_ACTIVE = 1;

  /** The disc menu is in the process of opening. */
  public static final int PHASE_OPENING = 2;

  /** A state representing that no disc menu is available. */
  public static final DiscMenuState EMPTY = new DiscMenuState(0, PHASE_IDLE, false);

  /** The availability of the disc menu; {@link #AVAILABILITY_AVAILABLE} if available. */
  public int availability;

  /** The current phase of the disc menu; one of the {@code PHASE_*} constants. */
  public int phase;

  /** Whether disc-navigation playback is currently ongoing. */
  public boolean navigationPlaybackOngoing;

  /** Creates a new state. */
  public DiscMenuState(int availability, int phase, boolean navigationPlaybackOngoing) {
    this.availability = availability;
    this.phase = phase;
    this.navigationPlaybackOngoing = navigationPlaybackOngoing;
  }
}
