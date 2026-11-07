/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.companion

/** Prevents a second set of model weights loading alongside Gallery's regular LiteRT chat. */
object CompanionEngineGate {
  private val galleryModels = mutableSetOf<String>()
  private var companionActive = false

  @Synchronized
  fun enterGallery(model: String): Boolean {
    if (companionActive) return false
    galleryModels.add(model)
    return true
  }

  @Synchronized
  fun leaveGallery(model: String) {
    galleryModels.remove(model)
  }

  @Synchronized
  fun enterCompanion(): Boolean {
    if (companionActive || galleryModels.isNotEmpty()) return false
    companionActive = true
    return true
  }

  @Synchronized
  fun leaveCompanion() {
    companionActive = false
  }
}
