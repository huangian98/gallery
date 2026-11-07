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

import com.google.ai.edge.gallery.companion.telegram.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TelegramPolicyTest {
  private val owner = 7136094800L

  private fun update(
    from: Long = owner,
    chat: Long = owner,
    type: String = "private",
    bot: Boolean = false,
    text: String = "Hello",
  ) = buildJsonObject {
    put("update_id", 25)
    put(
      "message",
      buildJsonObject {
        put(
          "from",
          buildJsonObject {
            put("id", from)
            put("is_bot", bot)
          },
        )
        put(
          "chat",
          buildJsonObject {
            put("id", chat)
            put("type", type)
          },
        )
        put("text", text)
      },
    )
  }

  @Test
  fun acceptsOnlyConfiguredPrivateOwnerWith64BitIds() {
    assertEquals(TelegramInput(25, owner, "Hello"), TelegramPolicy.authorizedInput(update(), owner))
    assertNull(TelegramPolicy.authorizedInput(update(), 0))
    assertNull(TelegramPolicy.authorizedInput(update(from = owner + 1), owner))
    assertNull(TelegramPolicy.authorizedInput(update(chat = owner + 1), owner))
  }

  @Test
  fun groupsAndBotMessagesCannotReadCompanionMemory() {
    assertNull(TelegramPolicy.authorizedInput(update(type = "group"), owner))
    assertNull(TelegramPolicy.authorizedInput(update(type = "supergroup"), owner))
    assertNull(TelegramPolicy.authorizedInput(update(bot = true), owner))
  }

  @Test
  fun ignoresEmptyNonTextAndEditedMessages() {
    assertNull(TelegramPolicy.authorizedInput(update(text = "  "), owner))
    assertNull(TelegramPolicy.authorizedInput(buildJsonObject { put("update_id", 25) }, owner))
    assertNull(
      TelegramPolicy.authorizedInput(
        buildJsonObject { put("edited_message", update()["message"]!!) },
        owner,
      )
    )
  }

  @Test
  fun oversizedInputsAreBounded() {
    assertEquals(
      4000,
      TelegramPolicy.authorizedInput(update(text = "x".repeat(10000)), owner)!!.text.length,
    )
  }

  @Test
  fun longRepliesPreserveUnicodeAndFitTelegramLimits() {
    val text = "🙂".repeat(5000) + "中文"
    val chunks = TelegramPolicy.chunks(text)
    assertEquals(text, chunks.joinToString(""))
    assertTrue(
      chunks.all {
        it.length <= 3500 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate()
      }
    )
    assertTrue(TelegramPolicy.chunks("").isEmpty())
  }

  @Test
  fun commandMentionDoesNotBecomeAnInstruction() {
    assertEquals("/remember", TelegramPolicy.command("/remember@MyBot Likes hiking"))
    assertEquals("/diary", TelegramPolicy.command("/DIARY@MyBot"))
    assertEquals("hello", TelegramPolicy.command("Hello there"))
  }

  @Test
  fun pendingWorkAndOutboxSurviveSerializationWithoutCredentials() {
    val state =
      CompanionState(
        telegramOffset = 26,
        telegramPending = TelegramInput(25, owner, "Hello"),
        telegramOutbox = listOf(TelegramDelivery("one", owner, "Reply")),
        telegramCheckIns = true,
      )
    val encoded = Json.encodeToString(state)
    assertEquals(state, Json.decodeFromString<CompanionState>(encoded))
    assertFalse(encoded.contains("token", ignoreCase = true))
  }

  @Test
  fun apiErrorsNeverExposeTransportUrlsOrTokens() {
    assertEquals("Bot token rejected. Save a new token.", TelegramApiException(401).message)
    assertFalse(TelegramApiException(0).message!!.contains("https://"))
  }
}
