/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.extractor.iso.bdmv;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.TrackOutput;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Combines Dolby Vision base- and enhancement-layer access units by presentation timestamp. */
final class DolbyVisionCombiningTrackOutput {

  private static final int MAX_PENDING_SAMPLE_COUNT = 16;

  public final TrackOutput baseLayerOutput;
  public final TrackOutput enhancementLayerOutput;

  private final TrackOutput output;
  private final HdrTrackOutput dolbyVisionOutput;
  private final LayerOutput baseLayer;
  private final LayerOutput enhancementLayer;
  private final ArrayDeque<Sample> baseSamples;
  private final ArrayDeque<Sample> enhancementSamples;
  @Nullable private Format baseFormat;
  @Nullable private Format enhancementFormat;
  private int formatRevision;
  private int lastSentFormatRevision;
  private boolean lastSentDolbyVisionFormat;

  DolbyVisionCombiningTrackOutput(TrackOutput output, BdmvStreamContext enhancementContext) {
    enhancementContext.markCombinedDolbyVisionEnhancementLayer();
    this.output = output;
    dolbyVisionOutput = new HdrTrackOutput(output, enhancementContext);
    baseSamples = new ArrayDeque<>();
    enhancementSamples = new ArrayDeque<>();
    baseLayer = new LayerOutput(/* isBaseLayer= */ true);
    enhancementLayer = new LayerOutput(/* isBaseLayer= */ false);
    baseLayerOutput = baseLayer;
    enhancementLayerOutput = enhancementLayer;
    lastSentFormatRevision = -1;
  }

  public void flush() {
    drainMatchedSamples();
    while (!baseSamples.isEmpty()) {
      writeSample(baseSamples.remove(), /* enhancementSample= */ null);
    }
    enhancementSamples.clear();
  }

  public void reset() {
    baseSamples.clear();
    enhancementSamples.clear();
    baseLayer.reset();
    enhancementLayer.reset();
  }

  private void queueSample(boolean isBaseLayer, Sample sample) {
    (isBaseLayer ? baseSamples : enhancementSamples).add(sample);
    drainMatchedSamples();
    if (baseSamples.size() >= MAX_PENDING_SAMPLE_COUNT) {
      writeSample(baseSamples.remove(), /* enhancementSample= */ null);
    }
    if (enhancementSamples.size() >= MAX_PENDING_SAMPLE_COUNT) {
      enhancementSamples.remove();
    }
  }

  private void drainMatchedSamples() {
    while (!baseSamples.isEmpty() && !enhancementSamples.isEmpty()) {
      Sample baseSample = baseSamples.element();
      Sample enhancementSample = enhancementSamples.element();
      if (baseSample.timeUs == enhancementSample.timeUs) {
        baseSamples.remove();
        enhancementSamples.remove();
        writeSample(baseSample, enhancementSample);
      } else if (baseSample.timeUs < enhancementSample.timeUs) {
        writeSample(baseSamples.remove(), /* enhancementSample= */ null);
      } else {
        enhancementSamples.remove();
      }
    }
  }

  private void writeSample(Sample baseSample, @Nullable Sample enhancementSample) {
    ensureOutputFormat(/* dolbyVision= */ enhancementSample != null);
    output.sampleData(new ParsableByteArray(baseSample.data), baseSample.data.length);
    int size = baseSample.data.length;
    if (enhancementSample != null) {
      output.sampleData(
          new ParsableByteArray(enhancementSample.data), enhancementSample.data.length);
      size += enhancementSample.data.length;
    }
    output.sampleMetadata(
        baseSample.timeUs, baseSample.flags, size, /* offset= */ 0, baseSample.cryptoData);
  }

  private void ensureOutputFormat(boolean dolbyVision) {
    Format format = baseFormat;
    if (format == null) {
      throw new IllegalStateException("Dolby Vision sample received before base format");
    }
    if (formatRevision == lastSentFormatRevision && dolbyVision == lastSentDolbyVisionFormat) {
      return;
    }
    if (dolbyVision) {
      dolbyVisionOutput.format(withEnhancementInitializationData(format));
    } else {
      output.format(format);
    }
    lastSentFormatRevision = formatRevision;
    lastSentDolbyVisionFormat = dolbyVision;
  }

  private Format withEnhancementInitializationData(Format format) {
    Format enhancementFormat = this.enhancementFormat;
    if (enhancementFormat == null || enhancementFormat.initializationData.isEmpty()) {
      return format;
    }
    byte[] enhancementCsd = enhancementFormat.initializationData.get(0);
    if (enhancementCsd.length == 0) {
      return format;
    }
    List<byte[]> initializationData = new ArrayList<>(format.initializationData);
    if (initializationData.isEmpty()) {
      initializationData.add(Arrays.copyOf(enhancementCsd, enhancementCsd.length));
    } else {
      byte[] baseCsd = initializationData.get(0);
      byte[] combinedCsd = Arrays.copyOf(baseCsd, baseCsd.length + enhancementCsd.length);
      System.arraycopy(enhancementCsd, 0, combinedCsd, baseCsd.length, enhancementCsd.length);
      initializationData.set(0, combinedCsd);
    }
    return format.buildUpon().setInitializationData(initializationData).build();
  }

  private final class LayerOutput implements TrackOutput {

    private final boolean isBaseLayer;
    private final ByteArrayOutputStream pendingData;

    public LayerOutput(boolean isBaseLayer) {
      this.isBaseLayer = isBaseLayer;
      pendingData = new ByteArrayOutputStream();
    }

    public void reset() {
      pendingData.reset();
    }

    @Override
    public void durationUs(long durationUs) {
      if (isBaseLayer) {
        output.durationUs(durationUs);
      }
    }

    @Override
    public void format(@NonNull Format format) {
      if (isBaseLayer) {
        baseFormat = format;
      } else {
        enhancementFormat = format;
      }
      formatRevision++;
    }

    @Override
    public int sampleData(
        DataReader input, int length, boolean allowEndOfInput, @SampleDataPart int sampleDataPart)
        throws IOException {
      byte[] data = new byte[length];
      int bytesRead = input.read(data, 0, length);
      if (bytesRead == C.RESULT_END_OF_INPUT) {
        if (allowEndOfInput) {
          return C.RESULT_END_OF_INPUT;
        }
        throw new EOFException();
      }
      pendingData.write(data, 0, bytesRead);
      return bytesRead;
    }

    @Override
    public void sampleData(ParsableByteArray data, int length, @SampleDataPart int sampleDataPart) {
      pendingData.write(data.getData(), data.getPosition(), length);
      data.skipBytes(length);
    }

    @Override
    public void sampleMetadata(
        long timeUs,
        @C.BufferFlags int flags,
        int size,
        int offset,
        @Nullable CryptoData cryptoData) {
      byte[] bufferedData = pendingData.toByteArray();
      int sampleStart = bufferedData.length - offset - size;
      if (sampleStart < 0) {
        throw new IllegalStateException("Invalid Dolby Vision sample bounds");
      }
      byte[] sampleData =
          sampleStart == 0 && size == bufferedData.length
              ? bufferedData
              : Arrays.copyOfRange(bufferedData, sampleStart, sampleStart + size);
      pendingData.reset();
      if (offset > 0) {
        pendingData.write(bufferedData, bufferedData.length - offset, offset);
      }
      queueSample(
          isBaseLayer, new Sample(timeUs, flags, sampleData, isBaseLayer ? cryptoData : null));
    }
  }

  private static final class Sample {

    public final long timeUs;
    public final @C.BufferFlags int flags;
    public final byte[] data;
    @Nullable public final TrackOutput.CryptoData cryptoData;

    public Sample(
        long timeUs,
        @C.BufferFlags int flags,
        byte[] data,
        @Nullable TrackOutput.CryptoData cryptoData) {
      this.timeUs = timeUs;
      this.flags = flags;
      this.data = data;
      this.cryptoData = cryptoData;
    }
  }
}
