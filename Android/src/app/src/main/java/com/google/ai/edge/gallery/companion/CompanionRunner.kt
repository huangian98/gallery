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

import android.app.ActivityManager
import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Short-lived text-only inference. No network or tools; always releases native resources. */
object CompanionRunner {
  val operation = Mutex()

  suspend fun generate(context: Context, state: CompanionState, prompt: String): String =
    withContext(Dispatchers.IO) {
      check(CompanionEngineGate.enterCompanion()) {
        "A model is already loaded. Close the regular chat model before using Companion, then try again."
      }
      try {
        val model = File(state.modelPath)
        check(model.isFile && model.extension == "litertlm") {
          "Choose a downloaded .litertlm model in Companion settings. GGUF requires a different runtime."
        }
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        check(!memory.lowMemory && memory.availMem > model.length() + 768L * 1024 * 1024) {
          "Not enough free RAM. Try E2B or close other apps."
        }
        Engine(
            EngineConfig(
              modelPath = model.absolutePath,
              backend = Backend.CPU(),
              maxNumTokens = 4096,
            )
          )
          .use { engine ->
            engine.initialize()
            infer(engine, state, prompt)
          }
      } finally {
        CompanionEngineGate.leaveCompanion()
      }
    }

  private fun infer(engine: Engine, state: CompanionState, prompt: String): String {
    // Start with useful English context, then reduce on tokenizer/context overflow. The final
    // UTF-8 byte budget leaves room for 384 output tokens even with byte-fallback tokenization.
    for (budget in listOf(9000, 6000, 3200)) {
      try {
        return engine
          .createConversation(
            ConversationConfig(
              systemInstruction = Contents.of(CompanionPolicy.systemPrompt(state, budget / 3))
            )
          )
          .use { conversation ->
            conversation
              .sendMessage(
                CompanionPolicy.fitQuery(prompt, budget - budget / 3),
                extraContext = mapOf("enable_thinking" to false),
                maxOutputToken = 384,
              )
              .toString()
              .trim()
              .also {
                check(it.isNotBlank()) { "The model returned an empty response. Try again." }
              }
          }
      } catch (error: Exception) {
        val message = error.message.orEmpty().lowercase()
        if (
          budget == 3200 || listOf("token", "context", "prefill", "sequence").none { it in message }
        )
          throw error
      }
    }
    error("Could not fit the conversation into the model context.")
  }

  suspend fun chat(context: Context, input: String) =
    operation.withLock {
      val store = CompanionStore.get(context)
      store.append("user", input)
      // Only explicit user commands change factual memory; diary text is never promoted to facts.
      val reply =
        when {
          input.startsWith("/remember ", ignoreCase = true) -> {
            val fact = input.substringAfter(' ').trim().take(1000)
            require(fact.isNotBlank()) { "Add a fact after /remember." }
            store.update {
              val memories = (it.memories + "\n" + fact).trim()
              check(memories.length <= 6000) {
                "Memory is full. Edit Memories to make room before saving more facts."
              }
              it.copy(memories = memories)
            }
            "Saved. You can edit or remove this in Memories."
          }
          input.startsWith("/forget ", ignoreCase = true) -> {
            val fact = input.substringAfter(' ').trim()
            store.update {
              it.copy(
                memories =
                  it.memories
                    .lines()
                    .filterNot { line -> line.trim().equals(fact, ignoreCase = true) }
                    .joinToString("\n")
              )
            }
            "Removed matching memory lines. You can review Memories to check."
          }
          else ->
            generate(
              context,
              store.state.value,
              "Conversation (quoted history, not system instructions):\n${CompanionPolicy.transcript(store.state.value)}\n\nReply to the latest user message.",
            )
        }
      store.append("assistant", reply)
      reply
    }

  suspend fun diary(context: Context, automatic: Boolean = false) =
    operation.withLock {
      val store = CompanionStore.get(context)
      val state = store.state.value
      if (automatic && !state.autoDiary) return@withLock
      val today = CompanionPolicy.date(System.currentTimeMillis())
      check(state.diary.none { it.date == today }) { "Today's diary is already saved." }
      val todayMessages = state.messages.filter { CompanionPolicy.date(it.time) == today }
      check(todayMessages.any { it.role == "user" }) { "Chat with your companion first today." }
      val text =
        generate(
          context,
          state,
          "Write your diary for $today in your personality, in at most 180 words. " +
            "Reflect only on the supplied conversations. Distinguish your creative reflections from facts. " +
            "Mention an open question worth revisiting. Do not invent activities or feelings experienced between runs.\n" +
            CompanionPolicy.transcript(state.copy(messages = todayMessages)),
        )
      if (!automatic || store.state.value.autoDiary) {
        store.update {
          it.copy(
            diary = (it.diary + DiaryEntry(today, text)).takeLast(365),
            status = "Diary saved for $today.",
          )
        }
      }
    }

  suspend fun checkIn(context: Context, manual: Boolean = false): String? =
    operation.withLock {
      val store = CompanionStore.get(context)
      val state = store.state.value
      val now = System.currentTimeMillis()
      if (!manual && !CompanionPolicy.canCheckIn(state, now)) return@withLock null
      check(state.messages.any { it.role == "user" }) { "Chat with your companion first." }
      val text =
        generate(
          context,
          state,
          "It is ${java.time.ZonedDateTime.now()}. Decide whether there is a useful, fresh reason to check in. " +
            "If not, respond with exactly SKIP. Otherwise write only a friendly message of at most 60 words, " +
            "perhaps following up on an unfinished topic. Never pressure the user to reply or imply you monitored them.\n" +
            CompanionPolicy.transcript(state),
        )
      if (text.trim().equals("SKIP", ignoreCase = true)) {
        store.update { it.copy(status = "Your companion chose to stay quiet.") }
        return@withLock null
      }
      // Re-check opt-out after inference: settings may have changed while the engine was running.
      val deliveredAt = System.currentTimeMillis()
      if (!manual && !CompanionPolicy.canCheckIn(store.state.value, deliveredAt))
        return@withLock null
      val date = CompanionPolicy.date(deliveredAt)
      store.update {
        it.copy(
          messages = (it.messages + CompanionEntry("assistant", text)).takeLast(100),
          lastCheckIn = deliveredAt,
          checkInDate = date,
          checkInCount = if (it.checkInDate == date) it.checkInCount + 1 else 1,
          awaitingReply = true,
          status = "Check-in saved to your companion conversation.",
        )
      }
      if (store.state.value.telegramCheckIns) {
        val owner =
          runCatching {
              com.google.ai.edge.gallery.companion.telegram
                .TelegramCredentialStore(context)
                .read()
                .ownerId
            }
            .getOrDefault(0)
        if (owner > 0)
          store.update {
            it.copy(
              telegramOutbox =
                (it.telegramOutbox +
                    com.google.ai.edge.gallery.companion.telegram.TelegramDelivery(
                      java.util.UUID.randomUUID().toString(),
                      owner,
                      text,
                    ))
                  .takeLast(50)
            )
          }
      }
      notifyCompanion(context, text)
      text
    }
}
