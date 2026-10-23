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
package androidx.media3.decoder.ffmpeg;

import java.nio.ByteBuffer;

/** Converts Dolby Vision profile 7 enhancement-layer RPU data to a base-layer-compatible form. */
/* package-private */ final class FfmpegDolbyVisionP7Converter {

  private long nativeHandle;

  public FfmpegDolbyVisionP7Converter(Object output, int profile) {
    // Stub constructor; native initialization is performed by the native layer.
  }

  public static native boolean ffmpegIsDolbyVisionP7ConversionAvailable(int profile);

  private static native long ffmpegCreateDolbyVisionP7Converter(
      int width, int height, boolean useP010, byte[] dolbyVisionConfig);

  private static native int ffmpegConvertDolbyVisionP7(
      long handle, ByteBuffer input, int inputSize, int outputStride);

  private static native void ffmpegFlushDolbyVisionP7Converter(long handle);

  private static native void ffmpegReleaseDolbyVisionP7Converter(long handle);
}
