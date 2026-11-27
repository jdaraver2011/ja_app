package com.example.ja

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.*
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** OS-managed channel and guide refreshes scheduled after 6:00 AM local time. */
internal object JaLiveTvUpdates {
    private const val DAILY=240301
    private const val OPEN=240302
    private const val PREFS="ja_live_tv_update_schedule"
    private const val SCHEDULE_VERSION="schedule_version"

    fun schedule(context:Context) {
        val scheduler=context.getSystemService(JobScheduler::class.java)
        val service=ComponentName(context,JaLiveTvUpdateService::class.java)
        val prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        // Replace the earlier rolling 24-hour job with the new 6:00 AM anchored schedule once.
        if(prefs.getInt(SCHEDULE_VERSION,0)<2){
            scheduler.cancel(DAILY)
            prefs.edit().putInt(SCHEDULE_VERSION,2).apply()
        }
        if(scheduler.allPendingJobs.none{it.id==DAILY})scheduleNext(context)

        val now=System.currentTimeMillis()
        val todaySix=todaySix(now)
        val catalog=File(context.cacheDir,"live-tv/direct-catalog.json").takeIf{it.isFile}
        val cachedAt=catalog?.lastModified()?:0L
        if(now>=todaySix&&cachedAt<todaySix&&scheduler.allPendingJobs.none{it.id==OPEN}){
            scheduler.schedule(JobInfo.Builder(OPEN,service)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                .setBackoffCriteria(1_800_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
        }
    }

    fun scheduleNext(context:Context) {
        val now=System.currentTimeMillis()
        val localNow=Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        var target=localNow.toLocalDate().atTime(6,0).atZone(localNow.zone)
        if(!target.isAfter(localNow))target=target.plusDays(1)
        val delay=Duration.between(localNow,target).toMillis().coerceAtLeast(0L)
        val service=ComponentName(context,JaLiveTvUpdateService::class.java)
        val scheduler=context.getSystemService(JobScheduler::class.java)
        scheduler.schedule(JobInfo.Builder(DAILY,service)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
            .setMinimumLatency(delay).setOverrideDeadline(delay+Duration.ofHours(3).toMillis())
            .setBackoffCriteria(1_800_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
    }

    private fun todaySix(now:Long):Long=Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        .toLocalDate().atTime(6,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

class JaLiveTvUpdateService:JobService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val running=mutableMapOf<Int,Job>()
    override fun onStartJob(params:JobParameters):Boolean {
        running[params.jobId]=scope.launch {
            try{
                val repository=JaLiveTvRepository(applicationContext)
                // Use the same local-day guard as the foreground screen so a catch-up job
                // cannot download a second time after IPTV already refreshed today.
                val result=repository.channels(force=false)
                if(result.channels.isNotEmpty()){
                    try{repository.guide(result.channels,force=false)}
                    catch(e:CancellationException){throw e}
                    catch(e:Exception){android.util.Log.w("JA_LIVE_TV","DAILY_GUIDE_REFRESH_FAILED; will retry at next daily run",e)}
                }
                jobFinished(params,false)
                if(params.jobId==240301)JaLiveTvUpdates.scheduleNext(applicationContext)
            }catch(e:CancellationException){throw e}
            catch(e:Exception){
                android.util.Log.w("JA_LIVE_TV","DAILY_REFRESH_FAILED; will retry at next daily run",e)
                jobFinished(params,false)
                if(params.jobId==240301)JaLiveTvUpdates.scheduleNext(applicationContext)
            }
            finally{running.remove(params.jobId)}
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean {running.remove(params.jobId)?.cancel();return true}
    override fun onDestroy(){scope.cancel();super.onDestroy()}
}
