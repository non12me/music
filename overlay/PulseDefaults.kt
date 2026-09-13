package com.metrolist.music.pulse

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.*
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

object PulseDefaults {
    suspend fun initialize(context: Context) {
        context.dataStore.edit { values ->
            val initialized = booleanPreferencesKey("pulse_initialized_1")
            if (values[initialized] != true) {
                if (values[DarkModeKey] == null) values[DarkModeKey] = "ON"
                if (values[SelectedThemeColorKey] == null) values[SelectedThemeColorKey] = 0xFF6BE4C2.toInt()
                if (values[DynamicThemeKey] == null) values[DynamicThemeKey] = false
                if (values[PureBlackKey] == null) values[PureBlackKey] = false
                if (values[ContentCountryKey] == null) values[ContentCountryKey] = "PE"
                if (values[ContentLanguageKey] == null) values[ContentLanguageKey] = "es"
                if (values[UseNewPlayerDesignKey] == null) values[UseNewPlayerDesignKey] = true
                if (values[UseNewMiniPlayerDesignKey] == null) values[UseNewMiniPlayerDesignKey] = true
                if (values[MaxSongCacheSizeKey] == null) values[MaxSongCacheSizeKey] = 512
                if (values[AutoSkipNextOnErrorKey] == null) values[AutoSkipNextOnErrorKey] = false
                values[initialized] = true
            }
        }
        runCatching { scheduleBackup(context) }.onFailure {
            PulseCloud(context).recordJobStatus("Revisa la programación de copias en Mi Pulse")
        }
    }

    fun scheduleBackup(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val cloud = PulseCloud(context)
        if (!cloud.signedIn || !cloud.autoBackup) {
            scheduler.cancel(JOB_ID)
            return
        }
        if (scheduler.getPendingJob(JOB_ID) == null) {
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, PulseBackupJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setRequiresBatteryNotLow(true)
                .setPeriodic(24 * 60 * 60 * 1000L, 3 * 60 * 60 * 1000L)
                .setPersisted(true).build()
            check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) { "Android no pudo programar la copia." }
        }
    }

    private const val JOB_ID = 730041
}

@AndroidEntryPoint
class PulseBackupJobService : JobService() {
    @Inject lateinit var database: MusicDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val cloud = PulseCloud(this)
        if (!cloud.autoBackup || !cloud.signedIn) return false
        job = scope.launch {
            try {
                cloud.upload(PulseLibrarySnapshot.export(database))
                cloud.recordJobStatus("Copia automática completada")
                jobFinished(params, false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                cloud.recordJobStatus("Copia pendiente; revisa Cuenta Pulse")
                jobFinished(params, true)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
