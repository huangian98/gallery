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

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable data class TelegramCredentials(val token: String = "", val ownerId: Long = 0)

/** Credentials never enter chat history, prompts, logs, backups or repository files. */
class TelegramCredentialStore(context: Context) {
  private val file = AtomicFile(File(context.noBackupFilesDir, "telegram-credentials.enc"))

  private fun key(): SecretKey {
    val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (keys.getKey(ALIAS, null) as? SecretKey)?.let {
      return it
    }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
      .apply {
        init(
          KeyGenParameterSpec.Builder(
              ALIAS,
              KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        )
      }
      .generateKey()
  }

  fun read(): TelegramCredentials =
    synchronized(LOCK) {
      val bytes =
        try {
          file.openRead().use { it.readBytes() }
        } catch (_: FileNotFoundException) {
          return@synchronized TelegramCredentials()
        }
      check(bytes.size > 12) { "Invalid Telegram credentials. Save them again." }
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
      Json.decodeFromString<TelegramCredentials>(
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
      )
    }

  fun save(value: TelegramCredentials) =
    synchronized(LOCK) {
      require(value.ownerId > 0 && value.token.matches(Regex("[0-9]+:[A-Za-z0-9_-]{20,}"))) {
        "Enter a valid bot token and your numeric Telegram user ID."
      }
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, key())
      val bytes = cipher.iv + cipher.doFinal(Json.encodeToString(value).toByteArray(Charsets.UTF_8))
      val output = file.startWrite()
      try {
        output.write(bytes)
        file.finishWrite(output)
      } catch (error: Exception) {
        file.failWrite(output)
        throw error
      }
    }

  fun clear() = synchronized(LOCK) { file.delete() }

  companion object {
    private const val ALIAS = "gallery-telegram-bot"
    private val LOCK = Any()
  }
}
