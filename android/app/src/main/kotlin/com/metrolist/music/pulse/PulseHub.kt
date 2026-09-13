package com.metrolist.music.pulse

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.metrolist.music.BuildConfig
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.db.MusicDatabase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class PulseHubViewModel @Inject constructor(application: Application, private val database: MusicDatabase) : AndroidViewModel(application) {
    val cloud = PulseCloud(application)
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set
    var signedIn by mutableStateOf(cloud.signedIn)
        private set
    var copies by mutableStateOf(emptyList<PulseBackup>())
        private set
    var latest by mutableStateOf("")
        private set

    fun runOperation(action: suspend () -> String) {
        if (busy) { return }
        viewModelScope.launch {
            busy = true
            message = ""
            try {
                message = action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: getApplication<Application>().getString(R.string.pulse_operation_failed)
            } finally {
                signedIn = cloud.signedIn
                if (!signedIn) copies = emptyList()
                busy = false
            }
        }
    }

    suspend fun backup() { cloud.upload(PulseLibrarySnapshot.export(database)) }
    suspend fun listBackups() { copies = cloud.backups() }
    val hasRecovery: Boolean get() = getApplication<Application>().filesDir.resolve("pulse_before_restore.json.gz").isFile
    suspend fun undoRestore() {
        val bytes = withContext(Dispatchers.IO) {
            getApplication<Application>().filesDir.resolve("pulse_before_restore.json.gz").readBytes()
        }
        PulseLibrarySnapshot.restore(database, bytes)
    }
    suspend fun restore(copy: PulseBackup) {
        val snapshot = cloud.download(copy.device)
        // Keep an independent local recovery file before replacing the library.
        withContext(Dispatchers.IO) {
            val recovery = PulseLibrarySnapshot.export(database)
            val destination = getApplication<Application>().filesDir.resolve("pulse_before_restore.json.gz")
            val temp = destination.resolveSibling("pulse_before_restore.tmp")
            temp.writeBytes(recovery)
            check(temp.renameTo(destination)) { "No se pudo crear la copia de recuperación." }
        }
        PulseLibrarySnapshot.restore(database, snapshot)
    }

    suspend fun checkUpdates(): String = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url("https://api.github.com/repos/MetrolistGroup/Metrolist/releases/latest").build()).execute().use {
            check(it.isSuccessful) { getApplication<Application>().getString(R.string.pulse_update_unavailable) }
            val info = JSONObject(it.body!!.string())
            latest = info.getString("tag_name")
        }
        getApplication<Application>().getString(
            if (PulseReleasePolicy.isNewer(latest, BuildConfig.BASE_VERSION_NAME)) R.string.pulse_new_base else R.string.pulse_current_base,
            latest
        )
    }
}

@Composable
fun PulseHub(navController: NavController, viewModel: PulseHubViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val playerConnection = LocalPlayerConnection.current
    val cloud = viewModel.cloud
    val preferences = remember { context.getSharedPreferences("pulse_ui", 0) }
    var project by remember { mutableStateOf(cloud.project) }
    var apiKey by remember { mutableStateOf(cloud.apiKey) }
    var email by remember { mutableStateOf(cloud.email) }
    var password by remember { mutableStateOf("") }
    var showConfiguration by remember { mutableStateOf(cloud.project.isBlank()) }
    var automatic by remember { mutableStateOf(cloud.autoBackup) }
    var repository by remember { mutableStateOf(preferences.getString("repository", "").orEmpty()) }
    var restoreCandidate by remember { mutableStateOf<PulseBackup?>(null) }
    var undoRequested by remember { mutableStateOf(false) }
    val busy = viewModel.busy

    Column(Modifier.fillMaxSize().windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
        .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = { navController.navigateUp() }) { Text(stringResource(R.string.pulse_back)) }
        Column(Modifier.fillMaxWidth().background(
            Brush.linearGradient(listOf(Color(0xFF183B36), Color(0xFF242342))), RoundedCornerShape(28.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("PULSE MUSIC", style = MaterialTheme.typography.labelLarge, color = Color(0xFF6BE4C2))
            Text(stringResource(R.string.pulse_hero), style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold, color = Color.White)
            Text(stringResource(R.string.pulse_hero_detail), color = Color(0xFFCCD8D5))
            Text(stringResource(R.string.pulse_base, BuildConfig.BASE_VERSION_NAME),
                style = MaterialTheme.typography.labelMedium, color = Color(0xFFAABCB8))
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (viewModel.message.isNotBlank()) {
            Card(Modifier.fillMaxWidth()) { Text(viewModel.message, Modifier.padding(16.dp)) }
        }

        PulseSection(stringResource(R.string.pulse_music_account)) {
            Text(stringResource(R.string.pulse_music_account_detail))
            Button(enabled = !busy, onClick = { navController.navigate("login") }) { Text(stringResource(R.string.pulse_connect_google)) }
            OutlinedButton(onClick = { navController.navigate("settings/backup_restore") }) { Text(stringResource(R.string.pulse_local_backup)) }
            if (viewModel.hasRecovery) {
                OutlinedButton(enabled = !busy, onClick = { undoRequested = true }) { Text(stringResource(R.string.pulse_undo_restore)) }
            }
        }

        PulseSection(stringResource(R.string.pulse_cloud_title)) {
            Text(stringResource(R.string.pulse_cloud_detail))
            TextButton(enabled = !busy, onClick = { showConfiguration = !showConfiguration }) { Text(stringResource(R.string.pulse_configure)) }
            if (showConfiguration) {
                OutlinedTextField(project, { project = it.trim() }, enabled = !busy, singleLine = true,
                    label = { Text(stringResource(R.string.pulse_project)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(apiKey, { apiKey = it.trim() }, enabled = !busy, singleLine = true,
                    label = { Text(stringResource(R.string.pulse_api_key)) }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(enabled = !busy, onClick = {
                    viewModel.runOperation {
                        cloud.configure(project, apiKey)
                        PulseDefaults.scheduleBackup(context)
                        automatic = cloud.autoBackup
                        showConfiguration = false
                        context.getString(R.string.pulse_configured)
                    }
                }) { Text(stringResource(R.string.pulse_save_configuration)) }
            }
            if (!viewModel.signedIn) {
                OutlinedTextField(email, { email = it }, enabled = !busy, singleLine = true,
                    label = { Text(stringResource(R.string.pulse_email)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, enabled = !busy, singleLine = true,
                    label = { Text(stringResource(R.string.pulse_password)) }, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy && password.isNotEmpty() && email.isNotBlank(), onClick = {
                        val suppliedPassword = password; password = ""
                        viewModel.runOperation { cloud.authenticate(email, suppliedPassword, false); context.getString(R.string.pulse_signed_in) }
                    }) { Text(stringResource(R.string.pulse_sign_in)) }
                    OutlinedButton(enabled = !busy && password.isNotEmpty() && email.isNotBlank(), onClick = {
                        val suppliedPassword = password; password = ""
                        viewModel.runOperation { cloud.authenticate(email, suppliedPassword, true); context.getString(R.string.pulse_signed_in) }
                    }) { Text(stringResource(R.string.pulse_create_account)) }
                }
                TextButton(enabled = !busy && email.isNotBlank(), onClick = {
                    viewModel.runOperation { cloud.resetPassword(email); context.getString(R.string.pulse_reset_sent) }
                }) { Text(stringResource(R.string.pulse_reset_password)) }
            } else {
                Text(cloud.email, style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.pulse_daily_backup), Modifier.weight(1f))
                    Switch(automatic, enabled = !busy, onCheckedChange = { enabled ->
                        viewModel.runOperation {
                            cloud.setAutoBackup(enabled)
                            PulseDefaults.scheduleBackup(context)
                            automatic = enabled
                            context.getString(R.string.pulse_saved)
                        }
                    })
                }
                Text(stringResource(R.string.pulse_daily_detail), style = MaterialTheme.typography.bodySmall)
                if (cloud.lastBackup > 0) Text(stringResource(R.string.pulse_last_backup,
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(cloud.lastBackup))))
                if (cloud.lastJobStatus.isNotBlank()) Text(cloud.lastJobStatus, style = MaterialTheme.typography.bodySmall)
                Button(enabled = !busy, onClick = {
                    viewModel.runOperation { viewModel.backup(); context.getString(R.string.pulse_backup_saved) }
                }) { Text(stringResource(R.string.pulse_backup_now)) }
                OutlinedButton(enabled = !busy, onClick = {
                    viewModel.runOperation { viewModel.listBackups(); context.getString(R.string.pulse_backups_loaded) }
                }) { Text(stringResource(R.string.pulse_show_backups)) }
                viewModel.copies.forEach { copy ->
                    TextButton(enabled = !busy, onClick = { restoreCandidate = copy }) {
                        Text("${copy.label} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(copy.timestamp))}")
                    }
                }
                TextButton(enabled = !busy, onClick = {
                    viewModel.runOperation {
                        cloud.signOut(); automatic = false
                        PulseDefaults.scheduleBackup(context)
                        context.getString(R.string.pulse_signed_out)
                    }
                }) { Text(stringResource(R.string.pulse_sign_out)) }
            }
        }

        PulseSection(stringResource(R.string.pulse_maintenance_title)) {
            Text(stringResource(R.string.pulse_maintenance_detail))
            OutlinedTextField(repository, { repository = it.trim() }, enabled = !busy, singleLine = true,
                label = { Text(stringResource(R.string.pulse_repository)) }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(enabled = !busy, onClick = {
                viewModel.runOperation { viewModel.checkUpdates() }
            }) { Text(stringResource(R.string.pulse_check_updates)) }
            Button(enabled = !busy && PulseReleasePolicy.validRepository(repository), onClick = {
                preferences.edit().putString("repository", repository).apply()
                uriHandler.openUri("https://github.com/$repository/actions/workflows/pulse-build.yml")
            }) { Text(stringResource(R.string.pulse_prepare_update)) }
            Text(stringResource(R.string.pulse_update_detail), style = MaterialTheme.typography.bodySmall)
        }

        PulseSection(stringResource(R.string.pulse_personalize)) {
            OutlinedButton(onClick = { navController.navigate("settings/appearance") }) { Text(stringResource(R.string.pulse_design)) }
            OutlinedButton(onClick = { navController.navigate("settings/player") }) { Text(stringResource(R.string.pulse_audio)) }
            OutlinedButton(onClick = { navController.navigate("settings/ai") }) { Text(stringResource(R.string.pulse_optional_ai)) }
        }
        Text(stringResource(R.string.pulse_credits), style = MaterialTheme.typography.bodySmall)
    }

    if (restoreCandidate != null || undoRequested) {
        AlertDialog(onDismissRequest = { restoreCandidate = null; undoRequested = false },
            title = { Text(stringResource(R.string.pulse_restore_title)) },
            text = { Text(stringResource(if (undoRequested) R.string.pulse_undo_detail else R.string.pulse_restore_detail)) },
            confirmButton = {
                TextButton(onClick = {
                    val copy = restoreCandidate
                    val undo = undoRequested
                    restoreCandidate = null
                    undoRequested = false
                    playerConnection?.player?.pause()
                    viewModel.runOperation {
                        if (undo) viewModel.undoRestore() else viewModel.restore(requireNotNull(copy))
                        context.getString(R.string.pulse_restored)
                    }
                }) { Text(stringResource(R.string.pulse_restore_confirm)) }
            }, dismissButton = { TextButton(onClick = { restoreCandidate = null; undoRequested = false }) { Text(stringResource(R.string.pulse_cancel)) } })
    }
}

@Composable
private fun PulseSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
