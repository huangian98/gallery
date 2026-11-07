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

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CompanionPolicyTest {
  private val zone = ZoneId.of("Australia/Sydney")

  private fun at(hour: Int, day: Int = 7) =
    ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()

  private fun ready() =
    CompanionState(checkIns = true, messages = listOf(CompanionEntry("user", "My project", at(10))))

  @Test
  fun quietHoursAndOptOut() {
    assertFalse(CompanionPolicy.canCheckIn(ready(), at(8), zone))
    assertTrue(CompanionPolicy.canCheckIn(ready(), at(9), zone))
    assertTrue(CompanionPolicy.canCheckIn(ready(), at(21), zone))
    assertFalse(CompanionPolicy.canCheckIn(ready(), at(22), zone))
    assertFalse(CompanionPolicy.canCheckIn(ready().copy(checkIns = false), at(12), zone))
  }

  @Test
  fun unansweredMessagesAndCooldownPreventRepeatedPrompts() {
    assertFalse(CompanionPolicy.canCheckIn(ready().copy(awaitingReply = true), at(18), zone))
    assertFalse(CompanionPolicy.canCheckIn(ready().copy(lastCheckIn = at(13)), at(18), zone))
    assertTrue(CompanionPolicy.canCheckIn(ready().copy(lastCheckIn = at(12)), at(18), zone))
    assertFalse(CompanionPolicy.canCheckIn(ready().copy(messages = emptyList()), at(18), zone))
  }

  @Test
  fun dailyLimitResetsOnLocalDate() {
    val state = ready().copy(checkInDate = "2026-11-07", checkInCount = 2)
    assertFalse(CompanionPolicy.canCheckIn(state, at(18), zone))
    assertTrue(CompanionPolicy.canCheckIn(state, at(18, day = 8), zone))
  }

  @Test
  fun diaryRequiresOptInTodaysConversationAndNoExistingEntry() {
    val state = ready().copy(autoDiary = true)
    assertFalse(CompanionPolicy.diaryDue(state, at(19), zone))
    assertTrue(CompanionPolicy.diaryDue(state, at(20), zone))
    assertFalse(CompanionPolicy.diaryDue(state.copy(autoDiary = false), at(20), zone))
    assertFalse(
      CompanionPolicy.diaryDue(
        state.copy(diary = listOf(DiaryEntry("2026-11-07", "Saved"))),
        at(20),
        zone,
      )
    )
    assertFalse(CompanionPolicy.diaryDue(state, at(20, day = 8), zone))
  }

  @Test
  fun diaryCannotBecomeFactualPromptMemory() {
    val state =
      ready()
        .copy(
          memories = "Likes hiking",
          diary = listOf(DiaryEntry("2026-11-07", "FICTIONAL_EVENT")),
        )
    val prompt = CompanionPolicy.systemPrompt(state)
    assertTrue(prompt.contains("Likes hiking"))
    assertFalse(prompt.contains("FICTIONAL_EVENT"))
  }

  @Test
  fun contextIsBounded() {
    val messages = (1..100).map { CompanionEntry("user", "x".repeat(8000)) }
    assertTrue(CompanionPolicy.transcript(ready().copy(messages = messages)).length <= 12000)
  }

  @Test
  fun multilingualPromptBudgetPreservesLatestMessage() {
    val state = ready().copy(personality = "好".repeat(4000), memories = "🙂".repeat(3000))
    val system = CompanionPolicy.systemPrompt(state, 1066)
    val query = CompanionPolicy.fitQuery("TASK\n" + "好".repeat(10000) + "LATEST_USER", 2134)
    assertTrue(system.toByteArray().size <= 1066)
    assertTrue(query.toByteArray().size <= 2134)
    assertTrue(query.startsWith("TASK"))
    assertTrue(query.endsWith("LATEST_USER"))
  }

  @Test
  fun savedStateRoundTripsPersonalityMemoriesMessagesAndDiary() {
    val state =
      ready()
        .copy(
          personality = "Thoughtful with dry humour",
          memories = "Likes hiking\nSpeaks 中文",
          diary = listOf(DiaryEntry("2026-11-07", "A creative reflection")),
          autoDiary = true,
          chargingOnly = false,
          awaitingReply = true,
          checkInDate = "2026-11-07",
          checkInCount = 1,
        )
    assertEquals(state, Json.decodeFromString<CompanionState>(Json.encodeToString(state)))
  }

  @Test
  fun closingOneGalleryModelDoesNotUnlockAnother() {
    try {
      assertTrue(CompanionEngineGate.enterGallery("first"))
      assertTrue(CompanionEngineGate.enterGallery("second"))
      CompanionEngineGate.leaveGallery("first")
      assertFalse(CompanionEngineGate.enterCompanion())
      CompanionEngineGate.leaveGallery("second")
      assertTrue(CompanionEngineGate.enterCompanion())
    } finally {
      CompanionEngineGate.leaveGallery("first")
      CompanionEngineGate.leaveGallery("second")
      CompanionEngineGate.leaveCompanion()
    }
  }

  @Test
  fun companionCannotLoadAlongsideGallery() {
    try {
      assertTrue(CompanionEngineGate.enterGallery("test"))
      assertFalse(CompanionEngineGate.enterCompanion())
      CompanionEngineGate.leaveGallery("test")
      assertTrue(CompanionEngineGate.enterCompanion())
      assertFalse(CompanionEngineGate.enterGallery("test"))
      assertFalse(CompanionEngineGate.enterCompanion())
    } finally {
      CompanionEngineGate.leaveGallery("test")
      CompanionEngineGate.leaveCompanion()
    }
  }
}
