package com.example.ja

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Durable, network-constrained cloud sync for user data changes and app backgrounding. */
internal class JaAccountSyncWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val session = applicationContext.getSharedPreferences("ja_account_session", Context.MODE_PRIVATE)
            .getString("session", null)
        if (session.isNullOrBlank()) return Result.success()

        val database = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "ja.db").build()
        return try {
            JaAccountSync(applicationContext, database.dao()).sync()
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = error.message.orEmpty()
            Log.w("JA_ACCOUNT_SYNC", "BACKGROUND_SYNC_FAILED attempt=$runAttemptCount reason=$message", error)
            when {
                message.contains("Choose Download cloud data or Upload this device") ||
                    message.contains("Both this device and the cloud changed") ||
                    message.contains("awaiting administrator approval") ||
                    message.contains("Sign in to your JA account") -> Result.success()
                message.contains("settings are not configured") -> Result.failure()
                runAttemptCount >= 5 -> Result.failure()
                else -> Result.retry()
            }
        } finally {
            database.close()
        }
    }

    companion object {
        private const val UNIQUE_WORK = "ja-account-data-sync"

        fun enqueue(context: Context, delayMs: Long = 750L) {
            val app = context.applicationContext
            if (app.getSharedPreferences("ja_account_session", Context.MODE_PRIVATE)
                    .getString("session", null).isNullOrBlank()) return
            val request = OneTimeWorkRequestBuilder<JaAccountSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15L, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
