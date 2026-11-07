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

package com.google.ai.edge.gallery.companion.telegram

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.companion.*
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

/** Explicitly started, private-account-only Telegram bridge. Never restarts itself after reboot. */
class TelegramListenerService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var api: TelegramApi? = null
  private var listener: Job? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == STOP) {
      stopSelf()
      return START_NOT_STICKY
    }
    if (listener != null) return START_NOT_STICKY
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL, "Telegram companion", NotificationManager.IMPORTANCE_LOW)
    )
    val open =
      PendingIntent.getActivity(
        this,
        4202,
        Intent(this, CompanionActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
      )
    val stop =
      PendingIntent.getService(
        this,
        4203,
        Intent(this, TelegramListenerService::class.java).setAction(STOP),
        PendingIntent.FLAG_IMMUTABLE,
      )
    val notification =
      NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Companion is listening on Telegram")
        .setContentText("Only your configured private account can access it.")
        .setContentIntent(open)
        .setOngoing(true)
        .addAction(android.R.drawable.ic_delete, "Stop", stop)
        .build()
    if (Build.VERSION.SDK_INT >= 34)
      startForeground(4202, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
    else startForeground(4202, notification)
    running.value = true
    listener =
      scope.launch {
        val store = CompanionStore.get(this@TelegramListenerService)
        try {
          val credentials = TelegramCredentialStore(this@TelegramListenerService).read()
          check(credentials.ownerId > 0 && credentials.token.isNotBlank()) {
            "Save Telegram settings first."
          }
          val client = TelegramApi(credentials.token).also { api = it }
          client.call("getMe")
          val webhook =
            client.call("getWebhookInfo").jsonObject["url"]?.jsonPrimitive?.content.orEmpty()
          if (webhook.isNotEmpty()) throw TelegramApiException(409)
          if (store.state.value.telegramOffset < 0) {
            // Ignore the pre-connection backlog. New messages after Start are handled normally.
            val latest =
              client
                .call(
                  "getUpdates",
                  buildJsonObject {
                    put("offset", -1)
                    put("timeout", 0)
                    put("limit", 1)
                    put("allowed_updates", buildJsonArray { add("message") })
                  },
                )
                .jsonArray
                .lastOrNull()
                ?.jsonObject
                ?.get("update_id")
                ?.jsonPrimitive
                ?.longOrNull
            store.update { it.copy(telegramOffset = latest?.plus(1) ?: 0) }
          }
          store.update { it.copy(status = "Telegram connected. Send a new message to your bot.") }
          var failures = 0
          while (isActive) {
            try {
              finishPending(client, credentials.ownerId)
              flush(client, credentials.ownerId)
              tick()
              val updates = client.updates(store.state.value.telegramOffset)
              for (update in updates) {
                ensureActive()
                val objectValue = update.jsonObject
                val id = objectValue["update_id"]?.jsonPrimitive?.longOrNull ?: continue
                if (id < store.state.value.telegramOffset) continue
                val input = TelegramPolicy.authorizedInput(objectValue, credentials.ownerId)
                store.update {
                  it.copy(
                    telegramOffset = id + 1,
                    telegramPending = input,
                    awaitingReply = if (input != null) false else it.awaitingReply,
                  )
                }
                finishPending(client, credentials.ownerId)
                flush(client, credentials.ownerId)
              }
              failures = 0
            } catch (cancelled: CancellationException) {
              throw cancelled
            } catch (error: TelegramApiException) {
              store.update { it.copy(status = error.message.orEmpty()) }
              if (error.code == 401 || error.code == 409 || error.code == 403) break
              failures++
              delay(if (error.code == 429) 60000 else minOf(60000L, 5000L * failures))
            }
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          store.update {
            it.copy(status = "Telegram listener stopped. Check your saved settings and connection.")
          }
        } finally {
          stopSelf()
        }
      }
    return START_NOT_STICKY
  }

  private suspend fun finishPending(client: TelegramApi, owner: Long) {
    val store = CompanionStore.get(this)
    val pending = store.state.value.telegramPending ?: return
    if (pending.chatId != owner) {
      store.update { it.copy(telegramPending = null) }
      return
    }
    val reply =
      try {
        when (TelegramPolicy.command(pending.text)) {
          "/start",
          "/help" ->
            "I'm your Gallery companion. Messages share your phone's personality and memory. Commands: /remember FACT, /forget EXACT FACT, /diary, /help. Chats travel through Telegram; inference stays on your phone."
          "/diary" -> {
            val today = CompanionPolicy.date(System.currentTimeMillis())
            if (store.state.value.diary.none { it.date == today }) CompanionRunner.diary(this)
            store.state.value.diary.lastOrNull { it.date == today }?.text
              ?: "Chat with me first today."
          }
          else -> {
            val command = TelegramPolicy.command(pending.text)
            val text =
              if (command == "/remember" || command == "/forget")
                command + " " + pending.text.substringAfter(' ', "")
              else pending.text
            CompanionRunner.chat(this, text)
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        "I couldn't complete that request. Check Companion on your phone: choose a model and close any regular Gallery chat model, then try again."
      }
    currentCoroutineContext().ensureActive()
    store.update {
      it.copy(
        telegramPending = null,
        telegramOutbox =
          it.telegramOutbox + TelegramDelivery(UUID.randomUUID().toString(), owner, reply),
      )
    }
  }

  private suspend fun flush(client: TelegramApi, owner: Long) {
    val store = CompanionStore.get(this)
    while (true) {
      val delivery = store.state.value.telegramOutbox.firstOrNull() ?: return
      if (delivery.chatId != owner) {
        store.update {
          it.copy(telegramOutbox = it.telegramOutbox.filterNot { item -> item.id == delivery.id })
        }
        continue
      }
      val chunks = TelegramPolicy.chunks(delivery.text)
      // Store remaining chunks after each successful call, limiting duplicates after interruptions.
      var remaining = delivery.text
      for (chunk in chunks) {
        client.send(owner, chunk)
        remaining = remaining.substring(chunk.length)
        val next = remaining
        store.update {
          it.copy(
            telegramOutbox =
              it.telegramOutbox.mapNotNull { item ->
                if (item.id != delivery.id) item
                else if (next.isEmpty()) null else item.copy(text = next)
              }
          )
        }
      }
    }
  }

  private suspend fun tick() {
    val store = CompanionStore.get(this)
    val now = System.currentTimeMillis()
    if (now - store.state.value.telegramLastTick < 3 * 60 * 60 * 1000L) return
    val battery = getSystemService(BatteryManager::class.java)
    if (store.state.value.chargingOnly && !battery.isCharging) return
    if (
      !battery.isCharging &&
        battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) in 0..15
    )
      return
    store.update { it.copy(telegramLastTick = now) }
    try {
      if (CompanionPolicy.diaryDue(store.state.value, now))
        CompanionRunner.diary(this, automatic = true)
      if (
        store.state.value.telegramCheckIns &&
          CompanionPolicy.canCheckIn(store.state.value, now) &&
          Random.nextDouble() < 0.4
      )
        CompanionRunner.checkIn(this)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      store.update {
        it.copy(
          status = "Telegram connected; automatic Companion task deferred until a later cycle."
        )
      }
    }
  }

  override fun onDestroy() {
    scope.cancel()
    api?.close()
    running.value = false
    super.onDestroy()
  }

  companion object {
    private const val CHANNEL = "telegram-companion"
    const val STOP = "stop-telegram-companion"
    private val running = MutableStateFlow(false)
    val isRunning = running.asStateFlow()
  }
}
