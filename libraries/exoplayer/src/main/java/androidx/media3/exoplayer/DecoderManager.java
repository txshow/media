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

import android.content.Context;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.trackselection.DecodeTrackSelector;

/**
 * Holds the currently requested audio and video {@link DecoderMode} and bridges them to a {@link
 * DecodeTrackSelector} that can switch renderer preferences without rebuilding the player.
 */
@UnstableApi
public class DecoderManager {

  private DecoderMode audioMode;
  private DecoderMode videoMode;
  @Nullable private ExoPlayer player;
  @Nullable private DecodeTrackSelector trackSelector;

  /**
   * @param audioMode The initial {@link DecoderMode} for audio tracks.
   * @param videoMode The initial {@link DecoderMode} for video tracks.
   */
  public DecoderManager(DecoderMode audioMode, DecoderMode videoMode) {
    this.audioMode = audioMode;
    this.videoMode = videoMode;
  }

  /** Returns the currently requested audio {@link DecoderMode}. */
  public DecoderMode getAudioMode() {
    return audioMode;
  }

  /** Returns the currently requested video {@link DecoderMode}. */
  public DecoderMode getVideoMode() {
    return videoMode;
  }

  /** Requests the given {@link DecoderMode} for audio tracks. */
  public void selectAudioDecoder(DecoderMode mode) {
    this.audioMode = mode;
    applyDecoderPreferences();
  }

  /** Requests the given {@link DecoderMode} for video tracks. */
  public void selectVideoDecoder(DecoderMode mode) {
    this.videoMode = mode;
    applyDecoderPreferences();
  }

  /** Updates both the audio and video {@link DecoderMode}s at once. */
  public void setDecoderModes(DecoderMode audioMode, DecoderMode videoMode) {
    this.audioMode = audioMode;
    this.videoMode = videoMode;
    applyDecoderPreferences();
  }

  /**
   * Attaches this manager to the given player. The manager keeps a weak reference so that mode
   * changes can be propagated while the player is alive.
   */
  public void attach(ExoPlayer player) {
    this.player = player;
  }

  /** Detaches this manager from any previously attached player. */
  public void detach() {
    this.player = null;
  }

  /**
   * Creates a {@link DecodeTrackSelector} that honours the current audio and video {@link
   * DecoderMode}s. Later mode changes are propagated to the returned selector.
   */
  public DecodeTrackSelector createTrackSelector(Context context) {
    trackSelector = new DecodeTrackSelector(context);
    applyDecoderPreferences();
    return trackSelector;
  }

  private void applyDecoderPreferences() {
    DecodeTrackSelector selector = trackSelector;
    if (selector != null) {
      selector.setRendererDecodePreferences(
          toDecodeMode(audioMode), toDecodeMode(videoMode));
    }
  }

  private static @C.DecodeMode int toDecodeMode(DecoderMode mode) {
    switch (mode) {
      case SOFTWARE:
        return C.DECODE_SOFTWARE_CODEC;
      case FFMPEG:
        return C.DECODE_SOFTWARE;
      case AUTO:
        return C.DECODE_HARDWARE;
      case HARDWARE:
      default:
        return C.DECODE_HARDWARE;
    }
  }
}
