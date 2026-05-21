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
import android.util.Base64;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.ui.danmaku.Danmaku;
import androidx.media3.ui.danmaku.parser.YoukuParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONException;
import org.json.JSONObject;

/** Fetches danmaku data for Youku video page URIs. */
@UnstableApi
public final class YoukuFetcher implements Fetcher {

  /** Shared instance. */
  public static final YoukuFetcher INSTANCE = new YoukuFetcher();

  private static final Pattern PATH_VID_PATTERN =
      Pattern.compile("/id_([^./?#]+)(?:\\.html)?(?:[/?#]|$)");
  private static final Pattern QUERY_VID_PATTERN = Pattern.compile("[?&]vid=([^&#]+)");
  private static final Pattern SHOW_ID_PATTERN = Pattern.compile("[?&]s=([^&]+)");
  private static final String UA = FetcherUtil.UA;
  private static final String GUID = "NJnMGnrls3wCAXQaiNsMGIsY";
  private static final String DANMU_SECRET = "MkmC9SoIw6xCkSKHhJ7b5D2r51kBiREr";
  private static final String TOKEN_COOKIE_NAME = "_m_h5_tk";
  private static final String TOKEN_COOKIE_URL =
      "https://acs.youku.com/h5/mtop.youku.favorite.query.isfavorite/1.0/?appKey=24679788";
  private static final String VIDEO_INFO_URL =
      "https://openapi.youku.com/v2/videos/show.json?client_id=53e6cc67237fc59a&video_id=";
  private static final String DANMU_MESSAGE_FORMAT =
      "{\"ctime\": %d, \"ctype\": 10004, \"cver\": \"v1.0\", \"guid\": \"%s\", \"mat\": %d,"
          + " \"mcount\": 1, \"pid\": 0, \"sver\": \"3.1.0\", \"vid\": \"%s\"}";
  private static final String DANMU_DATA_FORMAT =
      "{\"pid\": 0, \"ctype\": 10004, \"sver\": \"3.1.0\", \"cver\": \"v1.0\", \"ctime\": %d,"
          + " \"guid\": \"%s\", \"vid\": \"%s\", \"mat\": %d, \"mcount\": 1, \"type\": 1, \"msg\":"
          + " \"%s\", \"sign\": \"%s\"}";
  private static final String DANMU_API_URL_FORMAT =
      "https://acs.youku.com/h5/mopen.youku.danmu.list/1.0/?jsv=2.7.0&appKey=24679788&t=%d&api=mopen.youku.danmu.list&v=1.0&type=originaljson&dataType=jsonp&timeout=20000&jsonpIncPrefix=utility&sign=%s";
  private static final MediaType FORM_MEDIA_TYPE =
      MediaType.get("application/x-www-form-urlencoded");

  private YoukuFetcher() {}

  private static List<Danmaku> fetchSegment(
      String vid, int segmentIndex, String token, OkHttpClient client) throws IOException {
    long timestamp = System.currentTimeMillis();
    String messageSource =
        String.format(Locale.US, DANMU_MESSAGE_FORMAT, timestamp, GUID, segmentIndex, vid);
    String encodedMessage = Base64.encodeToString(Util.getUtf8Bytes(messageSource), Base64.NO_WRAP);
    String dataSignature = FetcherUtil.md5Hex(encodedMessage + DANMU_SECRET);
    String dataJson =
        String.format(
            Locale.US,
            DANMU_DATA_FORMAT,
            timestamp,
            GUID,
            vid,
            segmentIndex,
            encodedMessage,
            dataSignature);
    String requestSignature = FetcherUtil.md5Hex(token + "&" + timestamp + "&24679788&" + dataJson);
    String requestUrl = String.format(Locale.US, DANMU_API_URL_FORMAT, timestamp, requestSignature);
    String responseJson = httpPost(client, requestUrl, "data=" + Uri.encode(dataJson));
    String resultJson;
    try {
      resultJson = new JSONObject(responseJson).getJSONObject("data").getString("result");
    } catch (JSONException e) {
      throw new IOException("Unexpected Youku danmaku API response format", e);
    }
    try {
      if (new JSONObject(resultJson).optInt("code", -1) != 1) {
        return Collections.emptyList();
      }
    } catch (JSONException ignored) {
    }
    return YoukuParser.INSTANCE.parse(new ByteArrayInputStream(Util.getUtf8Bytes(resultJson)));
  }

  @Nullable
  @VisibleForTesting
  static String extractVidFromContent(String content) {
    @Nullable String vid = extractDecodedGroup(PATH_VID_PATTERN, content);
    if (vid != null) {
      return vid;
    }
    return extractDecodedGroup(QUERY_VID_PATTERN, content);
  }

  @Nullable
  private static String extractDecodedGroup(Pattern pattern, String content) {
    Matcher matcher = pattern.matcher(content);
    return matcher.find() ? Uri.decode(matcher.group(1)) : null;
  }

  @Nullable
  private static String extractVidFromPage(String url, OkHttpClient client) throws IOException {
    String html = httpGet(client, url);
    return extractVidFromContent(html);
  }

  static String httpGet(OkHttpClient client, String url) throws IOException {
    Request request = new Request.Builder().url(url).header("User-Agent", UA).build();
    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful()) {
        throw new IOException("HTTP " + response.code() + " for " + url);
      }
      return response.body().string();
    }
  }

  static String httpPost(OkHttpClient client, String url, String formBody) throws IOException {
    RequestBody body = RequestBody.create(formBody, FORM_MEDIA_TYPE);
    Request request =
        new Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .addHeader("Referer", "https://v.youku.com")
            .post(body)
            .build();
    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful()) {
        throw new IOException("HTTP " + response.code() + " for " + url);
      }
      return response.body().string();
    }
  }

  private static String resolveVid(String url, OkHttpClient client) throws IOException {
    @Nullable
    String vid =
        SHOW_ID_PATTERN.matcher(url).find()
            ? extractVidFromPage(url, client)
            : extractVidFromContent(url);
    if (vid == null) {
      throw new IOException("Cannot extract vid from Youku URL: " + url);
    }
    return vid;
  }

  private static double resolveDurationSec(String vid, long durationMs, OkHttpClient client)
      throws IOException {
    double durationSec = durationMs / 1000.0;
    if (durationSec <= 0) {
      String videoInfo = httpGet(client, VIDEO_INFO_URL + vid);
      try {
        durationSec = Double.parseDouble(new JSONObject(videoInfo).getString("duration"));
      } catch (JSONException | NumberFormatException e) {
        throw new IOException("Cannot parse duration from Youku OpenAPI response", e);
      }
      if (durationSec <= 0) {
        throw new IOException("Invalid duration for Youku vid: " + vid);
      }
    }
    return durationSec;
  }

  private static String obtainToken(SessionCookieJar cookieJar, OkHttpClient client)
      throws IOException {
    httpGet(client, TOKEN_COOKIE_URL);
    for (Cookie cookie : cookieJar.loadForRequest(HttpUrl.get(TOKEN_COOKIE_URL))) {
      if (TOKEN_COOKIE_NAME.equals(cookie.name())) {
        return cookie.value().split("_")[0];
      }
    }
    throw new IOException("Cannot obtain " + TOKEN_COOKIE_NAME + " cookie from Youku");
  }

  @Override
  public boolean accepts(Uri uri) {
    String host = uri.getHost();
    return host != null && host.endsWith(".youku.com");
  }

  @Override
  public Session prepare(Uri uri, OkHttpClient client, long durationMs) throws IOException {
    String url = uri.toString();
    SessionCookieJar cookieJar = new SessionCookieJar();
    OkHttpClient sessionClient = client.newBuilder().cookieJar(cookieJar).build();
    String vid = resolveVid(url, sessionClient);
    double durationSec = resolveDurationSec(vid, durationMs, sessionClient);
    String token = obtainToken(cookieJar, sessionClient);
    int segmentCount = (int) Math.ceil(durationSec / 60.0);
    return new YoukuSession(vid, segmentCount, token, sessionClient);
  }

  static final class SessionCookieJar implements CookieJar {

    private final List<Cookie> cookies = new ArrayList<>();

    private static boolean hasSameIdentity(Cookie first, Cookie second) {
      return first.name().equals(second.name())
          && first.domain().equals(second.domain())
          && first.path().equals(second.path());
    }

    @Override
    public synchronized void saveFromResponse(@NonNull HttpUrl url, List<Cookie> newCookies) {
      long nowMs = System.currentTimeMillis();
      removeExpired(nowMs);
      for (Cookie newCookie : newCookies) {
        for (Iterator<Cookie> iterator = cookies.iterator(); iterator.hasNext(); ) {
          Cookie oldCookie = iterator.next();
          if (hasSameIdentity(oldCookie, newCookie)) {
            iterator.remove();
          }
        }
        if (newCookie.expiresAt() > nowMs) {
          cookies.add(newCookie);
        }
      }
    }

    @NonNull
    @Override
    public synchronized List<Cookie> loadForRequest(@NonNull HttpUrl url) {
      removeExpired(System.currentTimeMillis());
      List<Cookie> matching = new ArrayList<>();
      for (Cookie cookie : cookies) {
        if (cookie.matches(url)) {
          matching.add(cookie);
        }
      }
      return matching;
    }

    private void removeExpired(long nowMs) {
      for (Iterator<Cookie> iterator = cookies.iterator(); iterator.hasNext(); ) {
        if (iterator.next().expiresAt() <= nowMs) {
          iterator.remove();
        }
      }
    }
  }

  private static final class YoukuSession implements Fetcher.Session {

    private final String vid;
    private final int segmentCount;
    private final String token;
    private final OkHttpClient client;

    YoukuSession(String vid, int segmentCount, String token, OkHttpClient client) {
      this.vid = vid;
      this.segmentCount = segmentCount;
      this.token = token;
      this.client = client;
    }

    @Override
    public int segmentCount() {
      return segmentCount;
    }

    @Override
    public List<Danmaku> fetchSegment(int segmentNumber) throws IOException {
      return YoukuFetcher.fetchSegment(vid, segmentNumber - 1, token, client);
    }
  }
}
