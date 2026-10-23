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

import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;

/**
 * Represents the subtitle-transcript state associated with a single {@link MediaItem}.
 *
 * <p>This is an opaque handle produced by {@link SubtitleTranscriptSession#forMediaItem} and
 * consumed by {@link SubtitleTranscriptParserFactory} and {@code
 * androidx.media3.exoplayer.libass.LibassPlaybackSession#createMediaComponents}.
 *
 * <p>The base implementation is a no-op empty transcript. Subclasses may attach parsed cues,
 * timing, or other content as needed.
 */
@UnstableApi
public abstract class SubtitleTranscript {

  /**
   * Returns whether this transcript has any usable content. The default implementation returns
   * {@code false}.
   */
  public boolean isEmpty() {
    return true;
  }

  /** Releases any resources held by this transcript. The default implementation is a no-op. */
  public void release() {
    // Default no-op.
  }
}
