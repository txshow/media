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

import androidx.media3.common.util.UnstableApi;

/**
 * A {@link WrappingMediaSource} that marks a child source to start playback at a fixed position.
 *
 * <p>This is a degraded implementation that fully delegates to the wrapped source. The start
 * position is recorded for reference; seeking to it is expected to be performed by the player
 * (e.g. {@code ExoPlayer#seekTo(int, long)}).
 */
@UnstableApi
public class DefaultPositionMediaSource extends WrappingMediaSource {

  private final long startPositionMs;

  /**
   * @param source The wrapped child {@link MediaSource}.
   * @param startPositionMs The position, in milliseconds, at which playback of the source should
   *     begin.
   */
  public DefaultPositionMediaSource(MediaSource source, long startPositionMs) {
    super(source);
    this.startPositionMs = startPositionMs;
  }

  /** Returns the requested start position, in milliseconds. */
  public long getStartPositionMs() {
    return startPositionMs;
  }
}
