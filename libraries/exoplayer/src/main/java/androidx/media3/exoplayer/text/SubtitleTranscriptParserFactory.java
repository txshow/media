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
package androidx.media3.exoplayer.text;

import androidx.media3.common.Format;
import androidx.media3.common.Format.CueReplacementBehavior;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.text.SubtitleParser;

/**
 * A {@link SubtitleParser.Factory} that delegates to a {@link SubtitleTranscript}.
 *
 * <p>Downgrade implementation: supports no formats, so ExoPlayer falls back to its built-in
 * subtitle extraction. {@link #create(Format)} throws {@link IllegalStateException} if called for
 * a format that the factory reports as unsupported (matching the behavior of {@link
 * SubtitleParser.Factory#UNSUPPORTED}).
 */
@UnstableApi
public final class SubtitleTranscriptParserFactory implements SubtitleParser.Factory {

  private final SubtitleTranscript transcript;

  /**
   * Creates a new factory bound to the given {@link SubtitleTranscript}.
   *
   * @param transcript The transcript.
   */
  public SubtitleTranscriptParserFactory(SubtitleTranscript transcript) {
    this.transcript = transcript;
  }

  /** Returns the {@link SubtitleTranscript} this factory is bound to. */
  public SubtitleTranscript getTranscript() {
    return transcript;
  }

  @Override
  public boolean supportsFormat(Format format) {
    // Downgrade: do not override ExoPlayer's built-in subtitle parsing.
    return false;
  }

  @Override
  public @CueReplacementBehavior int getCueReplacementBehavior(Format format) {
    return Format.CUE_REPLACEMENT_BEHAVIOR_MERGE;
  }

  @Override
  public SubtitleParser create(Format format) {
    throw new IllegalStateException(
        "SubtitleTranscriptParserFactory doesn't support any formats (downgrade implementation).");
  }
}
