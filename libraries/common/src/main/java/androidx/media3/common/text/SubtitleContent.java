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
package androidx.media3.common.text;

import androidx.media3.common.util.UnstableApi;
import java.util.List;

/**
 * The parsed content of a subtitle track, used by players that can load and render external or
 * advanced subtitle content independently of the standard text renderer.
 *
 * <p>This is a marker type that also carries the decoded list of timed {@link Entry} items. Use
 * {@link #UNSUPPORTED} to signal that subtitle content is not available for the requested
 * selection.
 */
@UnstableApi
public final class SubtitleContent {

  /** A sentinel value indicating that subtitle content is not supported/available. */
  public static final SubtitleContent UNSUPPORTED = new SubtitleContent(List.of(), State.UNSUPPORTED);

  /** A placeholder content while subtitle content is being loaded. */
  public static final SubtitleContent LOADING = new SubtitleContent(List.of(), State.LOADING);

  /** The loading state of the subtitle content. */
  @UnstableApi
  public enum State {
    /** No content has been requested yet. */
    IDLE,
    /** Content is being loaded. */
    LOADING,
    /** Content failed to load. */
    ERROR,
    /** Content is not supported. */
    UNSUPPORTED,
    /** Only part of the content could be loaded. */
    PARTIAL,
    /** Content was truncated. */
    TRUNCATED,
    /** Content loaded completely. */
    COMPLETE
  }

  /** A single timed subtitle entry. */
  @UnstableApi
  public static final class Entry {
    /** Whether this entry is an image (rather than plain text). */
    public final boolean image;

    /** The text of the entry. Empty when {@link #image} is true. */
    public final String text;

    /** The start time of the entry, in microseconds. */
    public final long startTimeUs;

    /** The end time of the entry, in microseconds. */
    public final long endTimeUs;

    /** Creates a new entry. */
    public Entry(boolean image, String text, long startTimeUs, long endTimeUs) {
      this.image = image;
      this.text = text;
      this.startTimeUs = startTimeUs;
      this.endTimeUs = endTimeUs;
    }
  }

  /** The decoded timed entries of this content. Never null, but may be empty. */
  public final List<Entry> entries;

  /** The loading state of this content. */
  public final State state;

  private SubtitleContent(List<Entry> entries, State state) {
    this.entries = entries;
    this.state = state;
  }

  /** Creates a content instance with the given entries and state. */
  public static SubtitleContent of(List<Entry> entries, State state) {
    return new SubtitleContent(entries, state);
  }
}
