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
package androidx.media3.exoplayer;

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;

/**
 * Attempts to recover from decoder initialization failures by switching the affected track from
 * hardware to software decoding, once per track.
 */
@UnstableApi
public class DecoderFallback {

  private final DecoderManager decoderManager;
  private boolean audioRetried;
  private boolean videoRetried;

  /** @param decoderManager The {@link DecoderManager} used to switch decoder modes. */
  public DecoderFallback(DecoderManager decoderManager) {
    this.decoderManager = decoderManager;
  }

  /** Clears the retry marker for the given track type. */
  public void reset(@C.TrackType int trackType) {
    if (trackType == C.TRACK_TYPE_AUDIO) {
      audioRetried = false;
    } else if (trackType == C.TRACK_TYPE_VIDEO) {
      videoRetried = false;
    }
  }

  /**
   * Attempts to recover from the given playback exception by switching the affected track to
   * software decoding.
   *
   * @param exception The playback exception that occurred.
   * @return {@code true} if the decoder mode was switched and playback can be retried, {@code
   *     false} if the error cannot be recovered by a decoder switch.
   */
  public boolean retry(ExoPlaybackException exception) {
    if (exception.type != ExoPlaybackException.TYPE_RENDERER) {
      return false;
    }
    if (!audioRetried && decoderManager.getAudioMode() == DecoderMode.HARDWARE) {
      audioRetried = true;
      decoderManager.selectAudioDecoder(DecoderMode.SOFTWARE);
      return true;
    }
    if (!videoRetried && decoderManager.getVideoMode() == DecoderMode.HARDWARE) {
      videoRetried = true;
      decoderManager.selectVideoDecoder(DecoderMode.SOFTWARE);
      return true;
    }
    return false;
  }
}
