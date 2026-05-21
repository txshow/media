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
package androidx.media3.ui.danmaku.parser;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.ui.danmaku.Danmaku;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Parses iQIYI protobuf danmaku data. */
@UnstableApi
public final class IQIYIParser implements Parser {

  /** Shared instance. */
  public static final IQIYIParser INSTANCE = new IQIYIParser();

  private static final int WIRE_VARINT = 0;
  private static final int WIRE_64BIT = 1;
  private static final int WIRE_LEN = 2;
  private static final int WIRE_32BIT = 5;
  private static final int TAG_DANMU_ENTRY = (6 << 3) | WIRE_LEN;
  private static final int TAG_ENTRY_BULLET = (2 << 3) | WIRE_LEN;
  private static final int MAX_VARINT_BYTES = 10;

  private IQIYIParser() {}

  private static void parseDanmu(ByteBuffer buffer, List<Danmaku> output) {
    while (buffer.hasRemaining()) {
      int tag = (int) readVarint(buffer);
      if (tag == 0) {
        break;
      }
      int wireType = tag & 0x7;
      int fieldNumber = tag >>> 3;
      if (wireType == WIRE_LEN) {
        int length = (int) readVarint(buffer);
        if (length < 0 || length > buffer.remaining()) {
          break;
        }
        int entryEnd = buffer.position() + length;
        if (fieldNumber == 6) {
          int savedLimit = buffer.limit();
          buffer.limit(entryEnd);
          parseEntry(buffer, output);
          buffer.limit(savedLimit);
        }
        buffer.position(entryEnd);
      } else {
        skipField(buffer, wireType);
      }
    }
  }

  private static void parseEntry(ByteBuffer buffer, List<Danmaku> output) {
    while (buffer.hasRemaining()) {
      int tag = (int) readVarint(buffer);
      if (tag == 0) {
        break;
      }
      int wireType = tag & 0x7;
      int fieldNumber = tag >>> 3;
      if (wireType == WIRE_LEN) {
        int length = (int) readVarint(buffer);
        if (length < 0 || length > buffer.remaining()) {
          break;
        }
        int bulletEnd = buffer.position() + length;
        if (fieldNumber == 2) {
          int savedLimit = buffer.limit();
          buffer.limit(bulletEnd);
          @Nullable Danmaku danmaku = parseBulletInfo(buffer);
          buffer.limit(savedLimit);
          if (danmaku != null) {
            output.add(danmaku);
          }
        }
        buffer.position(bulletEnd);
      } else {
        skipField(buffer, wireType);
      }
    }
  }

  @Nullable
  private static Danmaku parseBulletInfo(ByteBuffer buffer) {
    String content = null;
    String timeString = null;
    String colorString = null;
    while (buffer.hasRemaining()) {
      int tag = (int) readVarint(buffer);
      if (tag == 0) {
        break;
      }
      int wireType = tag & 0x7;
      int fieldNumber = tag >>> 3;
      if (wireType == WIRE_LEN) {
        int length = (int) readVarint(buffer);
        if (length < 0 || length > buffer.remaining()) {
          break;
        }
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        String value = Util.fromUtf8Bytes(bytes);
        switch (fieldNumber) {
          case 2:
            content = value;
            break;
          case 6:
            timeString = value;
            break;
          case 8:
            colorString = value;
            break;
          default:
            break;
        }
      } else {
        skipField(buffer, wireType);
      }
    }
    if (content == null || content.isEmpty()) {
      return null;
    }
    if (timeString == null || timeString.isEmpty()) {
      return null;
    }
    long timeMs;
    try {
      timeMs = (long) (Double.parseDouble(timeString) * 1000L);
    } catch (NumberFormatException e) {
      return null;
    }
    return new Danmaku(content, timeMs, Danmaku.TYPE_SCROLL, parseColor(colorString), 0f);
  }

  private static int parseColor(@Nullable String colorString) {
    if (colorString == null || colorString.isEmpty()) {
      return 0xFFFFFFFF;
    }
    try {
      String normalizedColor = colorString.trim();
      if (normalizedColor.startsWith("#")) {
        normalizedColor = normalizedColor.substring(1);
      }
      if (normalizedColor.length() == 6) {
        return 0xFF000000 | Integer.parseInt(normalizedColor, 16);
      }
      return 0xFF000000 | (int) (Long.parseLong(normalizedColor) & 0xFFFFFFL);
    } catch (NumberFormatException e) {
      return 0xFFFFFFFF;
    }
  }

  private static long readVarint(ByteBuffer buffer) {
    long result = 0;
    int shift = 0;
    while (buffer.hasRemaining()) {
      int byteValue = buffer.get() & 0xFF;
      result |= (long) (byteValue & 0x7F) << shift;
      shift += 7;
      if ((byteValue & 0x80) == 0) {
        return result;
      }
      if (shift >= 64) {
        break;
      }
    }
    return result;
  }

  private static void skipField(ByteBuffer buffer, int wireType) {
    if (!buffer.hasRemaining()) {
      return;
    }
    switch (wireType) {
      case WIRE_VARINT:
        if (!skipVarint(buffer)) {
          buffer.position(buffer.limit());
        }
        break;
      case WIRE_64BIT:
        if (!skipBytes(buffer, 8)) {
          buffer.position(buffer.limit());
        }
        break;
      case WIRE_LEN:
        long length = readVarint(buffer);
        if (length >= 0 && length <= buffer.remaining()) {
          buffer.position(buffer.position() + (int) length);
        } else {
          buffer.position(buffer.limit());
        }
        break;
      case WIRE_32BIT:
        if (!skipBytes(buffer, 4)) {
          buffer.position(buffer.limit());
        }
        break;
      default:
        buffer.position(buffer.limit());
        break;
    }
  }

  private static boolean skipBytes(ByteBuffer buffer, int length) {
    if (buffer.remaining() < length) {
      return false;
    }
    buffer.position(buffer.position() + length);
    return true;
  }

  @VisibleForTesting
  static boolean skipVarint(ByteBuffer buffer) {
    for (int i = 0; i < MAX_VARINT_BYTES && buffer.hasRemaining(); i++) {
      if ((buffer.get() & 0x80) == 0) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean sniff(InputStream inputStream, int sniffLength) throws IOException {
    int firstByte = inputStream.read();
    if (firstByte != TAG_DANMU_ENTRY) {
      return false;
    }
    long entryLength = 0;
    int shift = 0;
    boolean terminated = false;
    for (int i = 0; i < MAX_VARINT_BYTES; i++) {
      int byteValue = inputStream.read();
      if (byteValue < 0) {
        return false;
      }
      entryLength |= (long) (byteValue & 0x7F) << shift;
      shift += 7;
      if ((byteValue & 0x80) == 0) {
        terminated = true;
        break;
      }
    }
    if (!terminated || entryLength <= 0) {
      return false;
    }
    int entryFirstByte = inputStream.read();
    return entryFirstByte == TAG_ENTRY_BULLET;
  }

  @Override
  public List<Danmaku> parse(InputStream inputStream) throws IOException {
    ByteBuffer buffer = ByteBuffer.wrap(ByteStreams.toByteArray(inputStream));
    List<Danmaku> result = new ArrayList<>();
    parseDanmu(buffer, result);
    Collections.sort(result, Danmaku.BY_TIME);
    return result;
  }
}
