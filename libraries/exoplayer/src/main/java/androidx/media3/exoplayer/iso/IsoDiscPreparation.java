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
package androidx.media3.exoplayer.iso;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.source.iso.IsoMediaSource;

/**
 * Lightweight, non-blocking checks that determine whether a {@link MediaItem} refers to an ISO
 * 9660 / UDF disc image (Blu-ray BDMV, DVD-Video or SACD) rather than a regular media file.
 */
@UnstableApi
public final class IsoDiscPreparation {

  private static final String ISO_FILE_EXTENSION = ".iso";

  private IsoDiscPreparation() {}

  /**
   * Returns whether the given {@link MediaItem} refers to an ISO/BDMV/DVD/SACD disc image.
   *
   * <p>The check is purely URI-based (it does not open the file): a disc image is recognised when
   * it carries an {@code edition=N} URI fragment (as produced by {@code IsoUri.withEditionIndex})
   * or when the last path segment has the {@code .iso} extension.
   */
  public static boolean isIso(MediaItem mediaItem) {
    if (mediaItem.localConfiguration == null) {
      return false;
    }
    Uri uri = mediaItem.localConfiguration.uri;
    if (IsoMediaSource.parseEditionIndex(uri) != C.INDEX_UNSET) {
      return true;
    }
    String lastPathSegment = uri.getLastPathSegment();
    return lastPathSegment != null && lastPathSegment.toLowerCase().endsWith(ISO_FILE_EXTENSION);
  }
}
