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

import android.content.Context;
import android.os.CancellationSignal;
import androidx.media3.common.util.UnstableApi;

/**
 * Entry point for the libbluray / BDJ (Blu-ray Java) disc navigation runtime.
 *
 * <p>This is a degraded implementation: the native BDJ runtime is not bundled, so {@link #prepare}
 * is a no-op and {@link #getStatus} reports that no restart is required.
 */
@UnstableApi
public final class BdjRuntime {

  private BdjRuntime() {}

  /**
   * Prepares the BDJ runtime. Degraded: does nothing.
   *
   * @param context A {@link Context}.
   * @param signal A {@link CancellationSignal} to abort preparation.
   */
  public static void prepare(Context context, CancellationSignal signal) throws Exception {
    // Degraded no-op: no native BDJ runtime is available.
  }

  /**
   * Returns the current {@link BdjRuntimeStatus}. Degraded: always reports {@code restartRequired
   * = false}.
   */
  public static BdjRuntimeStatus getStatus(Context context) {
    return new BdjRuntimeStatus(/* restartRequired= */ false);
  }

  /** Status of the BDJ runtime. */
  public static final class BdjRuntimeStatus {

    /** Whether the host application must be restarted after a runtime change. */
    public final boolean restartRequired;

    public BdjRuntimeStatus(boolean restartRequired) {
      this.restartRequired = restartRequired;
    }
  }
}
