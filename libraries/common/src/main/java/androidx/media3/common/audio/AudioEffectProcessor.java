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
package androidx.media3.common.audio;

import androidx.media3.common.util.UnstableApi;
import java.nio.ByteBuffer;

/**
 * Holds the currently applied {@link AudioEffectParameters} and presents itself as an {@link
 * AudioProcessor} so it can be plugged into the exoplayer audio sink.
 *
 * <p>This common-module implementation stores the parameters and passes audio through unchanged;
 * the actual DSP is applied by module-specific renderers (e.g. the exoplayer or mpvplayer audio
 * components).
 */
@UnstableApi
public final class AudioEffectProcessor implements AudioProcessor {

  private AudioEffectParameters parameters;
  private boolean softwareEqualizer;
  private AudioFormat outputFormat = AudioFormat.NOT_SET;
  private boolean ended;

  /** Creates a new processor with no parameters set. */
  public AudioEffectProcessor() {}

  /**
   * Stores the given parameters.
   *
   * @param parameters The parameters to apply.
   * @param softwareEqualizer Whether a software (CPU) equalizer is required.
   */
  public void setParameters(AudioEffectParameters parameters, boolean softwareEqualizer) {
    this.parameters = parameters;
    this.softwareEqualizer = softwareEqualizer;
  }

  /** Clears any stored parameters. */
  public void resetParameters() {
    this.parameters = null;
    this.softwareEqualizer = false;
  }

  /** Returns the currently stored parameters, or {@code null} if none. */
  public AudioEffectParameters getParameters() {
    return parameters;
  }

  /** Returns whether a software equalizer is required for the stored parameters. */
  public boolean isSoftwareEqualizer() {
    return softwareEqualizer;
  }

  // AudioProcessor implementation: pass-through, inactive by default.

  @Override
  public AudioFormat configure(AudioFormat inputAudioFormat) {
    outputFormat = inputAudioFormat;
    return outputFormat;
  }

  @Override
  public boolean isActive() {
    return parameters != null && parameters.hasEffect();
  }

  @Override
  public void queueInput(ByteBuffer inputBuffer) {
    // Pass-through: nothing to do, data is forwarded by the sink when inactive.
  }

  @Override
  public void queueEndOfStream() {
    ended = true;
  }

  @Override
  public ByteBuffer getOutput() {
    return EMPTY_BUFFER;
  }

  @Override
  public boolean isEnded() {
    return ended;
  }

  @Override
  public void flush(StreamMetadata streamMetadata) {
    ended = false;
  }

  @Override
  public void reset() {
    outputFormat = AudioFormat.NOT_SET;
    ended = false;
  }
}
