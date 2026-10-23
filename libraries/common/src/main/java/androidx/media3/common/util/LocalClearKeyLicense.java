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
package androidx.media3.common.util;

import android.util.Base64;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses ClearKey licenses provided as plaintext (for ISO/Blu-ray/MP4 sources that embed keys
 * directly rather than fetching them from a license server).
 *
 * <p>The accepted plaintext formats are:
 *
 * <ul>
 *   <li>A JSON ClearKey document: {@code {"keys":[{"kid":"BASE64URL","k":"BASE64URL"}, ...]}}.
 *   <li>A single {@code kid:key} or {@code kid=key} pair, where the {@code kid} part may be a UUID
 *       string, 16 raw bytes (base64url) or 32 hex characters.
 * </ul>
 */
@UnstableApi
public final class LocalClearKeyLicense {

  private static final Pattern KID_JSON_PATTERN =
      Pattern.compile("\"kid\"\\s*:\\s*\"([^\"]+)\"");

  private LocalClearKeyLicense() {}

  /**
   * Parses the key identifiers contained in a plaintext ClearKey license.
   *
   * @param key The plaintext license string. May be {@code null} or empty.
   * @return The list of key IDs (KIDs) found in the license. Empty if the string could not be
   *     parsed as a known ClearKey format.
   */
  public static List<UUID> parse(@Nullable String key) {
    if (key == null) {
      return Collections.emptyList();
    }
    String trimmed = key.trim();
    if (trimmed.isEmpty()) {
      return Collections.emptyList();
    }

    List<UUID> kids = new ArrayList<>();
    if (trimmed.startsWith("{")) {
      // JSON ClearKey document: extract every "kid" entry.
      Matcher matcher = KID_JSON_PATTERN.matcher(trimmed);
      while (matcher.find()) {
        @Nullable UUID kid = decodeKid(matcher.group(1));
        if (kid != null) {
          kids.add(kid);
        }
      }
    } else {
      // "kid:key" or "kid=key" pair. Take the part before the first separator as the KID.
      int separatorIndex = indexOfSeparator(trimmed);
      String kidPart = separatorIndex == -1 ? trimmed : trimmed.substring(0, separatorIndex);
      @Nullable UUID kid = decodeKid(kidPart);
      if (kid != null) {
        kids.add(kid);
      }
    }
    return kids;
  }

  /**
   * Parses the key identifiers of a ClearKey license embedded in a {@link MediaItem.DrmConfiguration}
   * (typically passed as the license URI).
   *
   * @param drmConfiguration The DRM configuration, or {@code null}.
   * @return The list of key IDs (KIDs) found in the embedded license. Empty if absent/unparseable.
   */
  public static List<UUID> parse(@Nullable MediaItem.DrmConfiguration drmConfiguration) {
    if (drmConfiguration == null || drmConfiguration.licenseUri == null) {
      return Collections.emptyList();
    }
    return parse(drmConfiguration.licenseUri.toString());
  }

  private static int indexOfSeparator(String value) {
    int colon = value.indexOf(':');
    int equals = value.indexOf('=');
    if (colon == -1) {
      return equals;
    }
    if (equals == -1) {
      return colon;
    }
    return Math.min(colon, equals);
  }

  @Nullable
  private static UUID decodeKid(String raw) {
    String value = raw == null ? "" : raw.trim();
    if (value.isEmpty()) {
      return null;
    }
    // 1. Standard UUID string.
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException ignored) {
      // Fall through to other encodings.
    }
    // 2. 32 hex characters.
    if (value.length() == 32 && value.matches("(?i)^[0-9a-f]+$")) {
      @Nullable byte[] bytes = hexDecode(value);
      if (bytes != null && bytes.length == 16) {
        return bytesToUuid(bytes);
      }
    }
    // 3. Base64url-encoded 16 bytes.
    try {
      byte[] bytes = Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
      if (bytes.length == 16) {
        return bytesToUuid(bytes);
      }
    } catch (IllegalArgumentException ignored) {
      // Fall through.
    }
    return null;
  }

  @Nullable
  private static byte[] hexDecode(String hex) {
    try {
      int length = hex.length();
      byte[] bytes = new byte[length / 2];
      for (int i = 0; i < bytes.length; i++) {
        int high = Character.digit(hex.charAt(2 * i), 16);
        int low = Character.digit(hex.charAt(2 * i + 1), 16);
        if (high == -1 || low == -1) {
          return null;
        }
        bytes[i] = (byte) ((high << 4) + low);
      }
      return bytes;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static UUID bytesToUuid(byte[] bytes) {
    long msb = 0;
    long lsb = 0;
    for (int i = 0; i < 8; i++) {
      msb = (msb << 8) | (bytes[i] & 0xFF);
    }
    for (int i = 8; i < 16; i++) {
      lsb = (lsb << 8) | (bytes[i] & 0xFF);
    }
    return new UUID(msb, lsb);
  }
}
