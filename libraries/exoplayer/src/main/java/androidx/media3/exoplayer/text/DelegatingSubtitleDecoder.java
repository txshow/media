/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.text;

import androidx.annotation.Nullable;
import androidx.media3.extractor.text.SimpleSubtitleDecoder;
import androidx.media3.extractor.text.Subtitle;
import androidx.media3.extractor.text.SubtitleParser;
import com.google.common.base.Charsets;
import java.nio.charset.Charset;
import java.util.regex.Pattern;
import org.mozilla.universalchardet.UniversalDetector;

/**
 * Wrapper around a {@link SubtitleParser} that can be used instead of any current {@link
 * SimpleSubtitleDecoder} subclass. The main {@link #decode(byte[], int, boolean)} method will be
 * delegating the parsing of the data to the underlying {@link SubtitleParser} instance and its
 * {@link SubtitleParser#parseToLegacySubtitle(byte[], int, int)} implementation.
 *
 * <p>Functionally, once each XXXDecoder class is refactored to be a XXXParser that implements
 * {@link SubtitleParser}, the following should be equivalent:
 *
 * <ul>
 *   <li>DelegatingSubtitleDecoder("XXX", new XXXParser())
 *   <li>XXXDecoder()
 * </ul>
 *
 * <p>Or in the case with initialization data:
 *
 * <ul>
 *   <li>DelegatingSubtitleDecoder("XXX", new XXXParser(initializationData))
 *   <li>XXXDecoder(initializationData)
 * </ul>
 */
// TODO(b/289983417): this will only be used in the old decoding flow (Decoder after SampleQueue)
// while we maintain dual architecture. Once we fully migrate to the pre-SampleQueue flow, it can be
// deprecated and later deleted.
/* package */ final class DelegatingSubtitleDecoder extends SimpleSubtitleDecoder {

  private static final Pattern XML_ENCODING_ATTRIBUTE =
      Pattern.compile("(?i)^\uFEFF?(\\s*<\\?xml\\b[^>]*\\bencoding\\s*=\\s*)([\"'])[^\"']*([\"'])");

  private final SubtitleParser subtitleParser;
  private final UniversalDetector detector;
  private final boolean detectCharset;
  private final boolean inputIsTtml;

  public DelegatingSubtitleDecoder(String name, SubtitleParser subtitleParser) {
    this(name, subtitleParser, /* detectCharset= */ false, /* inputIsTtml= */ false);
  }

  public DelegatingSubtitleDecoder(
      String name, SubtitleParser subtitleParser, boolean detectCharset) {
    this(name, subtitleParser, detectCharset, /* inputIsTtml= */ false);
  }

  public DelegatingSubtitleDecoder(
      String name, SubtitleParser subtitleParser, boolean detectCharset, boolean inputIsTtml) {
    super(name);
    this.detectCharset = detectCharset;
    this.inputIsTtml = inputIsTtml;
    this.subtitleParser = subtitleParser;
    this.detector = new UniversalDetector(null);
  }

  @Override
  protected Subtitle decode(byte[] data, int length, boolean reset) {
    if (reset) {
      subtitleParser.reset();
    }
    if (!detectCharset) {
      return subtitleParser.parseToLegacySubtitle(data, /* offset= */ 0, length);
    }
    byte[] convertedData = maybeConvertToUtf8(data, length);
    int convertedLength = convertedData == data ? length : convertedData.length;
    return subtitleParser.parseToLegacySubtitle(convertedData, /* offset= */ 0, convertedLength);
  }

  private byte[] maybeConvertToUtf8(byte[] data, int length) {
    detector.reset();
    detector.handleData(data, 0, length);
    detector.dataEnd();
    @Nullable String detectedCharset = detector.getDetectedCharset();
    if (detectedCharset == null) {
      return data;
    }
    if (!detectedCharset.startsWith("UTF")) {
      String utf8Text = new String(data, /* offset= */ 0, length, Charset.forName(detectedCharset));
      if (inputIsTtml) {
        utf8Text = XML_ENCODING_ATTRIBUTE.matcher(utf8Text).replaceFirst("$1$2UTF-8$3");
      }
      data = utf8Text.getBytes(Charsets.UTF_8);
    }
    return data;
  }
}
