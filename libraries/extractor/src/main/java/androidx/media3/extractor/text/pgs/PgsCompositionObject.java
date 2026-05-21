/*
 * Copyright (C) 2018 The Android Open Source Project
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
package androidx.media3.extractor.text.pgs;

import androidx.media3.common.C;

/** PGS object reference and placement in the current presentation composition. */
/* package */ final class PgsCompositionObject {

  int objectId;
  int windowId;
  int x;
  int y;
  boolean cropped;
  int cropX;
  int cropY;
  int cropWidth;
  int cropHeight;

  PgsCompositionObject() {
    clear();
  }

  void set(int objectId, int windowId, int x, int y) {
    this.objectId = objectId;
    this.windowId = windowId;
    this.x = x;
    this.y = y;
    cropped = false;
    cropX = 0;
    cropY = 0;
    cropWidth = 0;
    cropHeight = 0;
  }

  void setCrop(int cropX, int cropY, int cropWidth, int cropHeight) {
    cropped = true;
    this.cropX = cropX;
    this.cropY = cropY;
    this.cropWidth = cropWidth;
    this.cropHeight = cropHeight;
  }

  void clear() {
    set(C.INDEX_UNSET, C.INDEX_UNSET, /* x= */ 0, /* y= */ 0);
  }
}
