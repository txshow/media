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
package androidx.media3.container;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.util.CodecSpecificDataUtil;
import androidx.media3.common.util.ParsableByteArray;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link DolbyVisionConfig}. */
@RunWith(AndroidJUnit4.class)
public final class DolbyVisionConfigTest {

  @Test
  public void parse_withFewerThanFourBytes_returnsNull() {
    byte[] configuration =
        CodecSpecificDataUtil.buildDolbyVisionInitializationData(
            /* profile= */ 8, /* level= */ 1);

    for (int length = 0; length < 4; length++) {
      assertThat(
              DolbyVisionConfig.parse(
                  new ParsableByteArray(Arrays.copyOf(configuration, length))))
          .isNull();
    }
  }

  @Test
  public void parse_withUnsupportedMajorVersion_returnsNull() {
    byte[] configuration =
        CodecSpecificDataUtil.buildDolbyVisionInitializationData(
            /* profile= */ 8, /* level= */ 1);
    configuration[0] = 0;

    assertThat(DolbyVisionConfig.parse(new ParsableByteArray(configuration))).isNull();
  }

  @Test
  public void parse_withFourBytes_returnsConfiguration() {
    byte[] configuration =
        CodecSpecificDataUtil.buildDolbyVisionInitializationData(
            /* profile= */ 8, /* level= */ 1);

    DolbyVisionConfig result =
        DolbyVisionConfig.parse(new ParsableByteArray(Arrays.copyOf(configuration, 4)));

    assertThat(result).isNotNull();
    assertThat(result.profile).isEqualTo(8);
    assertThat(result.level).isEqualTo(1);
    assertThat(result.codecs).isEqualTo("dvhe.08.01");
  }
}
