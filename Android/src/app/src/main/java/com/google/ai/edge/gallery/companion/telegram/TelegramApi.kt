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

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

class TelegramApi(private val token: String) : AutoCloseable {
  private val client =
    HttpClient(Android) {
      install(HttpTimeout) {
        requestTimeoutMillis = 45000
        connectTimeoutMillis = 15000
        socketTimeoutMillis = 40000
      }
    }

  suspend fun call(method: String, body: JsonObject = buildJsonObject {}): JsonElement {
    try {
      val response =
        client.post("https://api.telegram.org/bot$token/$method") {
          contentType(ContentType.Application.Json)
          setBody(body.toString())
        }
      val data = Json.parseToJsonElement(response.bodyAsText()).jsonObject
      if (data["ok"]?.jsonPrimitive?.booleanOrNull != true) {
        // Never expose Ktor exceptions or URLs: the URL contains the secret bot token.
        val code = data["error_code"]?.jsonPrimitive?.intOrNull
        throw TelegramApiException(code ?: response.status.value)
      }
      return data["result"] ?: JsonNull
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (known: TelegramApiException) {
      throw known
    } catch (_: Exception) {
      throw TelegramApiException(0)
    }
  }

  suspend fun updates(offset: Long): JsonArray =
    call(
        "getUpdates",
        buildJsonObject {
          put("offset", offset)
          put("timeout", 25)
          put("limit", 10)
          put("allowed_updates", buildJsonArray { add("message") })
        },
      )
      .jsonArray

  suspend fun send(chat: Long, text: String) {
    call(
      "sendMessage",
      buildJsonObject {
        put("chat_id", chat)
        put("text", text)
      },
    )
  }

  override fun close() {
    client.close()
  }
}

class TelegramApiException(val code: Int) :
  Exception(
    when (code) {
      401 -> "Bot token rejected. Save a new token."
      409 -> "Another listener or webhook is using this bot. Stop it before starting Gallery."
      403 -> "The bot cannot message this account. Open the bot in Telegram and send /start."
      429 -> "Telegram rate limit; waiting before retrying."
      else -> "Telegram connection failed; waiting before retrying."
    }
  )
