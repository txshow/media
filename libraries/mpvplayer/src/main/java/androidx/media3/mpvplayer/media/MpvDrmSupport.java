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
package androidx.media3.mpvplayer.media;

import androidx.media3.common.MediaItem;

/**
 * Reports whether mpv playback supports the DRM configuration of a given {@link MediaItem}.
 *
 * <p>Degraded implementation: always returns {@code false}, so any DRM-protected content is routed
 * to the exo playback engine, which is the safe default for DRM support.
 */
public final class MpvDrmSupport {

  private MpvDrmSupport() {}

  /** Returns {@code true} if mpv can play back the DRM-protected {@code mediaItem}. */
  public static boolean supports(MediaItem mediaItem) {
    return false;
  }
}
