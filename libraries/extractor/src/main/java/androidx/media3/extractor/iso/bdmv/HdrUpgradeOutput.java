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
package androidx.media3.extractor.iso.bdmv;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.extractor.DiscardingTrackOutput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ForwardingExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

final class HdrUpgradeOutput extends ForwardingExtractorOutput {

  private final BdmvTsPayloadReaderFactory payloadReaderFactory;
  private final Map<Integer, DolbyVisionCombiningTrackOutput> dolbyVisionOutputsByPid;

  HdrUpgradeOutput(ExtractorOutput output, BdmvTsPayloadReaderFactory payloadReaderFactory) {
    super(output);
    this.payloadReaderFactory = payloadReaderFactory;
    dolbyVisionOutputsByPid = new HashMap<>();
  }

  @NonNull
  @Override
  public TrackOutput track(int id, @C.TrackType int type) {
    if (type != C.TRACK_TYPE_VIDEO) {
      return super.track(id, type);
    }
    @Nullable BdmvStreamContext context = payloadReaderFactory.getStreamContext(id);
    if (context == null) {
      return super.track(id, type);
    }
    @Nullable DolbyVisionCombiningTrackOutput existingOutput = dolbyVisionOutputsByPid.get(id);
    if (existingOutput != null) {
      return context.isDolbyVisionEnhancementLayer()
          ? existingOutput.enhancementLayerOutput
          : existingOutput.baseLayerOutput;
    }
    if (context.isDolbyVisionEnhancementLayer()) {
      @Nullable BdmvStreamContext baseContext = payloadReaderFactory.findBaseLayerContext(context);
      return baseContext != null
          ? getOrCreateDolbyVisionOutput(baseContext, context).enhancementLayerOutput
          : new DiscardingTrackOutput();
    }
    @Nullable
    BdmvStreamContext enhancementContext = payloadReaderFactory.findEnhancementLayerContext(id);
    if (enhancementContext != null) {
      return getOrCreateDolbyVisionOutput(context, enhancementContext).baseLayerOutput;
    }
    TrackOutput upstream = super.track(id, type);
    if (!BdmvConstants.isHdrDynamicRangeType(context.dynamicRangeType)) {
      return upstream;
    }
    return new HdrTrackOutput(upstream, context);
  }

  public void flush() {
    for (DolbyVisionCombiningTrackOutput output : new HashSet<>(dolbyVisionOutputsByPid.values())) {
      output.flush();
    }
  }

  public void reset() {
    for (DolbyVisionCombiningTrackOutput output : new HashSet<>(dolbyVisionOutputsByPid.values())) {
      output.reset();
    }
  }

  private DolbyVisionCombiningTrackOutput getOrCreateDolbyVisionOutput(
      BdmvStreamContext baseContext, BdmvStreamContext enhancementContext) {
    @Nullable
    DolbyVisionCombiningTrackOutput existing = dolbyVisionOutputsByPid.get(enhancementContext.pid);
    if (existing != null) {
      return existing;
    }
    TrackOutput upstream = super.track(baseContext.pid, C.TRACK_TYPE_VIDEO);
    DolbyVisionCombiningTrackOutput output =
        new DolbyVisionCombiningTrackOutput(upstream, enhancementContext);
    dolbyVisionOutputsByPid.put(baseContext.pid, output);
    dolbyVisionOutputsByPid.put(enhancementContext.pid, output);
    return output;
  }
}
