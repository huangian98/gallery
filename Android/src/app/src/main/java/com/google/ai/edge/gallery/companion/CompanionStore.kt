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

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** App-private, atomic local storage. Excluded from Android backup via noBackupFilesDir. */
class CompanionStore private constructor(context: Context) {
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }
  private val file = AtomicFile(File(context.noBackupFilesDir, "companion.json"))
  private val mutable =
    MutableStateFlow(
      try {
        json.decodeFromString<CompanionState>(
          file.openRead().bufferedReader().use { it.readText() }
        )
      } catch (_: FileNotFoundException) {
        CompanionState()
      }
    )
  val state = mutable.asStateFlow()

  @Synchronized
  fun update(transform: (CompanionState) -> CompanionState) {
    val next = transform(mutable.value)
    val output = file.startWrite()
    try {
      output.write(json.encodeToString(next).toByteArray(Charsets.UTF_8))
      file.finishWrite(output)
      mutable.value = next
    } catch (error: Exception) {
      file.failWrite(output)
      throw error
    }
  }

  fun append(role: String, text: String) = update {
    it.copy(
      messages = (it.messages + CompanionEntry(role, text.take(8000))).takeLast(100),
      awaitingReply = if (role == "user") false else it.awaitingReply,
    )
  }

  companion object {
    @Volatile private var instance: CompanionStore? = null

    fun get(context: Context): CompanionStore =
      instance
        ?: synchronized(this) {
          instance ?: CompanionStore(context.applicationContext).also { instance = it }
        }
  }
}
