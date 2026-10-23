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

import androidx.annotation.Nullable;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.util.UnstableApi;
import java.util.List;
import java.util.Objects;

/**
 * Describes the current subtitle track selections: the primary (main) subtitle selection, the
 * active secondary (overlay) subtitle selection, and the set of secondary candidates shown in the
 * UI.
 */
@UnstableApi
public final class SubtitleSelectionState {

  /** A state with no selections and no candidates. */
  public static final SubtitleSelectionState EMPTY =
      new SubtitleSelectionState(null, null, List.of(), null, false);

  /** The primary subtitle selection, or {@code null} if none. */
  public final @Nullable TrackSelectionOverride primarySelection;

  /** The active secondary subtitle selection, or {@code null} if none is active. */
  public final @Nullable TrackSelectionOverride activeSecondarySelection;

  /** The secondary track candidates available in the UI. Never null, but may be empty. */
  public final List<TrackSelectionOverride> secondaryCandidates;

  /** The secondary selection the user requested, or {@code null} if none. */
  public final @Nullable TrackSelectionOverride requestedSecondarySelection;

  /** Whether the secondary selection was promoted to primary. */
  public final boolean secondaryPromotedToPrimary;

  /** Creates a new state with no secondary candidates. */
  public SubtitleSelectionState(
      @Nullable TrackSelectionOverride primarySelection,
      @Nullable TrackSelectionOverride activeSecondarySelection) {
    this(primarySelection, activeSecondarySelection, List.of(), null, false);
  }

  /** Creates a new state. */
  public SubtitleSelectionState(
      @Nullable TrackSelectionOverride primarySelection,
      @Nullable TrackSelectionOverride activeSecondarySelection,
      List<TrackSelectionOverride> secondaryCandidates,
      @Nullable TrackSelectionOverride requestedSecondarySelection,
      boolean secondaryPromotedToPrimary) {
    this.primarySelection = primarySelection;
    this.activeSecondarySelection = activeSecondarySelection;
    this.secondaryCandidates = secondaryCandidates == null ? List.of() : secondaryCandidates;
    this.requestedSecondarySelection = requestedSecondarySelection;
    this.secondaryPromotedToPrimary = secondaryPromotedToPrimary;
  }

  /**
   * Returns whether the given override is the primary subtitle selection.
   *
   * @param override The override to test.
   * @return Whether the override equals the primary selection.
   */
  public boolean isPrimary(@Nullable TrackSelectionOverride override) {
    return Objects.equals(override, primarySelection);
  }

  /**
   * Returns whether the given override is the active secondary subtitle selection.
   *
   * @param override The override to test.
   * @return Whether the override equals the active secondary selection.
   */
  public boolean isActiveSecondary(@Nullable TrackSelectionOverride override) {
    return Objects.equals(override, activeSecondarySelection);
  }
}
