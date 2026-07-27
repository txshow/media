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
package androidx.media3.extractor.mmt;

import androidx.media3.common.C;

/** Converts MMT NTP timestamps to a stream-relative microsecond timebase. */
/* package */ final class MmtTimestampAdjuster {

  private static final long NTP_UNIX_EPOCH_OFFSET_SECONDS = 2_208_988_800L;
  private static final long UINT32_RANGE = 1L << 32;

  private long timestampOriginUs;
  private long ntpAnchor;

  public MmtTimestampAdjuster() {
    timestampOriginUs = C.TIME_UNSET;
    ntpAnchor = C.TIME_UNSET;
  }

  public void reset() {
    timestampOriginUs = C.TIME_UNSET;
    ntpAnchor = C.TIME_UNSET;
  }

  public void setNtpAnchor(long ntpAnchor) {
    if (ntpAnchor != 0) {
      this.ntpAnchor = ntpAnchor;
    }
  }

  public boolean hasNtpAnchor() {
    return ntpAnchor != C.TIME_UNSET;
  }

  public boolean hasTimestampOrigin() {
    return timestampOriginUs != C.TIME_UNSET;
  }

  public long getNtpAnchor() {
    return ntpAnchor;
  }

  public long adjustNtpTimestamp(long ntpTimestamp) {
    return adjustAbsoluteTimeUs(ntpToUnixTimeUs(ntpTimestamp));
  }

  public long adjustNtpTimestamp(long ntpTimestamp, long offsetUs) {
    return adjustAbsoluteTimeUs(ntpToUnixTimeUs(ntpTimestamp) + offsetUs);
  }

  public long adjustShortNtpTimestamp(long shortNtpTimestamp, long ntpAnchor) {
    long anchorSeconds = ntpAnchor >>> 32;
    long seconds = (anchorSeconds & ~0xFFFFL) | ((shortNtpTimestamp >>> 16) & 0xFFFFL);
    if (seconds - anchorSeconds > 0x8000L) {
      seconds -= 0x10000L;
    } else if (anchorSeconds - seconds > 0x8000L) {
      seconds += 0x10000L;
    }
    long fraction = (shortNtpTimestamp & 0xFFFFL) << 16;
    long ntpTimestamp = (seconds << 32) | fraction;
    return adjustNtpTimestamp(ntpTimestamp);
  }

  private long adjustAbsoluteTimeUs(long absoluteTimeUs) {
    if (timestampOriginUs == C.TIME_UNSET) {
      timestampOriginUs = absoluteTimeUs;
    }
    return absoluteTimeUs - timestampOriginUs;
  }

  private static long ntpToUnixTimeUs(long ntpTimestamp) {
    long seconds = (ntpTimestamp >>> 32) - NTP_UNIX_EPOCH_OFFSET_SECONDS;
    long fraction = ntpTimestamp & 0xFFFFFFFFL;
    return seconds * C.MICROS_PER_SECOND + (fraction * C.MICROS_PER_SECOND) / UINT32_RANGE;
  }
}
