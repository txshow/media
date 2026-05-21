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
 *
 */
package androidx.media3.ui.danmaku.fetcher;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.danmaku.Danmaku;
import androidx.media3.ui.danmaku.parser.QQParser;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.OkHttpClient;

/** Fetches danmaku data for Tencent Video page URIs. */
@UnstableApi
public final class QQFetcher implements Fetcher {

  /** Shared instance. */
  public static final QQFetcher INSTANCE = new QQFetcher();

  private static final Pattern RE_VID_PATH = Pattern.compile("/([^/]+)\\.html");
  private static final Pattern RE_VID_QUERY = Pattern.compile("[?&]vid=([^&]+)");
  private static final Pattern RE_DURATION = Pattern.compile("\"duration\":\"(\\d+)\"");
  private static final String INFO_URL =
      "https://union.video.qq.com/fcgi-bin/data?otype=json&tid=1804&appid=20001238&appkey=6c03bbe9658448a4&union_platform=1&idlist=";
  private static final String SEGMENT_URL = "https://dm.video.qq.com/barrage/segment/%s/t/v1/%d/%d";
  private static final ImmutableMap<String, String> HEADERS =
      FetcherUtil.headers("https://v.qq.com", "https://v.qq.com/");

  private QQFetcher() {}

  @Nullable
  private static String extractVid(String url) {
    Matcher queryMatcher = RE_VID_QUERY.matcher(url);
    if (queryMatcher.find()) {
      return queryMatcher.group(1);
    }
    Matcher pathMatcher = RE_VID_PATH.matcher(url);
    return pathMatcher.find() ? pathMatcher.group(1) : null;
  }

  private static int resolveDurationSec(String vid, long durationMs, OkHttpClient client)
      throws IOException {
    int durationSec = (int) (durationMs / 1000L);
    if (durationSec > 0) {
      return durationSec;
    }
    String response = FetcherUtil.fetchString(client, INFO_URL + vid, HEADERS);
    Matcher matcher = RE_DURATION.matcher(response);
    if (!matcher.find()) {
      throw new IOException("Cannot extract duration from QQ info response");
    }
    try {
      durationSec = Integer.parseInt(matcher.group(1));
    } catch (NumberFormatException e) {
      throw new IOException("Cannot parse duration for QQ vid: " + vid, e);
    }
    if (durationSec <= 0) {
      throw new IOException("Invalid duration for QQ vid: " + vid);
    }
    return durationSec;
  }

  @Override
  public boolean accepts(Uri uri) {
    String host = uri.getHost();
    return host != null && (host.equals("v.qq.com") || host.endsWith(".v.qq.com"));
  }

  @Override
  public Session prepare(Uri uri, OkHttpClient client, long durationMs) throws IOException {
    String url = uri.toString();
    @Nullable String vid = extractVid(url);
    if (vid == null) {
      throw new IOException("Cannot extract vid from QQ URL: " + url);
    }
    int segmentCount = (int) Math.ceil(resolveDurationSec(vid, durationMs, client) / 60.0);
    return new QQSession(vid, segmentCount, client);
  }

  private static final class QQSession implements Fetcher.Session {

    private final String vid;
    private final int segmentCount;
    private final OkHttpClient client;

    QQSession(String vid, int segmentCount, OkHttpClient client) {
      this.vid = vid;
      this.segmentCount = segmentCount;
      this.client = client;
    }

    @Override
    public int segmentCount() {
      return segmentCount;
    }

    @Override
    public List<Danmaku> fetchSegment(int segmentNumber) throws IOException {
      long startMs = (long) (segmentNumber - 1) * 60_000L;
      String segmentUrl = String.format(Locale.US, SEGMENT_URL, vid, startMs, startMs + 60_000L);
      byte[] data = FetcherUtil.fetchBytes(client, segmentUrl, HEADERS);
      return QQParser.INSTANCE.parse(new ByteArrayInputStream(data));
    }
  }
}
