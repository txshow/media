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

import android.net.Uri;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.text.SubtitleContent;
import androidx.media3.common.util.UnstableApi;
import java.io.IOException;

/** Loads and parses a side-loaded subtitle into a {@link SubtitleContent}. */
@UnstableApi
public interface SubtitleContentLoader {

  /**
   * Loads the subtitle referenced by {@code uri} for the given {@link MediaItem}.
   *
   * @param item The {@link MediaItem} the subtitle belongs to.
   * @param uri The subtitle {@link Uri}.
   * @param format The {@link Format} of the subtitle track.
   * @return The parsed {@link SubtitleContent}, or {@link SubtitleContent#UNSUPPORTED} if the
   *     subtitle cannot be loaded or parsed.
   * @throws IOException If loading the subtitle data fails.
   */
  SubtitleContent load(MediaItem item, Uri uri, Format format) throws IOException;
}
