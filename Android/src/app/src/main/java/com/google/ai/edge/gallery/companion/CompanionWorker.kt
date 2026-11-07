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
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CompanionWorker(context: Context, parameters: WorkerParameters) :
  CoroutineWorker(context, parameters) {
  override suspend fun doWork(): Result {
    val store = CompanionStore.get(applicationContext)
    // Foreground inference belongs to the user. WorkManager will try again on the next cycle.
    val foreground =
      withContext(Dispatchers.Main) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
      }
    if (foreground) return Result.success()
    try {
      var state = store.state.value
      val now = System.currentTimeMillis()
      if (CompanionPolicy.diaryDue(state, now))
        CompanionRunner.diary(applicationContext, automatic = true)
      state = store.state.value
      if (
        CompanionPolicy.canCheckIn(state, now) &&
          Random.nextDouble() < 0.4 &&
          notificationsAllowed(applicationContext)
      ) {
        CompanionRunner.checkIn(applicationContext)
      }
      return Result.success()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      // No repeated failure notifications or immediate expensive retries.
      store.update { it.copy(status = "Background task deferred: ${error.message?.take(240)}") }
      return Result.success()
    }
  }
}

object CompanionSchedule {
  private const val NAME = "companion-periodic"

  fun update(context: Context, state: CompanionState) {
    val manager = WorkManager.getInstance(context)
    if (!state.checkIns && !state.autoDiary) {
      manager.cancelUniqueWork(NAME)
      return
    }
    val request =
      PeriodicWorkRequestBuilder<CompanionWorker>(3, TimeUnit.HOURS, 1, TimeUnit.HOURS)
        .setConstraints(
          Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(state.chargingOnly)
            .build()
        )
        .build()
    manager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
  }
}

fun notificationsAllowed(context: Context): Boolean =
  (Build.VERSION.SDK_INT < 33 ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED) &&
    NotificationManagerCompat.from(context).areNotificationsEnabled()

fun notifyCompanion(context: Context, text: String) {
  if (!notificationsAllowed(context)) return
  val channel = "companion-check-ins"
  context
    .getSystemService(NotificationManager::class.java)
    .createNotificationChannel(
      NotificationChannel(channel, "Companion check-ins", NotificationManager.IMPORTANCE_DEFAULT)
    )
  val intent =
    PendingIntent.getActivity(
      context,
      4201,
      Intent(context, CompanionActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  val notification =
    NotificationCompat.Builder(context, channel)
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle("Your companion")
      .setContentText(text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(text))
      .setContentIntent(intent)
      .setAutoCancel(true)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .build()
  try {
    NotificationManagerCompat.from(context).notify(4201, notification)
  } catch (_: SecurityException) {
    // Permission can be revoked between the check and posting. The message remains in the inbox.
  }
}
