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
package androidx.media3.common;

import androidx.media3.common.util.UnstableApi;

/**
 * Controls a disc (DVD/Blu-ray/ISO) navigation menu, for players that support such interactive
 * menus.
 *
 * <p>A controller is obtained from {@link Player#getDiscMenuController()}. Players that do not
 * support disc menus return {@code null} from that method.
 */
@UnstableApi
public interface DiscMenuController {

  /** The result of attempting to open a disc menu. */
  @UnstableApi
  enum OpenResult {
    /** The menu was opened successfully. */
    SUCCESS,
    /** The runtime required to drive the menu is missing on this device. */
    RUNTIME_MISSING,
    /** The current media does not provide a menu. */
    UNAVAILABLE,
    /** Opening the menu timed out. */
    TIMED_OUT
  }

  /** Callback notified with the result of an {@link #open(String, Callback)} request. */
  @UnstableApi
  interface Callback {
    /**
     * Called when the open request completed.
     *
     * @param result The {@link OpenResult} describing the outcome.
     */
    void onResult(OpenResult result);
  }

  /**
   * Opens the disc menu, optionally starting from the given menu action.
   *
   * @param action The menu action to invoke, or an empty string to open the root menu.
   * @param callback A {@link Callback} notified with the {@link OpenResult}.
   * @return Whether the open request was accepted.
   */
  boolean open(String action, Callback callback);

  /**
   * Sends a menu navigation action (e.g. up/down/select/back).
   *
   * @param action The action to send.
   * @return Whether the action was accepted.
   */
  boolean sendAction(String action);

  /** Returns whether the disc menu is currently active (open and interactive). */
  boolean isActive();

  /** Returns whether the menu accepts pointer/touch input. */
  boolean supportsPointer();

  /** Returns whether the player is currently in disc-navigation playback mode. */
  boolean isNavigationPlayback();

  /** Closes the disc menu and releases any associated resources. */
  void close();
}
