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
package androidx.media3.extractor.ts;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.util.SparseArray;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.container.NalUnitUtil;
import androidx.media3.extractor.Ac3Util;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ForwardingExtractorOutput;
import androidx.media3.extractor.ForwardingTrackOutput;
import androidx.media3.extractor.TrackOutput;
import java.io.EOFException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Applies HLS SAMPLE-AES identity decryption to supported TS samples. */
/* package */ final class HlsSampleAesExtractorOutput extends ForwardingExtractorOutput {

  private static final int AES_BLOCK_SIZE = 16;
  private static final int AUDIO_FRAME_CLEAR_BYTES = 16;
  private static final int AUDIO_FRAME_ENCRYPTED_DATA_MIN_BYTES = 32;
  private static final int AC3_SYNCFRAME_HEADER_SIZE = 6;
  private static final int AC3_SYNC_WORD_FIRST_BYTE = 0x0B;
  private static final int AC3_SYNC_WORD_SECOND_BYTE = 0x77;
  private static final int H264_NAL_CLEAR_BYTES = 32;
  private static final int H264_NAL_ENCRYPTED_BYTES = 16;
  private static final int H264_NAL_CLEAR_SKIP_BYTES = 144;

  private final ExtractorOutput extractorOutput;
  private final SparseArray<TrackOutput> trackOutputs;

  @Nullable private byte[] key;
  @Nullable private byte[] iv;

  HlsSampleAesExtractorOutput(ExtractorOutput delegate) {
    super(delegate);
    this.extractorOutput = delegate;
    trackOutputs = new SparseArray<>();
  }

  void setDecryptionData(@Nullable byte[] key, @Nullable byte[] iv) {
    checkArgument(key == null || key.length == AES_BLOCK_SIZE);
    checkArgument(key == null || (iv != null && iv.length == AES_BLOCK_SIZE));
    this.key = key == null ? null : Arrays.copyOf(key, key.length);
    this.iv = key == null ? null : Arrays.copyOf(checkNotNull(iv), iv.length);
  }

  @Override
  public TrackOutput track(int id, @C.TrackType int type) {
    TrackOutput trackOutput = trackOutputs.get(id);
    if (trackOutput == null) {
      trackOutput = new SampleAesTrackOutput(extractorOutput.track(id, type), this);
      trackOutputs.put(id, trackOutput);
    }
    return trackOutput;
  }

  private boolean hasDecryptionData() {
    return key != null && iv != null;
  }

  @Nullable
  private byte[] getKey() {
    return key;
  }

  @Nullable
  private byte[] getIv() {
    return iv;
  }

  private static final class SampleAesTrackOutput extends ForwardingTrackOutput {

    private final HlsSampleAesExtractorOutput decryptionData;
    private final ParsableByteArray outputBuffer;
    private final byte[] scratchAc3Header;
    private final Cipher cipher;

    private byte[] pendingData;
    private int pendingDataSize;
    private byte[] scratchNalUnit;
    private boolean needsSampleAesDecryption;
    @Nullable private String sampleMimeType;

    SampleAesTrackOutput(TrackOutput delegate, HlsSampleAesExtractorOutput decryptionData) {
      super(delegate);
      this.decryptionData = decryptionData;
      outputBuffer = new ParsableByteArray();
      pendingData = new byte[0];
      scratchNalUnit = new byte[0];
      scratchAc3Header = new byte[AC3_SYNCFRAME_HEADER_SIZE];
      try {
        cipher = Cipher.getInstance("AES/CBC/NoPadding");
      } catch (GeneralSecurityException e) {
        throw new IllegalStateException("Failed to initialize HLS SAMPLE-AES cipher", e);
      }
    }

    @Override
    public void format(Format format) {
      sampleMimeType = format.sampleMimeType;
      needsSampleAesDecryption =
          MimeTypes.VIDEO_H264.equals(sampleMimeType)
              || MimeTypes.AUDIO_AAC.equals(sampleMimeType)
              || isAc3SampleMimeType(sampleMimeType);
      super.format(format);
      if (!needsSampleAesDecryption && pendingDataSize > 0) {
        if (MimeTypes.isAudio(sampleMimeType) || MimeTypes.isVideo(sampleMimeType)) {
          throw new UnsupportedOperationException(
              "HLS SAMPLE-AES decryption is not supported for " + sampleMimeType);
        }
        outputBuffer.reset(pendingData, pendingDataSize);
        super.sampleData(outputBuffer, pendingDataSize);
        pendingDataSize = 0;
      }
    }

    @Override
    public int sampleData(
        DataReader input, int length, boolean allowEndOfInput, @SampleDataPart int sampleDataPart)
        throws IOException {
      if (!shouldBuffer(sampleDataPart)) {
        return super.sampleData(input, length, allowEndOfInput, sampleDataPart);
      }
      ensureCapacity(pendingDataSize + length);
      int bytesRead = input.read(pendingData, pendingDataSize, length);
      if (bytesRead == C.RESULT_END_OF_INPUT) {
        if (allowEndOfInput) {
          return C.RESULT_END_OF_INPUT;
        }
        throw new EOFException();
      }
      pendingDataSize += bytesRead;
      return bytesRead;
    }

    @Override
    public int sampleData(DataReader input, int length, boolean allowEndOfInput)
        throws IOException {
      return sampleData(input, length, allowEndOfInput, SAMPLE_DATA_PART_MAIN);
    }

    @Override
    public void sampleData(ParsableByteArray data, int length, @SampleDataPart int sampleDataPart) {
      if (!shouldBuffer(sampleDataPart)) {
        super.sampleData(data, length, sampleDataPart);
        return;
      }
      ensureCapacity(pendingDataSize + length);
      data.readBytes(pendingData, pendingDataSize, length);
      pendingDataSize += length;
    }

    @Override
    public void sampleData(ParsableByteArray data, int length) {
      sampleData(data, length, SAMPLE_DATA_PART_MAIN);
    }

    @Override
    public void sampleMetadata(
        long timeUs,
        @C.BufferFlags int flags,
        int size,
        int offset,
        @Nullable CryptoData cryptoData) {
      if (pendingDataSize == 0) {
        super.sampleMetadata(timeUs, flags, size, offset, cryptoData);
        return;
      }
      checkState(offset <= pendingDataSize);
      int sampleStart = pendingDataSize - offset - size;
      checkState(sampleStart >= 0);
      if (sampleStart > 0) {
        System.arraycopy(pendingData, sampleStart, pendingData, 0, size + offset);
        pendingDataSize = size + offset;
      }
      int decryptedSize = decryptSample(pendingData, size);
      outputBuffer.reset(pendingData, decryptedSize);
      super.sampleData(outputBuffer, decryptedSize);
      super.sampleMetadata(timeUs, flags, decryptedSize, /* offset= */ 0, cryptoData);
      if (offset > 0) {
        System.arraycopy(pendingData, pendingDataSize - offset, pendingData, 0, offset);
      }
      pendingDataSize = offset;
    }

    private boolean shouldBuffer(@SampleDataPart int sampleDataPart) {
      if (sampleDataPart != SAMPLE_DATA_PART_MAIN || !decryptionData.hasDecryptionData()) {
        return false;
      }
      if (sampleMimeType == null) {
        return true;
      }
      if (needsSampleAesDecryption) {
        return true;
      }
      if (MimeTypes.isAudio(sampleMimeType) || MimeTypes.isVideo(sampleMimeType)) {
        throw new UnsupportedOperationException(
            "HLS SAMPLE-AES decryption is not supported for " + sampleMimeType);
      }
      return false;
    }

    private void ensureCapacity(int requiredCapacity) {
      if (pendingData.length >= requiredCapacity) {
        return;
      }
      pendingData = Arrays.copyOf(pendingData, requiredCapacity * 2);
    }

    private int decryptSample(byte[] data, int size) {
      byte[] key = decryptionData.getKey();
      byte[] iv = decryptionData.getIv();
      checkState(key != null && key.length == AES_BLOCK_SIZE);
      checkState(iv != null && iv.length == AES_BLOCK_SIZE);
      if (MimeTypes.VIDEO_H264.equals(sampleMimeType)) {
        return decryptH264Sample(data, size, key, iv);
      } else if (MimeTypes.AUDIO_AAC.equals(sampleMimeType)) {
        decryptAacSample(data, size, key, iv);
      } else if (isAc3SampleMimeType(sampleMimeType)) {
        decryptAc3Sample(data, size, key, iv);
      }
      return size;
    }

    private int decryptH264Sample(byte[] data, int size, byte[] key, byte[] iv) {
      int readPosition = 0;
      int writePosition = 0;
      while (readPosition < size) {
        int startCodeLength = getStartCodeLength(data, readPosition, size);
        if (startCodeLength == 0) {
          if (writePosition != readPosition) {
            System.arraycopy(data, readPosition, data, writePosition, size - readPosition);
          }
          return writePosition + size - readPosition;
        }

        int nalDataStart = readPosition + startCodeLength;
        int nextNalStart = findStartCode(data, nalDataStart, size);
        if (nextNalStart == C.INDEX_UNSET) {
          nextNalStart = size;
        }
        int nalLength = nextNalStart - nalDataStart;
        int nalType = nalLength > 0 ? data[nalDataStart] & 0x1F : 0;
        int outputNalLength = nalLength;
        if ((nalType == NalUnitUtil.H264_NAL_UNIT_TYPE_NON_IDR
                || nalType == NalUnitUtil.H264_NAL_UNIT_TYPE_IDR)
            && nalLength > 48) {
          outputNalLength = unescapeNalUnit(data, nalDataStart, nalLength);
          decryptH264NalUnit(data, nalDataStart, outputNalLength, key, iv);
        }
        System.arraycopy(data, readPosition, data, writePosition, startCodeLength);
        System.arraycopy(
            data, nalDataStart, data, writePosition + startCodeLength, outputNalLength);
        writePosition += startCodeLength + outputNalLength;
        readPosition = nextNalStart;
      }
      return writePosition;
    }

    private int unescapeNalUnit(byte[] data, int offset, int length) {
      ensureScratchNalUnitCapacity(length);
      System.arraycopy(data, offset, scratchNalUnit, 0, length);
      int unescapedLength = NalUnitUtil.unescapeStream(scratchNalUnit, length);
      System.arraycopy(scratchNalUnit, 0, data, offset, unescapedLength);
      return unescapedLength;
    }

    private void ensureScratchNalUnitCapacity(int requiredCapacity) {
      if (scratchNalUnit.length >= requiredCapacity) {
        return;
      }
      scratchNalUnit = Arrays.copyOf(scratchNalUnit, requiredCapacity * 2);
    }

    private void decryptH264NalUnit(byte[] data, int offset, int length, byte[] key, byte[] iv) {
      int encryptedOffset = offset + H264_NAL_CLEAR_BYTES;
      int remainingBytes = length - H264_NAL_CLEAR_BYTES;
      byte[] nalIv = Arrays.copyOf(iv, AES_BLOCK_SIZE);
      while (remainingBytes > 0) {
        if (remainingBytes > H264_NAL_ENCRYPTED_BYTES) {
          decryptInPlace(data, encryptedOffset, H264_NAL_ENCRYPTED_BYTES, key, nalIv);
          encryptedOffset += H264_NAL_ENCRYPTED_BYTES;
          remainingBytes -= H264_NAL_ENCRYPTED_BYTES;
        }
        int clearBytesToSkip = Math.min(H264_NAL_CLEAR_SKIP_BYTES, remainingBytes);
        encryptedOffset += clearBytesToSkip;
        remainingBytes -= clearBytesToSkip;
      }
    }

    private void decryptAacSample(byte[] data, int size, byte[] key, byte[] iv) {
      decryptAudioFrame(data, /* frameOffset= */ 0, size, key, Arrays.copyOf(iv, AES_BLOCK_SIZE));
    }

    private void decryptAc3Sample(byte[] data, int size, byte[] key, byte[] iv) {
      byte[] syncFrameIv = Arrays.copyOf(iv, AES_BLOCK_SIZE);
      int syncFrameOffset = 0;
      while (syncFrameOffset < size) {
        syncFrameOffset = findAc3SyncFrame(data, syncFrameOffset, size);
        if (syncFrameOffset == C.INDEX_UNSET) {
          return;
        }
        checkState(syncFrameOffset + AC3_SYNCFRAME_HEADER_SIZE <= size);
        System.arraycopy(data, syncFrameOffset, scratchAc3Header, 0, AC3_SYNCFRAME_HEADER_SIZE);
        int syncFrameSize = Ac3Util.parseAc3SyncframeSize(scratchAc3Header);
        checkState(syncFrameSize != C.LENGTH_UNSET && syncFrameOffset + syncFrameSize <= size);
        decryptAudioFrame(data, syncFrameOffset, syncFrameSize, key, syncFrameIv);
        syncFrameOffset += syncFrameSize;
      }
    }

    private void decryptAudioFrame(
        byte[] data, int frameOffset, int frameSize, byte[] key, byte[] iv) {
      if (frameSize < AUDIO_FRAME_ENCRYPTED_DATA_MIN_BYTES) {
        return;
      }
      int encryptedOffset = frameOffset + AUDIO_FRAME_CLEAR_BYTES;
      int encryptedLength =
          ((frameSize - AUDIO_FRAME_CLEAR_BYTES) / AES_BLOCK_SIZE) * AES_BLOCK_SIZE;
      decryptInPlace(data, encryptedOffset, encryptedLength, key, iv);
    }

    private static int findAc3SyncFrame(byte[] data, int start, int limit) {
      for (int i = start; i + 1 < limit; i++) {
        if ((data[i] & 0xFF) == AC3_SYNC_WORD_FIRST_BYTE
            && (data[i + 1] & 0xFF) == AC3_SYNC_WORD_SECOND_BYTE) {
          return i;
        }
      }
      return C.INDEX_UNSET;
    }

    private void decryptInPlace(byte[] data, int offset, int length, byte[] key, byte[] iv) {
      byte[] nextIv = Arrays.copyOfRange(data, offset + length - AES_BLOCK_SIZE, offset + length);
      try {
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] decryptedData = cipher.doFinal(data, offset, length);
        System.arraycopy(decryptedData, 0, data, offset, decryptedData.length);
        System.arraycopy(nextIv, 0, iv, 0, AES_BLOCK_SIZE);
      } catch (GeneralSecurityException e) {
        throw new IllegalStateException("Failed to decrypt HLS SAMPLE-AES sample", e);
      }
    }

    private static int findStartCode(byte[] data, int start, int limit) {
      for (int i = start; i + 2 < limit; i++) {
        if (data[i] == 0 && data[i + 1] == 0) {
          if (data[i + 2] == 1) {
            return i;
          } else if (i + 3 < limit && data[i + 2] == 0 && data[i + 3] == 1) {
            return i;
          }
        }
      }
      return C.INDEX_UNSET;
    }

    private static int getStartCodeLength(byte[] data, int offset, int limit) {
      if (offset + 3 <= limit
          && data[offset] == 0
          && data[offset + 1] == 0
          && data[offset + 2] == 1) {
        return 3;
      }
      if (offset + 4 <= limit
          && data[offset] == 0
          && data[offset + 1] == 0
          && data[offset + 2] == 0
          && data[offset + 3] == 1) {
        return 4;
      }
      return 0;
    }

    private static boolean isAc3SampleMimeType(@Nullable String sampleMimeType) {
      return MimeTypes.AUDIO_AC3.equals(sampleMimeType)
          || MimeTypes.AUDIO_E_AC3.equals(sampleMimeType)
          || MimeTypes.AUDIO_E_AC3_JOC.equals(sampleMimeType);
    }
  }
}
