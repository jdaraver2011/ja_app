package com.example.ja

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.*
import java.io.File

/** OS-managed daily catalog download, persisted across reboot; no channel probing on device. */
internal object JaLiveTvUpdates {
    private const val DAILY=240301
    private const val OPEN=240302
    fun schedule(context:Context) {
        val scheduler=context.getSystemService(JobScheduler::class.java)
        val service=ComponentName(context,JaLiveTvUpdateService::class.java)
        if(scheduler.allPendingJobs.none{it.id==DAILY})scheduler.schedule(JobInfo.Builder(DAILY,service)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
            .setPeriodic(86_400_000).setBackoffCriteria(1_800_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
        val file=File(context.cacheDir,"live-tv/verified-us.json")
        if(!JaLiveTvRules.fresh(file.lastModified(),System.currentTimeMillis(),86_400_000)
            &&scheduler.allPendingJobs.none{it.id==OPEN})scheduler.schedule(JobInfo.Builder(OPEN,service)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setBackoffCriteria(1_800_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
    }
}

class JaLiveTvUpdateService:JobService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val running=mutableMapOf<Int,Job>()
    override fun onStartJob(params:JobParameters):Boolean {
        running[params.jobId]=scope.launch {
            try{val result=JaLiveTvRepository(applicationContext).channels(force=true);jobFinished(params,result.saved)}
            catch(e:CancellationException){throw e}
            catch(_:Exception){jobFinished(params,true)}
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean {running.remove(params.jobId)?.cancel();return true}
    override fun onDestroy(){scope.cancel();super.onDestroy()}
}
