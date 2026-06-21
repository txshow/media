/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.exoplayer.util;

import static android.os.Build.VERSION.SDK_INT;
import static android.view.View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE;
import static com.google.common.base.Preconditions.checkArgument;

import android.annotation.SuppressLint;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.net.Uri;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.view.ViewParent;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MediaChapter;
import androidx.media3.common.MediaEdition;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.FormatNameUtil;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.HttpUtil;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.ExoPlayerDebugInfo;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import com.google.common.net.HttpHeaders;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A helper class for periodically updating a {@link TextView} with debug information obtained from
 * an {@link ExoPlayer}.
 */
public class DebugTextViewHelper {

  private static final int REFRESH_INTERVAL_MS = 1000;
  private static final String INDENT = "     ";
  private static final String INLINE_GAP = "  ";
  private static final String NONE = "none";
  private static final String UNKNOWN = "unknown";

  private final ExoPlayer player;
  private final TextView textView;
  private final Updater updater;
  private final View.OnLayoutChangeListener styleLayoutChangeListener;

  private boolean started;
  private @Nullable View styleReference;
  private @Nullable String lastLoadError;
  private @Nullable String lastAudioError;
  private @Nullable String lastVideoError;
  private long fileSizeBytes;

  /**
   * @param player The {@link ExoPlayer} from which debug information should be obtained. Only
   *     players which are accessed on the main thread are supported ({@code
   *     player.getApplicationLooper() == Looper.getMainLooper()}).
   * @param textView The {@link TextView} that should be updated to display the information.
   */
  public DebugTextViewHelper(ExoPlayer player, TextView textView) {
    checkArgument(player.getApplicationLooper() == Looper.getMainLooper());
    this.player = player;
    this.textView = textView;
    this.updater = new Updater();
    if (SDK_INT >= 35) {
      // Do not let text updates bump up the refresh rate higher than the playing video.
      // See https://developer.android.com/develop/ui/views/animations/adaptive-refresh-rate .
      textView.setRequestedFrameRate(REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE);
    }
    this.styleLayoutChangeListener =
        (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
            applyStatsTextStyle(v);
    this.fileSizeBytes = C.LENGTH_UNSET;
    applyStatsTextStyle(findStyleReferenceView());
  }

  private static @Nullable String getCodecLine(Format format) {
    return firstNonEmpty(
        FormatNameUtil.getSampleMimeTypeDisplayName(format), firstNonEmpty(format.codecs, UNKNOWN));
  }

  @VisibleForTesting
  /* package */ static @Nullable String getMediaTitle(MediaMetadata metadata) {
    @Nullable
    String displayTitle = metadata.displayTitle == null ? null : metadata.displayTitle.toString();
    @Nullable String title = metadata.title == null ? null : metadata.title.toString();
    return firstNonEmpty(displayTitle, title);
  }

  @VisibleForTesting
  /* package */ static @Nullable String getDistinctMediaTitle(
      MediaMetadata metadata, @Nullable String fileName) {
    @Nullable String title = getMediaTitle(metadata);
    if (title != null && fileName != null && title.trim().equals(fileName.trim())) {
      return null;
    }
    return title;
  }

  @VisibleForTesting
  /* package */ static @Nullable String getSelectedChapterString(List<MediaChapter> chapters) {
    for (MediaChapter chapter : chapters) {
      if (chapter.selected) {
        return chapter.label + " (" + (chapter.index + 1) + " / " + chapters.size() + ")";
      }
    }
    return null;
  }

  @VisibleForTesting
  /* package */ static @Nullable String getSelectedEditionString(List<MediaEdition> editions) {
    if (editions.size() <= 1) {
      return null;
    }
    for (MediaEdition edition : editions) {
      if (edition.selected) {
        return edition.label + " (" + (edition.index + 1) + " / " + editions.size() + ")";
      }
    }
    return null;
  }

  private static void appendPrimaryLine(
      StringBuilder builder, String prefix, @Nullable String value) {
    if (builder.length() > 0) {
      builder.append('\n');
    }
    builder.append(prefix);
    if (!isNullOrBlank(value)) {
      builder.append(' ').append(value.trim());
    }
    builder.append('\n');
  }

  private static void appendDetailLine(
      StringBuilder builder, String prefix, @Nullable String value) {
    if (isNullOrBlank(value)) {
      return;
    }
    builder.append(INDENT).append(prefix).append(' ').append(value.trim()).append('\n');
  }

  private static void appendDetailLine(
      StringBuilder builder,
      String prefix,
      @Nullable String value,
      String inlinePrefix,
      @Nullable String inlineValue) {
    @Nullable String line = appendInlineField(null, prefix, value);
    line = appendInlineField(line, inlinePrefix, inlineValue);
    if (line != null) {
      builder.append(INDENT).append(line).append('\n');
    }
  }

  private static @Nullable String appendInlineField(
      @Nullable String line, String prefix, @Nullable String value) {
    if (isNullOrBlank(value)) {
      return line;
    }
    String field = prefix + ' ' + value.trim();
    return isNullOrBlank(line) ? field : line.trim() + INLINE_GAP + field;
  }

  @VisibleForTesting
  /* package */ static @Nullable String getCurrentFileName(@Nullable MediaItem item) {
    if (item == null || item.localConfiguration == null) {
      return null;
    }
    @Nullable String encodedPath = item.localConfiguration.uri.getEncodedPath();
    if (isNullOrBlank(encodedPath)) {
      return null;
    }
    int lastSlash = encodedPath.lastIndexOf('/');
    String encodedFileName =
        lastSlash >= 0 && lastSlash + 1 < encodedPath.length()
            ? encodedPath.substring(lastSlash + 1)
            : encodedPath;
    return isNullOrBlank(encodedFileName) ? null : Uri.decode(encodedFileName);
  }

  private static @Nullable String joinFormatProtocol(
      @Nullable String format, @Nullable String protocol) {
    if (isNullOrBlank(format)) {
      return isNullOrBlank(protocol) ? null : protocol.trim();
    }
    String normalizedFormat = format.trim();
    if (isNullOrBlank(protocol) || normalizedFormat.equals(protocol.trim())) {
      return normalizedFormat;
    }
    return normalizedFormat + " / " + protocol.trim();
  }

  private static @Nullable String getContainerFormat(@Nullable Format format) {
    if (format == null) {
      return null;
    }
    return getShortMimeType(format.containerMimeType);
  }

  private static @Nullable String getShortMimeType(@Nullable String mimeType) {
    if (isNullOrBlank(mimeType)) {
      return null;
    }
    mimeType = mimeType.trim();
    switch (mimeType) {
      case MimeTypes.APPLICATION_M3U8:
        return "hls";
      case MimeTypes.APPLICATION_MPD:
        return "dash";
      case MimeTypes.APPLICATION_SS:
        return "ss";
      case MimeTypes.APPLICATION_RTSP:
        return "rtsp";
      case MimeTypes.VIDEO_MP4:
      case MimeTypes.AUDIO_MP4:
      case MimeTypes.APPLICATION_MP4:
        return "mp4";
      case MimeTypes.VIDEO_QUICK_TIME:
        return "mov";
      case MimeTypes.VIDEO_WEBM:
      case MimeTypes.AUDIO_WEBM:
        return "webm";
      case MimeTypes.VIDEO_MATROSKA:
      case MimeTypes.AUDIO_MATROSKA:
        return "mkv";
      case MimeTypes.VIDEO_MP2T:
        return "mpegts";
      default:
        return mimeType.replace("application/", "").replace("video/", "").replace("audio/", "");
    }
  }

  private static @Nullable String getQueryMimeType(
      MediaItem.LocalConfiguration localConfiguration) {
    if (!localConfiguration.uri.isHierarchical()) {
      return null;
    }
    return getShortMimeType(localConfiguration.uri.getQueryParameter("mime"));
  }

  private static @Nullable String getContentTypeString(@C.ContentType int contentType) {
    switch (contentType) {
      case C.CONTENT_TYPE_DASH:
        return "dash";
      case C.CONTENT_TYPE_HLS:
        return "hls";
      case C.CONTENT_TYPE_SS:
        return "ss";
      case C.CONTENT_TYPE_RTSP:
        return "rtsp";
      case C.CONTENT_TYPE_OTHER:
      default:
        return null;
    }
  }

  @VisibleForTesting
  /* package */ static @Nullable String getDroppedFramesString(@Nullable DecoderCounters counters) {
    if (counters == null) {
      return null;
    }
    counters.ensureUpdated();
    int afterDecoderDroppedBufferCount =
        Math.max(0, counters.droppedBufferCount - counters.droppedInputBufferCount);
    return counters.droppedInputBufferCount
        + " (input) "
        + afterDecoderDroppedBufferCount
        + " (after decoder)";
  }

  private static long getDocumentSize(Map<String, List<String>> responseHeaders) {
    @Nullable String contentRange = getFirstHeader(responseHeaders, HttpHeaders.CONTENT_RANGE);
    long documentSize = HttpUtil.getDocumentSize(contentRange);
    if (documentSize != C.LENGTH_UNSET) {
      return documentSize;
    }
    return HttpUtil.getContentLength(
        getFirstHeader(responseHeaders, HttpHeaders.CONTENT_LENGTH), contentRange);
  }

  private static @Nullable String getFirstHeader(
      Map<String, List<String>> responseHeaders, String name) {
    for (Map.Entry<String, List<String>> entry : responseHeaders.entrySet()) {
      if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
        return entry.getValue().get(0);
      }
    }
    return null;
  }

  @VisibleForTesting
  /* package */ static @Nullable String getEncodedFormatString(Format format) {
    @Nullable String sampleMimeType = format.sampleMimeType;
    @Nullable String codecs = format.codecs;
    if (isNullOrBlank(sampleMimeType)) {
      return codecs;
    }
    if (isNullOrBlank(codecs) || sampleMimeType.equals(codecs)) {
      return sampleMimeType;
    }
    return sampleMimeType + " (" + codecs + ")";
  }

  @VisibleForTesting
  /* package */ static @Nullable String getVideoBitDepthString(Format format) {
    ColorInfo colorInfo = format.colorInfo;
    if (colorInfo == null || !colorInfo.isBitdepthValid()) {
      return null;
    }
    return colorInfo.lumaBitdepth + "/" + colorInfo.chromaBitdepth + "-bit (luma/chroma)";
  }

  @VisibleForTesting
  /* package */ static @Nullable String getBitrateString(Format format) {
    StringBuilder builder = new StringBuilder();
    appendPart(builder, appendSuffix(formatBitrate(format.averageBitrate), " avg"));
    appendPart(builder, appendSuffix(formatBitrate(format.peakBitrate), " peak"));
    return builder.length() == 0 ? null : builder.toString();
  }

  @VisibleForTesting
  /* package */ static @Nullable String getColorString(Format format) {
    ColorInfo colorInfo = format.colorInfo;
    if (colorInfo == null) {
      return null;
    }
    StringBuilder builder = new StringBuilder();
    String colorSpace = getColorSpaceString(colorInfo.colorSpace);
    String colorRange = getColorRangeString(colorInfo.colorRange);
    String transfer = getColorTransferString(colorInfo.colorTransfer);
    if (colorSpace != null) {
      appendPart(builder, "Space: " + colorSpace);
    }
    if (colorRange != null) {
      appendPart(builder, "Range: " + colorRange);
    }
    if (transfer != null) {
      appendPart(builder, "Transfer: " + transfer);
    }
    return builder.length() == 0 ? null : builder.toString();
  }

  private static @Nullable String getSizeString(int width, int height, float pixelRatio) {
    if (width <= 0 || height <= 0) {
      return null;
    }
    int displayWidth = Math.max(1, Math.round(width * (pixelRatio > 0 ? pixelRatio : 1.0f)));
    double aspectRatio = displayWidth / (double) height;
    return width
        + " x "
        + height
        + " "
        + formatFloat(aspectRatio, 3)
        + " ("
        + getAspectFraction(displayWidth, height)
        + ")";
  }

  private static String getAspectFraction(int width, int height) {
    int gcd = greatestCommonDivisor(width, height);
    return width / gcd + ":" + height / gcd;
  }

  private static int greatestCommonDivisor(int a, int b) {
    a = Math.abs(a);
    b = Math.abs(b);
    while (b != 0) {
      int remainder = a % b;
      a = b;
      b = remainder;
    }
    return Math.max(1, a);
  }

  private static @Nullable String getDecoderString(Format format, @Nullable String decoderName) {
    if (isNullOrBlank(decoderName)) {
      return null;
    }
    String trimmedDecoderName = decoderName.trim();
    @Nullable MediaCodecInfo decoderInfo = getDecoderInfo(format, trimmedDecoderName);
    if (decoderInfo != null && decoderInfo.hardwareAccelerated) {
      return appendInlineField(trimmedDecoderName, "HW:", getLowerCaseSimpleName(MediaCodec.class));
    }
    return trimmedDecoderName;
  }

  private static @Nullable String getBufferCountersString(@Nullable DecoderCounters counters) {
    if (counters == null) {
      return null;
    }
    counters.ensureUpdated();
    int skipped = counters.skippedInputBufferCount + counters.skippedOutputBufferCount;
    return "queued "
        + counters.queuedInputBufferCount
        + " rendered "
        + counters.renderedOutputBufferCount
        + " skipped "
        + skipped;
  }

  private static @Nullable String getVideoFrameProcessingOffsetString(
      @Nullable DecoderCounters counters) {
    if (counters == null) {
      return null;
    }
    counters.ensureUpdated();
    if (counters.videoFrameProcessingOffsetCount <= 0) {
      return null;
    }
    double averageOffsetMs =
        counters.totalVideoFrameProcessingOffsetUs
            / (double) counters.videoFrameProcessingOffsetCount
            / 1000.0;
    return formatCompactFloat(averageOffsetMs, 2)
        + " ms avg ("
        + counters.videoFrameProcessingOffsetCount
        + ")";
  }

  private static @Nullable MediaCodecInfo getDecoderInfo(Format format, String decoderName) {
    if (isNullOrBlank(format.sampleMimeType)) {
      return null;
    }
    @Nullable
    MediaCodecInfo decoderInfo =
        findDecoderInfo(format.sampleMimeType, decoderName, /* secure= */ false);
    if (decoderInfo == null) {
      decoderInfo = findDecoderInfo(format.sampleMimeType, decoderName, /* secure= */ true);
    }
    return decoderInfo;
  }

  private static @Nullable MediaCodecInfo findDecoderInfo(
      String mimeType, String decoderName, boolean secure) {
    try {
      for (MediaCodecInfo decoderInfo :
          MediaCodecUtil.getDecoderInfos(mimeType, secure, /* tunneling= */ false)) {
        if (decoderName.equals(decoderInfo.name)) {
          return decoderInfo;
        }
      }
    } catch (DecoderQueryException e) {
      return null;
    }
    return null;
  }

  private static String getLowerCaseSimpleName(Class<?> clazz) {
    return clazz.getSimpleName().toLowerCase(Locale.US);
  }

  private static @Nullable String getColorSpaceString(@C.ColorSpace int colorSpace) {
    switch (colorSpace) {
      case C.COLOR_SPACE_BT601:
        return "bt601";
      case C.COLOR_SPACE_BT709:
        return "bt709";
      case C.COLOR_SPACE_BT2020:
        return "bt2020";
      case Format.NO_VALUE:
      default:
        return null;
    }
  }

  private static @Nullable String getColorRangeString(@C.ColorRange int colorRange) {
    switch (colorRange) {
      case C.COLOR_RANGE_LIMITED:
        return "limited";
      case C.COLOR_RANGE_FULL:
        return "full";
      case Format.NO_VALUE:
      default:
        return null;
    }
  }

  private static @Nullable String getColorTransferString(@C.ColorTransfer int colorTransfer) {
    switch (colorTransfer) {
      case C.COLOR_TRANSFER_LINEAR:
        return "linear";
      case C.COLOR_TRANSFER_SDR:
        return "smpte170m";
      case C.COLOR_TRANSFER_SRGB:
        return "srgb";
      case C.COLOR_TRANSFER_GAMMA_2_2:
        return "gamma2.2";
      case C.COLOR_TRANSFER_ST2084:
        return "smpte2084";
      case C.COLOR_TRANSFER_HLG:
        return "hlg";
      case Format.NO_VALUE:
      default:
        return null;
    }
  }

  private static void appendPart(StringBuilder builder, @Nullable String value) {
    if (value == null || value.isEmpty()) {
      return;
    }
    if (builder.length() > 0) {
      builder.append(' ');
    }
    builder.append(value);
  }

  private static @Nullable String appendSuffix(@Nullable String value, String suffix) {
    return value == null ? null : value + suffix;
  }

  private static @Nullable String formatDurationSeconds(long timeMs) {
    if (timeMs < 0) {
      return null;
    }
    return formatFloat(timeMs / 1000.0, 1) + " sec";
  }

  private static @Nullable String formatFrameRate(float frameRate) {
    if (frameRate <= 0) {
      return null;
    }
    return formatCompactFloat(frameRate, 3) + " fps";
  }

  @VisibleForTesting
  /* package */ static @Nullable String formatRefreshRate(float refreshRate) {
    return refreshRate <= 0 ? null : formatCompactFloat(refreshRate, 3) + " Hz";
  }

  @VisibleForTesting
  /* package */ static @Nullable String formatAudioOffset(long audioOffsetMs) {
    return audioOffsetMs == 0 ? null : (audioOffsetMs > 0 ? "+" : "") + audioOffsetMs + " ms";
  }

  @VisibleForTesting
  /* package */ static String formatVolume(float volume) {
    return formatCompactFloat(volume * 100.0, 1) + "%";
  }

  @VisibleForTesting
  /* package */ static String formatDeviceVolume(int volume, DeviceInfo deviceInfo, boolean muted) {
    String value =
        deviceInfo.maxVolume > 0 ? volume + " / " + deviceInfo.maxVolume : String.valueOf(volume);
    return muted ? value + " (Muted)" : value;
  }

  private static @Nullable String formatSampleRate(int sampleRate) {
    if (sampleRate <= 0) {
      return null;
    }
    return sampleRate + " Hz";
  }

  private static @Nullable String formatBitrate(long bitrate) {
    if (bitrate <= 0) {
      return null;
    }
    if (bitrate >= 1_000_000) {
      return formatCompactFloat(bitrate / 1_000_000.0, 2) + " Mbps";
    }
    if (bitrate >= 1_000) {
      return formatCompactFloat(bitrate / 1_000.0, 0) + " kbps";
    }
    return bitrate + " bps";
  }

  private static @Nullable String formatBytes(long bytes) {
    if (bytes < 0) {
      return null;
    }
    if (bytes >= 1L << 30) {
      return formatCompactFloat(bytes / (double) (1L << 30), 3) + " GiB";
    }
    if (bytes >= 1L << 20) {
      return formatCompactFloat(bytes / (double) (1L << 20), 3) + " MiB";
    }
    if (bytes >= 1L << 10) {
      return formatCompactFloat(bytes / (double) (1L << 10), 3) + " KiB";
    }
    return bytes + " B";
  }

  private static @Nullable String formatPositiveInt(int value) {
    return value <= 0 ? null : String.valueOf(value);
  }

  private static String formatFloat(double value, int decimals) {
    switch (decimals) {
      case 0:
        return String.format(Locale.US, "%.0f", value);
      case 1:
        return String.format(Locale.US, "%.1f", value);
      case 2:
        return String.format(Locale.US, "%.2f", value);
      case 3:
        return String.format(Locale.US, "%.3f", value);
      default:
        throw new IllegalArgumentException("Unsupported decimal count: " + decimals);
    }
  }

  private static String formatCompactFloat(double value, int maxDecimals) {
    String text = formatFloat(value, maxDecimals);
    int decimalIndex = text.indexOf('.');
    if (decimalIndex == -1) {
      return text;
    }
    int end = text.length();
    while (end > decimalIndex && text.charAt(end - 1) == '0') {
      end--;
    }
    if (end == decimalIndex + 1) {
      end--;
    }
    return text.substring(0, end);
  }

  private static @Nullable String firstNonEmpty(@Nullable String value, @Nullable String fallback) {
    return isNullOrBlank(value) ? fallback : value;
  }

  private static String noneIfNull(@Nullable String value) {
    return value == null ? NONE : value;
  }

  private static String errorMessage(Throwable error) {
    String message = error.getMessage();
    return isNullOrBlank(message) ? error.getClass().getSimpleName() : message;
  }

  private static boolean isNullOrBlank(@Nullable String value) {
    return value == null || value.trim().isEmpty();
  }

  /**
   * Starts periodic updates of the {@link TextView}. Must be called from the application's main
   * thread.
   */
  public final void start() {
    if (started) {
      return;
    }
    started = true;
    attachStatsTextStyle();
    player.addListener(updater);
    player.addAnalyticsListener(updater);
    updateAndPost();
  }

  /**
   * Stops periodic updates of the {@link TextView}. Must be called from the application's main
   * thread.
   */
  public final void stop() {
    if (!started) {
      return;
    }
    started = false;
    player.removeListener(updater);
    player.removeAnalyticsListener(updater);
    detachStatsTextStyle();
    textView.removeCallbacks(updater);
  }

  @UnstableApi
  @SuppressLint("SetTextI18n")
  protected final void updateAndPost() {
    if (textView.isInLayout()) {
      textView.removeCallbacks(updater);
      textView.post(updater);
      return;
    }
    String debugString = getDebugString();
    if (!TextUtils.equals(textView.getText(), debugString)) {
      textView.setText(DebugTextViewStyle.style(debugString));
    }
    textView.removeCallbacks(updater);
    if (started) {
      textView.postDelayed(updater, REFRESH_INTERVAL_MS);
    }
  }

  /** Returns the debugging information string to be shown by the target {@link TextView}. */
  @UnstableApi
  protected String getDebugString() {
    StringBuilder builder = new StringBuilder();
    @Nullable
    ExoPlayerDebugInfo debugInfo =
        player instanceof ExoPlayerDebugInfo ? (ExoPlayerDebugInfo) player : null;
    appendFile(builder);
    appendDisplay(builder, debugInfo);
    appendVideo(builder, debugInfo);
    appendAudio(builder, debugInfo);
    appendErrors(builder);
    return builder.toString();
  }

  private void appendFile(StringBuilder builder) {
    boolean canGetCurrentMediaItem =
        player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM);
    @Nullable MediaItem item = canGetCurrentMediaItem ? player.getCurrentMediaItem() : null;
    @Nullable String fileName = getCurrentFileName(item);
    appendPrimaryLine(builder, "File:", fileName);
    if (player.isCommandAvailable(Player.COMMAND_GET_METADATA)) {
      appendDetailLine(
          builder, "Title:", getDistinctMediaTitle(player.getMediaMetadata(), fileName));
    }
    if (canGetCurrentMediaItem) {
      appendDetailLine(builder, "Duration:", formatDurationSeconds(player.getDuration()));
    }
    appendDetailLine(
        builder, "Edition:", getSelectedEditionString(player.getCurrentMediaEditions()));
    appendDetailLine(
        builder, "Chapter:", getSelectedChapterString(player.getCurrentMediaChapters()));
    if (fileSizeBytes != C.LENGTH_UNSET) {
      appendDetailLine(
          builder,
          "Size:",
          formatBytes(fileSizeBytes),
          "Format/Protocol:",
          getFormatProtocol(item));
    } else {
      appendDetailLine(builder, "Format/Protocol:", getFormatProtocol(item));
    }
    if (canGetCurrentMediaItem) {
      appendDetailLine(builder, "Buffered:", getBufferedString());
    }
  }

  private void appendDisplay(StringBuilder builder, @Nullable ExoPlayerDebugInfo debugInfo) {
    @Nullable Object videoOutput = debugInfo == null ? null : debugInfo.getVideoOutput();
    Size surfaceSize = player.getSurfaceSize();
    if (!hasDisplayInfo(videoOutput, surfaceSize)) {
      return;
    }
    appendPrimaryLine(builder, "Display:", getVideoOutputString(videoOutput));
    appendDetailLine(
        builder,
        "Resolution:",
        getSizeString(surfaceSize.getWidth(), surfaceSize.getHeight(), /* pixelRatio= */ 1.0f));
    appendDetailLine(builder, "Refresh Rate:", getDisplayRefreshRateString());
    appendDetailLine(
        builder, "Dropped Frames:", getDroppedFramesString(player.getVideoDecoderCounters()));
  }

  private void appendVideo(StringBuilder builder, @Nullable ExoPlayerDebugInfo debugInfo) {
    Format format = player.getVideoFormat();
    if (format == null) {
      return;
    }
    DecoderCounters counters = player.getVideoDecoderCounters();
    appendPrimaryLine(builder, "Video:", getCodecLine(format));
    appendDetailLine(
        builder,
        "Decoder:",
        getDecoderString(format, debugInfo == null ? null : debugInfo.getVideoDecoderName()));
    appendDetailLine(builder, "Buffers:", getBufferCountersString(counters));
    appendDetailLine(builder, "Offset:", getVideoFrameProcessingOffsetString(counters));
    appendDetailLine(builder, "Frame Rate:", formatFrameRate(format.frameRate));
    appendDetailLine(
        builder,
        "Resolution:",
        getSizeString(format.width, format.height, format.pixelWidthHeightRatio));
    appendDetailLine(builder, "Encoded Format:", getEncodedFormatString(format));
    appendDetailLine(builder, "Bit Depth:", getVideoBitDepthString(format));
    appendDetailLine(builder, "Color:", getColorString(format));
    appendDetailLine(builder, "Bitrate:", getBitrateString(format));
  }

  private void appendAudio(StringBuilder builder, @Nullable ExoPlayerDebugInfo debugInfo) {
    Format format = player.getAudioFormat();
    if (format == null) {
      return;
    }
    DecoderCounters counters = player.getAudioDecoderCounters();
    appendPrimaryLine(builder, "Audio:", getCodecLine(format));
    appendDetailLine(
        builder,
        "Output:",
        debugInfo != null && debugInfo.isAudioTrackInitialized()
            ? getLowerCaseSimpleName(AudioTrack.class)
            : null);
    appendDetailLine(
        builder,
        "Decoder:",
        getDecoderString(format, debugInfo == null ? null : debugInfo.getAudioDecoderName()));
    appendDetailLine(builder, "Buffers:", getBufferCountersString(counters));
    appendDetailLine(
        builder,
        "Channels:",
        formatPositiveInt(format.channelCount),
        "Encoded Format:",
        getEncodedFormatString(format));
    appendDetailLine(builder, "Sample Rate:", formatSampleRate(format.sampleRate));
    appendDetailLine(builder, "Bitrate:", getBitrateString(format));
    if (player.isCommandAvailable(Player.COMMAND_GET_VOLUME)) {
      appendDetailLine(builder, "Volume:", formatVolume(player.getVolume()));
    }
    if (player.isCommandAvailable(Player.COMMAND_GET_DEVICE_VOLUME)) {
      appendDetailLine(
          builder,
          "Device Volume:",
          formatDeviceVolume(
              player.getDeviceVolume(), player.getDeviceInfo(), player.isDeviceMuted()));
    }
    if (player.isCommandAvailable(Player.COMMAND_GET_AUDIO_OFFSET)) {
      appendDetailLine(builder, "Audio Delay:", formatAudioOffset(player.getAudioOffsetMs()));
    }
  }

  private void appendErrors(StringBuilder builder) {
    @Nullable PlaybackException playerError = player.getPlayerError();
    @Nullable String playerErrorMessage = playerError == null ? null : errorMessage(playerError);
    if (playerErrorMessage == null
        && lastLoadError == null
        && lastAudioError == null
        && lastVideoError == null) {
      return;
    }
    appendPrimaryLine(builder, "Errors:", null);
    appendDetailLine(builder, "Player Error:", noneIfNull(playerErrorMessage));
    appendDetailLine(builder, "Load Error:", noneIfNull(lastLoadError));
    appendDetailLine(builder, "Audio Error:", noneIfNull(lastAudioError));
    appendDetailLine(builder, "Video Error:", noneIfNull(lastVideoError));
  }

  private @Nullable String getBufferedString() {
    return formatDurationSeconds(player.getTotalBufferedDuration());
  }

  private @Nullable String getDisplayRefreshRateString() {
    @Nullable Display display = textView.getDisplay();
    if (display == null || display.getRefreshRate() <= 0) {
      return null;
    }
    return formatRefreshRate(display.getRefreshRate());
  }

  private @Nullable String getFormatProtocol(@Nullable MediaItem item) {
    @Nullable String activeFormat = getActiveContainerFormat();
    if (item == null || item.localConfiguration == null) {
      return activeFormat;
    }
    MediaItem.LocalConfiguration localConfiguration = item.localConfiguration;
    String format =
        firstNonEmpty(
            activeFormat,
            firstNonEmpty(
                getShortMimeType(localConfiguration.mimeType),
                getQueryMimeType(localConfiguration)));
    if (format == null) {
      format =
          getContentTypeString(
              Util.inferContentTypeForUriAndMimeType(
                  localConfiguration.uri, localConfiguration.mimeType));
    }
    return joinFormatProtocol(format, localConfiguration.uri.getScheme());
  }

  private @Nullable String getActiveContainerFormat() {
    @Nullable Format videoFormat = player.getVideoFormat();
    @Nullable String videoContainer = getContainerFormat(videoFormat);
    if (videoContainer != null) {
      return videoContainer;
    }
    return getContainerFormat(player.getAudioFormat());
  }

  private static String getVideoOutputString(@Nullable Object output) {
    if (output == null) {
      return UNKNOWN;
    }
    return output instanceof Surface
        ? getLowerCaseSimpleName(Surface.class)
        : output.getClass().getSimpleName();
  }

  private static boolean hasDisplayInfo(@Nullable Object videoOutput, Size surfaceSize) {
    return videoOutput != null || surfaceSize.getWidth() > 0 || surfaceSize.getHeight() > 0;
  }

  private boolean maybeUpdateFileSize(
      EventTime eventTime, LoadEventInfo loadEventInfo, MediaLoadData mediaLoadData) {
    if (!isCurrentMediaLoad(eventTime) || !isProgressiveMediaLoad(mediaLoadData)) {
      return false;
    }
    long documentSize = getDocumentSize(loadEventInfo.responseHeaders);
    if (documentSize == C.LENGTH_UNSET || documentSize == fileSizeBytes) {
      return false;
    }
    fileSizeBytes = documentSize;
    return true;
  }

  @VisibleForTesting
  /* package */ static boolean isProgressiveMediaLoad(MediaLoadData mediaLoadData) {
    return mediaLoadData.dataType == C.DATA_TYPE_MEDIA
        && mediaLoadData.trackType == C.TRACK_TYPE_UNKNOWN
        && mediaLoadData.trackFormat == null;
  }

  @VisibleForTesting
  /* package */ static boolean isCurrentMediaLoad(EventTime eventTime) {
    if (eventTime.windowIndex != eventTime.currentWindowIndex) {
      return false;
    }
    if (eventTime.timeline.isEmpty() || eventTime.currentTimeline.isEmpty()) {
      return true;
    }
    Timeline.Window window = new Timeline.Window();
    Object eventWindowUid = eventTime.timeline.getWindow(eventTime.windowIndex, window).uid;
    Object currentWindowUid =
        eventTime.currentTimeline.getWindow(eventTime.currentWindowIndex, window).uid;
    return eventWindowUid.equals(currentWindowUid);
  }

  private void attachStatsTextStyle() {
    View reference = findStyleReferenceView();
    if (styleReference == reference) {
      applyStatsTextStyle();
      return;
    }
    detachStatsTextStyle();
    styleReference = reference;
    if (styleReference != null) {
      styleReference.addOnLayoutChangeListener(styleLayoutChangeListener);
    }
    applyStatsTextStyle(reference);
  }

  private void detachStatsTextStyle() {
    if (styleReference != null) {
      styleReference.removeOnLayoutChangeListener(styleLayoutChangeListener);
      styleReference = null;
    }
  }

  private @Nullable View findStyleReferenceView() {
    ViewParent parent = textView.getParent();
    return parent instanceof View ? (View) parent : null;
  }

  private void applyStatsTextStyle() {
    applyStatsTextStyle(styleReference);
  }

  private void applyStatsTextStyle(@Nullable View referenceView) {
    DebugTextViewStyle.apply(textView, referenceView);
  }

  private void resetMediaStats() {
    fileSizeBytes = C.LENGTH_UNSET;
    clearPlaybackErrors();
  }

  private void clearPlaybackErrors() {
    lastLoadError = null;
    lastAudioError = null;
    lastVideoError = null;
  }

  private final class Updater implements Player.Listener, AnalyticsListener, Runnable {

    // Player.Listener implementation.

    @Override
    public void onEvents(Player player, Player.Events events) {
      if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)
          && player.getPlaybackState() == Player.STATE_READY) {
        clearPlaybackErrors();
      }
      updateAndPost();
    }

    @Override
    public void onMediaItemTransition(
        @Nullable MediaItem mediaItem, @Player.MediaItemTransitionReason int reason) {
      resetMediaStats();
    }

    @Override
    public void onLoadCompleted(
        EventTime eventTime, LoadEventInfo loadEventInfo, MediaLoadData mediaLoadData) {
      boolean debugInfoChanged = maybeUpdateFileSize(eventTime, loadEventInfo, mediaLoadData);
      if (isCurrentMediaLoad(eventTime)) {
        debugInfoChanged |= lastLoadError != null;
        lastLoadError = null;
      }
      if (debugInfoChanged) {
        updateAndPost();
      }
    }

    @Override
    public void onLoadError(
        EventTime eventTime,
        LoadEventInfo loadEventInfo,
        MediaLoadData mediaLoadData,
        IOException error,
        boolean wasCanceled) {
      if (!wasCanceled && isCurrentMediaLoad(eventTime)) {
        lastLoadError = errorMessage(error);
      }
      updateAndPost();
    }

    @Override
    public void onAudioPositionAdvancing(EventTime eventTime, long playoutStartSystemTimeMs) {
      if (lastAudioError != null) {
        lastAudioError = null;
        updateAndPost();
      }
    }

    @Override
    public void onRenderedFirstFrame(EventTime eventTime, Object output, long renderTimeMs) {
      if (lastVideoError != null) {
        lastVideoError = null;
        updateAndPost();
      }
    }

    @Override
    public void onAudioSinkError(EventTime eventTime, Exception audioSinkError) {
      lastAudioError = errorMessage(audioSinkError);
      updateAndPost();
    }

    @Override
    public void onAudioCodecError(EventTime eventTime, Exception audioCodecError) {
      lastAudioError = errorMessage(audioCodecError);
      updateAndPost();
    }

    @Override
    public void onVideoCodecError(EventTime eventTime, Exception videoCodecError) {
      lastVideoError = errorMessage(videoCodecError);
      updateAndPost();
    }

    // Runnable implementation.

    @Override
    public void run() {
      updateAndPost();
    }
  }
}
