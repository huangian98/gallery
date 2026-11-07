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

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.companion.CompanionViewModel

@Composable
fun TelegramSettings(model: CompanionViewModel, busy: Boolean) {
  val running by TelegramListenerService.isRunning.collectAsState()
  val hasToken by model.telegramHasToken.collectAsState()
  val owner by model.telegramOwner.collectAsState()
  val state by model.state.collectAsState()
  var token by remember { mutableStateOf("") }
  var ownerText by remember(owner) { mutableStateOf(owner) }
  Text("Telegram", style = MaterialTheme.typography.titleMedium)
  Text(
    "Use a bot created with @BotFather. Only the configured numeric user ID in a private chat can access your shared Companion history and memory. Telegram carries the messages; Gemma runs on this phone."
  )
  OutlinedTextField(
    value = token,
    onValueChange = { token = it.trim() },
    modifier = Modifier.fillMaxWidth(),
    label = { Text(if (hasToken) "Bot token (saved; leave empty to keep)" else "Bot token") },
    visualTransformation = PasswordVisualTransformation(),
    singleLine = true,
    enabled = !running && !busy,
  )
  OutlinedTextField(
    value = ownerText,
    onValueChange = { ownerText = it.filter(Char::isDigit).take(20) },
    modifier = Modifier.fillMaxWidth(),
    label = { Text("Your numeric Telegram user ID") },
    singleLine = true,
    enabled = !running && !busy,
  )
  Button(
    onClick = {
      model.saveTelegram(token, ownerText)
      token = ""
    },
    enabled = !running && !busy,
  ) {
    Text("Save Telegram settings")
  }
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(onClick = { model.startTelegram() }, enabled = hasToken && !running && !busy) {
      Text("Start listener")
    }
    OutlinedButton(onClick = { model.stopTelegram() }, enabled = running) { Text("Stop listener") }
  }
  Text(
    if (running) "Listener active. Send your bot a new message in Telegram."
    else "Listener stopped."
  )
  Row {
    Text("Send Companion check-ins through Telegram", Modifier.weight(1f))
    Switch(
      checked = state.telegramCheckIns,
      onCheckedChange = { enabled -> model.configure { it.copy(telegramCheckIns = enabled) } },
      enabled = hasToken && !busy,
    )
  }
  Text(
    "Also enable Occasional check-ins above. Commands: /help, /remember FACT, /forget EXACT FACT, /diary. Diary entries stay on-device unless you request /diary. Stop the listener before changing accounts or bot credentials."
  )
  Text(
    "Start keeps a visible notification. Android may suspend networking or stop the service; reopen Companion and Start again if needed. It does not restart at boot. Only one listener may use this bot; existing webhooks are not removed automatically."
  )
  OutlinedButton(
    onClick = {
      token = ""
      model.clearTelegram()
    },
    enabled = !running && !busy,
  ) {
    Text("Remove Telegram connection")
  }
}
