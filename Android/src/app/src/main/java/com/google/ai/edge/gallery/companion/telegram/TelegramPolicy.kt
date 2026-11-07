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

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@Serializable data class TelegramInput(val updateId: Long, val chatId: Long, val text: String)

@Serializable data class TelegramDelivery(val id: String, val chatId: Long, val text: String)

object TelegramPolicy {
  fun authorizedInput(update: JsonObject, owner: Long): TelegramInput? {
    if (owner <= 0) return null
    val message = update["message"]?.jsonObject ?: return null
    val from = message["from"]?.jsonObject ?: return null
    val chat = message["chat"]?.jsonObject ?: return null
    if (
      from["is_bot"]?.jsonPrimitive?.content == "true" ||
        from["id"]?.jsonPrimitive?.longOrNull != owner ||
        chat["id"]?.jsonPrimitive?.longOrNull != owner ||
        chat["type"]?.jsonPrimitive?.content != "private"
    )
      return null
    val text =
      message["text"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val id = update["update_id"]?.jsonPrimitive?.longOrNull ?: return null
    return TelegramInput(id, owner, text.take(4000))
  }

  // Telegram limits sendMessage to 4096 characters; leave headroom and preserve surrogate pairs.
  fun chunks(text: String): List<String> {
    val result = mutableListOf<String>()
    var rest = text
    while (rest.isNotEmpty()) {
      var end = minOf(3500, rest.length)
      if (end < rest.length && rest[end - 1].isHighSurrogate()) end--
      result.add(rest.substring(0, end))
      rest = rest.substring(end)
    }
    return result
  }

  fun command(text: String): String = text.substringBefore(' ').substringBefore('@').lowercase()
}
