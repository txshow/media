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
package androidx.media3.mpvplayer;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaLibraryInfo;

/** Configures and queries the underlying mpv native libraries. */
public final class MpvLibrary {

  /** Loads the mpv native runtime. */
  public interface LibraryLoader {

    /** Loads the runtime and returns whether it became available. */
    boolean load();
  }

  private static final androidx.media3.common.util.LibraryLoader LOADER =
      new androidx.media3.common.util.LibraryLoader("mpv", "player") {
        @Override
        protected void loadLibrary(String name) {
          System.loadLibrary(name);
        }
      };

  @Nullable private static LibraryLoader libraryLoader;

  static {
    MediaLibraryInfo.registerModule("media3.mpvplayer");
  }

  /**
   * Overrides the names of the mpv native libraries.
   *
   * <p>If an application calls this method, it must do so before calling any other method defined
   * by this class and before instantiating an {@link MpvPlayer}.
   *
   * @param libraries The names of the mpv native libraries.
   */
  public static void setLibraries(String... libraries) {
    LOADER.setLibraries(libraries);
  }

  /**
   * Sets the callback used to load the mpv native runtime, for example from an on-demand module.
   *
   * <p>The loader is stored for future use; when set, {@link #isAvailable()} delegates to it and
   * returns {@code true} on success, mirroring the official FongMi 5.8.0 {@code MpvLibrary.a()}.
   */
  public static void setLibraryLoader(@Nullable LibraryLoader loader) {
    libraryLoader = loader;
  }

  /** Returns whether the underlying native libraries are available, loading them if necessary. */
  public static boolean isAvailable() {
    LibraryLoader loader = libraryLoader;
    if (loader != null) {
      loader.load();
      return true;
    }
    return LOADER.isAvailable();
  }
}
