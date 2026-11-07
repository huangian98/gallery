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

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.google.ai.edge.gallery.companion.telegram.TelegramSettings
import com.google.ai.edge.gallery.ui.theme.GalleryTheme

class CompanionActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val model = ViewModelProvider(this)[CompanionViewModel::class.java]
    val names = intent.getStringArrayListExtra("modelNames") ?: arrayListOf()
    val paths = intent.getStringArrayListExtra("modelPaths") ?: arrayListOf()
    if (paths.isNotEmpty())
      model.configure { state ->
        val models =
          names.zip(paths).map { CompanionModel(it.first, it.second) }.distinctBy { it.path }
        state.copy(
          models = models,
          modelPath = state.modelPath.ifEmpty { models.firstOrNull()?.path ?: "" },
        )
      }
    setContent { GalleryTheme { CompanionScreen(model, onBack = { finish() }) } }
  }
}

@Composable
private fun CompanionScreen(model: CompanionViewModel, onBack: () -> Unit) {
  val state by model.state.collectAsState()
  val busy by model.busy.collectAsState()
  val context = LocalContext.current
  var tab by remember { mutableIntStateOf(0) }
  var input by remember { mutableStateOf("") }
  var personality by remember(state.personality) { mutableStateOf(state.personality) }
  var memories by remember(state.memories) { mutableStateOf(state.memories) }
  var confirmDelete by remember { mutableStateOf(false) }
  val notificationPermission =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      model.configure {
        it.copy(
          checkIns = granted,
          status =
            if (granted) "Check-ins enabled." else "Notifications denied; check-ins remain off.",
        )
      }
    }

  Surface(modifier = Modifier.fillMaxSize()) {
    Column(Modifier.safeDrawingPadding().padding(16.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Companion", style = MaterialTheme.typography.headlineMedium)
        TextButton(onClick = onBack) { Text("Back") }
      }
      Text(
        "Private personality, memory, check-ins and diary",
        style = MaterialTheme.typography.bodySmall,
      )
      Row {
        listOf("Chat", "Diary", "Settings").forEachIndexed { index, label ->
          TextButton(onClick = { tab = index }) { Text(if (tab == index) "• $label" else label) }
        }
      }
      Text(state.status, style = MaterialTheme.typography.bodySmall)
      if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
      when (tab) {
        0 -> {
          val scroll = rememberScrollState()
          LaunchedEffect(state.messages.size, busy) { scroll.animateScrollTo(scroll.maxValue) }
          Column(
            Modifier.weight(1f).verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            if (state.messages.isEmpty())
              Text(
                "Choose your model in Settings, then say hello. Use /remember followed by a fact to save it, or /forget followed by the exact fact to remove it."
              )
            state.messages.forEach { message ->
              Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                  Text(
                    if (message.role == "user") "You" else "Companion",
                    style = MaterialTheme.typography.labelMedium,
                  )
                  Text(message.text)
                }
              }
            }
          }
          OutlinedTextField(
            value = input,
            onValueChange = { input = it.take(4000) },
            label = { Text("Message") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4,
            enabled = !busy,
          )
          Button(
            onClick = {
              model.send(input.trim())
              input = ""
            },
            enabled = !busy && input.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text("Send")
          }
        }
        1 ->
          Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(
              "Generated reflections based on Companion chats. These entries are never treated as factual memories."
            )
            Button(onClick = { model.diary() }, enabled = !busy) { Text("Write today's diary") }
            state.diary.asReversed().forEach { entry ->
              Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                  Text(entry.date, style = MaterialTheme.typography.titleMedium)
                  Text(entry.text)
                }
              }
            }
          }
        2 ->
          Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text("Model", style = MaterialTheme.typography.titleMedium)
            Text(
              "Download a Gemma E2B .litertlm model in Gallery first. E4B is also supported. Companion uses text-only CPU inference with a 4,096-token context and releases the model after each task."
            )
            if (state.models.isEmpty())
              Text(
                "No downloaded LiteRT models found. Return to Gallery, download a model, then reopen Companion."
              )
            state.models.forEach { candidate ->
              Row(Modifier.fillMaxWidth()) {
                RadioButton(
                  selected = state.modelPath == candidate.path,
                  enabled = !busy,
                  onClick = { model.configure { it.copy(modelPath = candidate.path) } },
                )
                Text(candidate.name, Modifier.padding(top = 12.dp))
              }
            }
            OutlinedTextField(
              value = personality,
              onValueChange = { personality = it.take(4000) },
              label = { Text("Personality") },
              modifier = Modifier.fillMaxWidth(),
              minLines = 3,
              enabled = !busy,
            )
            OutlinedTextField(
              value = memories,
              onValueChange = { memories = it.take(6000) },
              label = { Text("Memories (one fact per line)") },
              modifier = Modifier.fillMaxWidth(),
              minLines = 4,
              enabled = !busy,
            )
            Button(
              onClick = {
                model.configure {
                  it.copy(
                    personality = personality,
                    memories = memories,
                    status = "Personality and memories saved.",
                  )
                }
              },
              enabled = !busy,
            ) {
              Text("Save personality and memories")
            }
            CompanionSwitch("Occasional check-ins", state.checkIns, !busy) { enabled ->
              if (enabled && Build.VERSION.SDK_INT >= 33 && !notificationsAllowed(context)) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
              } else model.configure { it.copy(checkIns = enabled) }
            }
            CompanionSwitch("Automatic nightly diary", state.autoDiary, !busy) { enabled ->
              model.configure { it.copy(autoDiary = enabled) }
            }
            CompanionSwitch("Background tasks only while charging", state.chargingOnly, !busy) {
              enabled ->
              model.configure { it.copy(chargingOnly = enabled) }
            }
            Text(
              "Automatic tasks are off by default. Check-ins run approximately every 3 hours, may choose silence, respect quiet hours (10 pm–9 am), and stop after an unanswered message. Maximum 2 per day, at least 6 hours apart. Android may delay tasks. Nightly diary runs after 8 pm when there was a conversation today. Background work skips while Gallery is open."
            )
            Text(
              "Only chats in this Companion area are used. Regular Gallery chats are separate. Close any regular chat model before using Companion. Memories, the last 100 messages and up to 365 diary entries stay on this device and are excluded from Android backup."
            )
            OutlinedButton(onClick = { model.checkIn() }, enabled = !busy) {
              Text("Try a check-in now")
            }
            Text(
              "The test bypasses quiet hours and daily limits. Your companion may still choose silence.",
              style = MaterialTheme.typography.bodySmall,
            )
            TelegramSettings(model, busy)
            OutlinedButton(onClick = { confirmDelete = true }, enabled = !busy) {
              Text("Delete companion data")
            }
          }
      }
    }
  }
  if (confirmDelete)
    AlertDialog(
      onDismissRequest = { confirmDelete = false },
      title = { Text("Delete companion data?") },
      text = {
        Text(
          "This removes your personality, memories, chats and diary entries, and turns off automatic tasks."
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            confirmDelete = false
            model.clear()
          }
        ) {
          Text("Delete")
        }
      },
      dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
private fun CompanionSwitch(
  label: String,
  checked: Boolean,
  enabled: Boolean,
  onChange: (Boolean) -> Unit,
) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, Modifier.weight(1f).padding(top = 12.dp))
    Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
  }
}
