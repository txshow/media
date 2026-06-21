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
package androidx.media3.mpvplayer.options;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;
import static androidx.media3.mpvplayer.nativebridge.MpvConstants.OPT_USER_AGENT;
import static androidx.media3.mpvplayer.nativebridge.MpvConstants.OPT_VIDEO_OUTPUT;
import static androidx.media3.mpvplayer.nativebridge.MpvConstants.PROP_HWDEC;
import static androidx.media3.mpvplayer.nativebridge.MpvConstants.VALUE_NO;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.media3.common.DolbyVisionOutputPolicy;
import androidx.media3.mpvplayer.MpvPlayerConfig;
import androidx.media3.mpvplayer.audio.MpvAudioCapabilities;
import com.google.common.annotations.VisibleForTesting;

@RestrictTo(LIBRARY_GROUP)
public final class MpvOptions {

  private static final String OPT_AUDIO_SPDIF = "audio-spdif";
  private static final String OPT_VIDEO_DECODER_OPTIONS = "vd-lavc-o";
  private static final String OPT_VIDEO_DECODER_OPTIONS_APPEND = "vd-lavc-o-append";
  private static final String VIDEO_DECODER_OPTION_DOLBY_VISION_SINK_SUPPORT = "dovi_sink_support";
  private static final String VIDEO_OUTPUT_NULL = "null";

  private final MpvPlayerConfig config;
  @Nullable private final String supportedPassthroughCodecs;
  @Nullable private final Boolean dolbyVisionSinkSupported;
  private String hardwareDecode;
  @Nullable private String deferredVideoOutput;
  private boolean hardwareDecodeEnabled;

  public MpvOptions(Context context, MpvPlayerConfig config) {
    this(
        config,
        MpvAudioCapabilities.getSupportedPassthroughCodecs(
            context, config.getRequestedPassthroughCodecs()),
        resolveDolbyVisionSinkSupport(context, config));
  }

  @VisibleForTesting
  MpvOptions(MpvPlayerConfig config, @Nullable String supportedPassthroughCodecs) {
    this(config, supportedPassthroughCodecs, /* dolbyVisionSinkSupported= */ null);
  }

  @VisibleForTesting
  MpvOptions(
      MpvPlayerConfig config,
      @Nullable String supportedPassthroughCodecs,
      @Nullable Boolean dolbyVisionSinkSupported) {
    this.config = config;
    this.supportedPassthroughCodecs = supportedPassthroughCodecs;
    this.dolbyVisionSinkSupported = dolbyVisionSinkSupported;
    this.hardwareDecode = config.getDefaultHardwareDecode();
  }

  private static @Nullable Boolean resolveDolbyVisionSinkSupport(
      Context context, MpvPlayerConfig config) {
    return !config.isDolbyVisionOutputPolicySet()
        ? null
        : DolbyVisionOutputPolicy.isNativeOutputAllowed(
            context, config.getDolbyVisionOutputPolicy());
  }

  private static boolean isHardwareDecodeValue(@Nullable String value) {
    return !TextUtils.isEmpty(value) && !VALUE_NO.equals(value);
  }

  private @Nullable String getHardwareDecodeValue() {
    if (!hardwareDecodeEnabled) {
      return VALUE_NO;
    }
    return TextUtils.isEmpty(hardwareDecode) ? null : hardwareDecode;
  }

  public void applyPreInit(StringOptionWriter strings) {
    config.applyPreInit(strings);
    applyAudioPassthrough(strings);
    String defaultUserAgent = config.getDefaultUserAgent();
    if (!TextUtils.isEmpty(defaultUserAgent)) {
      strings.set(OPT_USER_AGENT, defaultUserAgent);
    }
  }

  public void applyAppOwned(StringOptionWriter strings) {
    config.applyAppOwned(strings);
    applyAudioPassthrough(strings);
  }

  /** Defers Android VO creation from player initialization to the next loadfile. */
  public void deferVideoOutputUntilLoad(
      @Nullable String configuredVideoOutput, StringOptionWriter strings) {
    if (configuredVideoOutput == null
        || configuredVideoOutput.isEmpty()
        || VIDEO_OUTPUT_NULL.equals(configuredVideoOutput)) {
      deferredVideoOutput = null;
      return;
    }
    deferredVideoOutput = configuredVideoOutput;
    strings.set(OPT_VIDEO_OUTPUT, VIDEO_OUTPUT_NULL);
  }

  public boolean isAudioPassthroughEnabled() {
    return !TextUtils.isEmpty(supportedPassthroughCodecs);
  }

  public boolean isVideoSharpnessSupported() {
    return config.isVideoSharpnessSupported();
  }

  public @Nullable String onInitialized(
      boolean hardwareDecodeEnabled, @Nullable String hardwareDecode) {
    if (hardwareDecode != null && isHardwareDecodeValue(hardwareDecode)) {
      this.hardwareDecode = hardwareDecode;
    }
    this.hardwareDecodeEnabled = hardwareDecodeEnabled;
    return getHardwareDecodeValue();
  }

  public @Nullable String setHardwareDecodeEnabled(
      boolean hardwareDecodeEnabled, @Nullable String currentHardwareDecode) {
    if (!hardwareDecodeEnabled
        && this.hardwareDecodeEnabled
        && currentHardwareDecode != null
        && isHardwareDecodeValue(currentHardwareDecode)) {
      hardwareDecode = currentHardwareDecode;
    }
    this.hardwareDecodeEnabled = hardwareDecodeEnabled;
    return getHardwareDecodeValue();
  }

  public void onNativeSessionEnded() {
    hardwareDecode = config.getDefaultHardwareDecode();
    deferredVideoOutput = null;
    hardwareDecodeEnabled = false;
  }

  public void addPerFileOptions(Uri uri, @Nullable String mimeType, MpvPerFileOptions options) {
    applyAppOwned(options::add);
    if (deferredVideoOutput != null) {
      options.set(OPT_VIDEO_OUTPUT, deferredVideoOutput);
    }
    config.applyNetworkOptions(uri, mimeType, options::add);
    @Nullable String hardwareDecode = getHardwareDecodeValue();
    if (hardwareDecode != null) {
      options.add(PROP_HWDEC, hardwareDecode);
    }
    if (hardwareDecodeEnabled && dolbyVisionSinkSupported != null) {
      options.add(
          OPT_VIDEO_DECODER_OPTIONS_APPEND,
          VIDEO_DECODER_OPTION_DOLBY_VISION_SINK_SUPPORT
              + "="
              + (dolbyVisionSinkSupported ? "1" : "0"));
    }
  }

  public void applyRuntimeDolbyVisionSinkSupport(KeyValueOptionWriter writer) {
    if (dolbyVisionSinkSupported != null) {
      writer.set(
          OPT_VIDEO_DECODER_OPTIONS,
          VIDEO_DECODER_OPTION_DOLBY_VISION_SINK_SUPPORT,
          hardwareDecodeEnabled ? (dolbyVisionSinkSupported ? "1" : "0") : null);
    }
  }

  private void applyAudioPassthrough(StringOptionWriter strings) {
    if (supportedPassthroughCodecs != null) {
      strings.set(OPT_AUDIO_SPDIF, supportedPassthroughCodecs);
    }
  }

  public interface StringOptionWriter {

    void set(String name, String value);
  }

  public interface DoubleOptionWriter {

    void set(String name, double value);
  }

  public interface KeyValueOptionWriter {

    void set(String name, String key, @Nullable String value);
  }
}
