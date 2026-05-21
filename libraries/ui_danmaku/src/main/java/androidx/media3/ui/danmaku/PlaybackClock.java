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

import android.os.SystemClock;

/** Tracks the playback position used by a {@link DanmakuView}. */
final class PlaybackClock {

  boolean started;
  boolean paused;
  long basePositionMs;
  long baseElapsedRealtimeMs;
  float playbackSpeed = 1f;

  void rebase(long positionMs) {
    basePositionMs = positionMs;
    baseElapsedRealtimeMs = SystemClock.elapsedRealtime();
  }

  void unpause() {
    baseElapsedRealtimeMs = SystemClock.elapsedRealtime();
    paused = false;
  }

  long getPositionMs() {
    if (paused || !started) {
      return basePositionMs;
    }
    long elapsed = SystemClock.elapsedRealtime() - baseElapsedRealtimeMs;
    return basePositionMs + (long) (elapsed * playbackSpeed);
  }
}
