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
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.companion.telegram.TelegramCredentialStore
import com.google.ai.edge.gallery.companion.telegram.TelegramCredentials
import com.google.ai.edge.gallery.companion.telegram.TelegramListenerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

class CompanionViewModel(application: Application) : AndroidViewModel(application) {
  private val store = CompanionStore.get(application)
  val state = store.state
  private val mutableBusy = MutableStateFlow(false)
  val busy = mutableBusy.asStateFlow()

  private val savedToken = MutableStateFlow(false)
  private val savedOwner = MutableStateFlow("")
  val telegramHasToken = savedToken.asStateFlow()
  val telegramOwner = savedOwner.asStateFlow()

  init {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        refreshTelegram()
      } catch (_: Exception) {
        store.update {
          it.copy(
            status = "Could not unlock Telegram settings. Remove the connection and save it again."
          )
        }
      }
    }
  }

  private fun refreshTelegram() {
    val credentials = TelegramCredentialStore(getApplication()).read()
    savedToken.value = credentials.token.isNotEmpty()
    savedOwner.value = credentials.ownerId.takeIf { it > 0 }?.toString().orEmpty()
  }

  fun saveTelegram(token: String, owner: String) = run {
    check(!TelegramListenerService.isRunning.value) {
      "Stop the listener before changing settings."
    }
    CompanionRunner.operation.withLock {
      val credentials = TelegramCredentialStore(getApplication())
      val existing = credentials.read()
      credentials.save(
        TelegramCredentials(token.ifBlank { existing.token }, owner.toLongOrNull() ?: 0)
      )
      store.update {
        it.copy(
          telegramOffset = -1,
          telegramPending = null,
          telegramOutbox = emptyList(),
          status =
            "Telegram settings saved. Start the listener, then send a new message to your bot.",
        )
      }
      refreshTelegram()
    }
  }

  fun startTelegram() {
    try {
      ContextCompat.startForegroundService(
        getApplication(),
        Intent(getApplication(), TelegramListenerService::class.java),
      )
    } catch (_: Exception) {
      configure {
        it.copy(status = "Android could not start the listener. Keep Companion open and try again.")
      }
    }
  }

  fun stopTelegram() {
    getApplication<Application>()
      .stopService(Intent(getApplication(), TelegramListenerService::class.java))
  }

  fun clearTelegram() = run {
    stopTelegram()
    CompanionRunner.operation.withLock {
      TelegramCredentialStore(getApplication()).clear()
      store.update {
        it.copy(
          telegramOffset = -1,
          telegramPending = null,
          telegramOutbox = emptyList(),
          telegramCheckIns = false,
          status = "Telegram connection removed.",
        )
      }
      refreshTelegram()
    }
  }

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
    stopTelegram()
    TelegramCredentialStore(getApplication()).clear()
    refreshTelegram()
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
