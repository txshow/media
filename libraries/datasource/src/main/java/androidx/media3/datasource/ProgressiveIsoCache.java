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

import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.cache.Cache;

/**
 * Wraps a {@link DataSource.Factory} with progressive ISO disc caching.
 *
 * <p>Degraded implementation: disc titles are not progressively seekable-cacheable, so the factory
 * is returned unchanged. Production call sites already supply a {@code CacheDataSource.Factory}
 * backed by the shared {@code cache}; this method exists so call sites can future-proof against an
 * ISO-aware cache wrapping without changing their call.
 */
@UnstableApi
public final class ProgressiveIsoCache {

  private ProgressiveIsoCache() {}

  /**
   * Returns {@code factory} optionally wrapped for progressive ISO disc caching.
   *
   * <p>The current implementation returns {@code factory} unchanged. {@code cache} is accepted for
   * API compatibility with future implementations.
   */
  public static DataSource.Factory withDiskCache(DataSource.Factory factory, Cache cache) {
    return factory;
  }
}
