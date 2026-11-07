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

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CompanionViewModel(application: Application) : AndroidViewModel(application) {
  private val store = CompanionStore.get(application)
  val state = store.state
  private val mutableBusy = MutableStateFlow(false)
  val busy = mutableBusy.asStateFlow()

  fun configure(transform: (CompanionState) -> CompanionState) {
    viewModelScope.launch(Dispatchers.IO) {
      store.update(transform)
      CompanionSchedule.update(getApplication(), store.state.value)
    }
  }

  fun run(action: suspend () -> Unit) {
    if (mutableBusy.value) return
    mutableBusy.value = true
    viewModelScope.launch(Dispatchers.IO) {
      try {
        action()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        store.update {
          it.copy(status = error.message?.take(300) ?: "Could not complete the task.")
        }
      } finally {
        mutableBusy.value = false
      }
    }
  }

  fun send(text: String) = run {
    CompanionRunner.chat(getApplication(), text)
    store.update { it.copy(status = "Conversation saved on this device.") }
  }

  fun diary() = run { CompanionRunner.diary(getApplication()) }

  fun checkIn() = run { CompanionRunner.checkIn(getApplication(), manual = true) }

  fun clear() = run {
    // Serialize deletion with inference so no in-flight diary or reply can repopulate deleted data.
    CompanionRunner.operation.lock()
    try {
      store.update {
        CompanionState(
          models = it.models,
          modelPath = it.modelPath,
          status = "Companion data deleted. Automatic tasks are off.",
        )
      }
      CompanionSchedule.update(getApplication(), store.state.value)
      NotificationManagerCompat.from(getApplication()).cancel(4201)
    } finally {
      CompanionRunner.operation.unlock()
    }
  }
}
