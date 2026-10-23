/*
 * Copyright 2026 The Android Open Source Project
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

package androidx.media3.exoplayer.hls;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;

/**
 * Configuration for CMG (China Media Group) private streaming manifests.
 *
 * <p>This is a stub: {@link #fromMediaUri(Uri)} always returns {@code null}, so callers treat
 * every URI as a regular HLS stream. The real CMG parsing is private and not part of the open
 * source tree; returning {@code null} preserves the contract that non-null means "this URI is a
 * CMG stream and must be routed through the CMG pipeline".
 */
@UnstableApi
public final class CmgConfiguration {

  /**
   * Attempts to parse a CMG configuration from the given media URI.
   *
   * @param uri The media URI to inspect.
   * @return {@code null} in this stub, meaning the URI is not a CMG stream.
   */
  @Nullable
  public static CmgConfiguration fromMediaUri(Uri uri) {
    return null;
  }

  private CmgConfiguration() {}
}
