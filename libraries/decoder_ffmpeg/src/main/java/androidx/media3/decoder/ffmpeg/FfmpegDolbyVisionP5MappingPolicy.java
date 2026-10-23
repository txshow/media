package androidx.media3.decoder.ffmpeg;

import static androidx.media3.common.util.CodecSpecificDataUtil.getDolbyVisionCsd;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import java.util.Locale;

/**
 * Policy deciding whether a Dolby Vision stream can be mapped onto the profile-5 GPU pipeline
 * (profile-5 bitstream passed to the GPU with the EL/RPU stripped). Ported 1:1 from the official
 * obfuscated {@code FfmpegDolbyVisionP5MappingPolicy} ({@code r8-map-id-c17cc7ab...}).
 */
final class FfmpegDolbyVisionP5MappingPolicy {

  private FfmpegDolbyVisionP5MappingPolicy() {}

  /**
   * Returns whether the format is a Dolby Vision stream whose base layer can be rendered through
   * the profile-5 pipeline (profile 5 signaled by either the codec private data record or the
   * {@code codecs} string, and the enhancement layer present).
   */
  public static boolean isProfile5Mappable(Format format) {
    if (!MimeTypes.VIDEO_DOLBY_VISION.equals(format.sampleMimeType)) {
      return false;
    }
    int profile = -1;
    String codecs = format.codecs;
    if (MimeTypes.VIDEO_DOLBY_VISION.equals(format.sampleMimeType) && codecs != null) {
      String[] codecList = codecs.split(",");
      for (int i = 0; i < codecList.length; i++) {
        String[] parts = codecList[i].trim().toLowerCase(Locale.US).split("\\.");
        if (parts.length >= 3
            && (parts[0].equals("dvhe") || parts[0].equals("dvh1"))
            && parts[1].length() == 2
            && Character.isDigit(parts[1].charAt(0))
            && Character.isDigit(parts[1].charAt(1))) {
          profile = Integer.parseInt(parts[1]);
          break;
        }
      }
    }
    byte[] csd = getDolbyVisionCsd(format);
    if (csd != null) {
      int dvdProfile = (csd[2] & 254) >> 1;
      return dvdProfile == 5
          && !((profile != -1 && profile != dvdProfile) || csd == null || (csd[3] & 4) == 0);
    }
    if (format.initializationData.size() <= 2 && profile == 5) {
      return true;
    }
    return false;
  }
}
