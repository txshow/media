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
package androidx.media3.datasource;

import android.net.Uri;
import androidx.media3.common.util.UnstableApi;
import java.util.Set;

/**
 * Helper for attaching an ISO/BD/DVD edition selection to a {@link Uri}.
 *
 * <p>Degraded implementation: the edition index is carried as the {@link #QUERY_EDITION_KEY}
 * query parameter. Attaching an edition index is idempotent: any previously attached value is
 * replaced, so the same {@code Uri} can be annotated repeatedly.
 */
@UnstableApi
public final class IsoUri {

  /** Query parameter that carries the selected ISO edition index. */
  public static final String QUERY_EDITION_KEY = "isoEdition";

  private IsoUri() {}

  /**
   * Returns {@code uri} annotated with {@code editionIndex}.
   *
   * <p>If {@code uri} already carries an {@link #QUERY_EDITION_KEY} parameter, it is replaced with
   * {@code editionIndex} so the call is idempotent.
   */
  public static Uri withEditionIndex(Uri uri, int editionIndex) {
    String query = rebuildQuery(uri, editionIndex);
    return uri.buildUpon().query(query.isEmpty() ? null : query).build();
  }

  private static String rebuildQuery(Uri uri, int editionIndex) {
    StringBuilder queryBuilder = new StringBuilder();
    Set<String> names = uri.getQueryParameterNames();
    for (String name : names) {
      if (QUERY_EDITION_KEY.equals(name)) {
        continue;
      }
      for (String value : uri.getQueryParameters(name)) {
        appendQueryParameter(queryBuilder, name, value);
      }
    }
    appendQueryParameter(queryBuilder, QUERY_EDITION_KEY, String.valueOf(editionIndex));
    return queryBuilder.toString();
  }

  private static void appendQueryParameter(StringBuilder queryBuilder, String name, String value) {
    if (queryBuilder.length() > 0) {
      queryBuilder.append('&');
    }
    queryBuilder.append(Uri.encode(name)).append('=').append(Uri.encode(value));
  }
}
