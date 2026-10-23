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
package androidx.media3.exoplayer.trackselection;

import android.content.Context;
import androidx.media3.common.util.UnstableApi;

/**
 * Wraps a base {@link TrackSelector.Factory} so that the resulting {@link TrackSelector} can also
 * service secondary (e.g. libass-rendered) text tracks.
 *
 * <p>Downgrade implementation: the factory simply delegates to the wrapped base factory. The
 * returned selector is the same selector the base factory produces; secondary text track handling
 * is expected to be layered on top by the player / libass controller.
 */
@UnstableApi
public final class SecondaryTextTrackSelector {

  /**
   * {@link TrackSelector.Factory} that wraps a base factory and produces a {@link
   * SecondaryTextTrackSelector}-compatible {@link TrackSelector}.
   */
  public static final class Factory implements TrackSelector.Factory {

    private final TrackSelector.Factory baseFactory;

    /**
     * Creates a new factory.
     *
     * @param baseFactory The base factory to delegate to.
     */
    public Factory(TrackSelector.Factory baseFactory) {
      this.baseFactory = baseFactory;
    }

    @Override
    public TrackSelector createTrackSelector(Context context) {
      return baseFactory.createTrackSelector(context);
    }
  }

  private SecondaryTextTrackSelector() {}
}
