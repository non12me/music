package com.metrolist.music.pulse

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class PulseBackup(val device: String, val label: String, val timestamp: Long)

/** Firebase REST keeps this optional feature independent of Google Play Services. */
class PulseCloud(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pulse_private", Context.MODE_PRIVATE)

    val project: String get() = prefs.getString("project", "")!!
    val apiKey: String get() = prefs.getString("api_key", "")!!
    val email: String get() = prefs.getString("email", "")!!
    val signedIn: Boolean get() = prefs.contains("session")
    val autoBackup: Boolean get() = prefs.getBoolean("auto_backup", false)
    val lastBackup: Long get() = prefs.getLong("last_backup", 0)
    val lastJobStatus: String get() = prefs.getString("last_job_status", "")!!
    private val deviceId: String
        get() = synchronized(deviceLock) {
            prefs.getString("device", null) ?: UUID.randomUUID().toString().also {
                check(prefs.edit().putString("device", it).commit())
            }
        }

    fun configure(projectId: String, webApiKey: String) {
        require(Regex("[a-z][a-z0-9-]{4,61}[a-z0-9]").matches(projectId.trim())) { "ID de proyecto inválido." }
        require(Regex("[A-Za-z0-9_-]{20,120}").matches(webApiKey.trim())) { "Clave web de Firebase inválida." }
        if (projectId.trim() != project || webApiKey.trim() != apiKey) signOut()
        check(prefs.edit().putString("project", projectId.trim()).putString("api_key", webApiKey.trim()).commit())
    }

    fun setAutoBackup(enabled: Boolean) { prefs.edit().putBoolean("auto_backup", enabled).apply() }
    fun recordJobStatus(status: String) { prefs.edit().putString("last_job_status", status).apply() }
    fun signOut() {
        synchronized(deviceLock) {
            check(prefs.edit().remove("session").remove("email").remove("uid").remove("last_backup").remove("last_job_status")
                .putLong("session_epoch", prefs.getLong("session_epoch", 0) + 1)
                .putBoolean("auto_backup", false).commit())
        }
    }

    suspend fun authenticate(mail: String, password: String, create: Boolean) = withContext(Dispatchers.IO) {
        accountMutex.withLock {
            require(project.isNotBlank() && apiKey.isNotBlank()) { "Configura Firebase primero." }
            val epoch = prefs.getLong("session_epoch", 0)
            val action = if (create) "signUp" else "signInWithPassword"
            val result = request("https://identitytoolkit.googleapis.com/v1/accounts:$action?key=$apiKey", "POST",
                JSONObject().put("email", mail.trim()).put("password", password).put("returnSecureToken", true))
            saveSession(result.getString("refreshToken"), result.getString("localId"), result.optString("email", mail.trim()), epoch)
        }
    }

    suspend fun resetPassword(mail: String) = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Configura Firebase primero." }
        request("https://identitytoolkit.googleapis.com/v1/accounts:sendOobCode?key=$apiKey", "POST",
            JSONObject().put("requestType", "PASSWORD_RESET").put("email", mail.trim()))
        Unit
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    private fun saveSession(refreshToken: String, uid: String, mail: String, epoch: Long) = synchronized(deviceLock) {
        check(epoch == prefs.getLong("session_epoch", 0)) { "La sesión cambió. Vuelve a entrar." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val encrypted = cipher.doFinal(refreshToken.toByteArray(Charsets.UTF_8))
        val payload = "${encode(cipher.iv)}.${encode(encrypted)}"
        check(prefs.edit().putString("session", payload).putString("uid", uid).putString("email", mail).commit())
    }

    private fun refreshToken(): String {
        val parts = (prefs.getString("session", null) ?: error("Inicia sesión con tu correo.")).split('.')
        require(parts.size == 2) { "Vuelve a iniciar sesión." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, decode(parts[0])))
        }
        return cipher.doFinal(decode(parts[1])).toString(Charsets.UTF_8)
    }

    private fun access(): Pair<String, String> {
        val epoch = prefs.getLong("session_epoch", 0)
        val result = request("https://securetoken.googleapis.com/v1/token?key=$apiKey", "POST",
            form = mapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken()))
        val uid = result.getString("user_id")
        check(uid == prefs.getString("uid", null)) { "La sesión cambió. Inicia sesión de nuevo." }
        saveSession(result.getString("refresh_token"), uid, email, epoch)
        return uid to result.getString("id_token")
    }

    private fun root(uid: String) = "projects/$project/databases/(default)/documents/users/$uid/backups"
    private fun endpoint(path: String) = "https://firestore.googleapis.com/v1/$path"

    suspend fun backups(): List<PulseBackup> = withContext(Dispatchers.IO) {
        accountMutex.withLock {
            val (uid, token) = access()
            val result = request(endpoint(root(uid)) + "?pageSize=100", token = token)
            val docs = result.optJSONArray("documents") ?: JSONArray()
            (0 until docs.length()).map { index ->
                val d = docs.getJSONObject(index)
                val f = d.getJSONObject("fields")
                PulseBackup(d.getString("name").substringAfterLast('/'), text(f, "label"), number(f, "timestamp"))
            }.sortedByDescending { it.timestamp }
        }
    }

    suspend fun upload(bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        accountMutex.withLock {
            require(bytes.isNotEmpty() && bytes.size <= MAX_BACKUP_BYTES) { "La copia supera los 32 MB comprimidos." }
            val (uid, token) = access()
            val base = "${root(uid)}/$deviceId"
            val generation = UUID.randomUUID().toString()
            val count = (bytes.size + CHUNK_SIZE - 1) / CHUNK_SIZE
            val previous = optionalDocument(base, token)?.optJSONObject("fields")
            for (index in 0 until count) {
                currentCoroutineContext().ensureActive()
                val chunk = bytes.copyOfRange(index * CHUNK_SIZE, minOf(bytes.size, (index + 1) * CHUNK_SIZE))
                val fields = JSONObject().put("data", stringField(encode(chunk)))
                    .put("created", intField(System.currentTimeMillis()))
                request(endpoint("$base/chunks/$generation-$index"), "PATCH", JSONObject().put("fields", fields), token)
            }
            val now = System.currentTimeMillis()
            currentCoroutineContext().ensureActive()
            check(signedIn && prefs.getString("uid", null) == uid) { "La sesión cambió. Vuelve a entrar." }
            val manifest = JSONObject().put("generation", stringField(generation)).put("count", intField(count.toLong()))
                .put("sha256", stringField(sha256(bytes))).put("timestamp", intField(now))
                .put("label", stringField("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"))
                .put("size", intField(bytes.size.toLong())).put("format", intField(1))
            // Publish only after every immutable chunk exists; the previous backup remains usable on failure.
            request(endpoint(base), "PATCH", JSONObject().put("fields", manifest), token)
            prefs.edit().putLong("last_backup", now).apply()
            if (previous != null) {
                val oldGeneration = text(previous, "generation")
                val oldCount = number(previous, "count").toInt()
                if (validGeneration(oldGeneration) && oldCount in 1..MAX_CHUNKS) {
                    for (index in 0 until oldCount) {
                        currentCoroutineContext().ensureActive()
                        try { request(endpoint("$base/chunks/$oldGeneration-$index"), "DELETE", token = token) }
                        catch (_: IOException) { break } // Cleanup is optional; never undo a successful backup.
                    }
                }
            }
            Unit
        }
    }

    suspend fun download(device: String): ByteArray = withContext(Dispatchers.IO) {
        accountMutex.withLock {
            require(validGeneration(device)) { "Copia no válida." }
            val (uid, token) = access()
            val base = "${root(uid)}/$device"
            val f = request(endpoint(base), token = token).getJSONObject("fields")
            val generation = text(f, "generation")
            val count = number(f, "count").toInt()
            val size = number(f, "size").toInt()
            require(validGeneration(generation) && count in 1..MAX_CHUNKS && size in 1..MAX_BACKUP_BYTES) { "Copia no válida." }
            require(number(f, "format") == 1L) { "Actualiza Pulse para abrir esta copia." }
            val output = java.io.ByteArrayOutputStream(size)
            for (index in 0 until count) {
                currentCoroutineContext().ensureActive()
                val d = request(endpoint("$base/chunks/$generation-$index"), token = token).getJSONObject("fields")
                val chunk = decode(text(d, "data"))
                require(chunk.size <= CHUNK_SIZE && output.size() + chunk.size <= size) { "Copia dañada." }
                output.write(chunk)
            }
            output.toByteArray().also {
                require(it.size == size && sha256(it) == text(f, "sha256")) { "La copia está incompleta. Tus datos locales no cambiaron." }
            }
        }
    }

    private fun optionalDocument(path: String, token: String): JSONObject? = try {
        request(endpoint(path), token = token)
    } catch (e: CloudException) { if (e.status == 404) null else throw e }

    private fun request(url: String, method: String = "GET", data: JSONObject? = null, token: String? = null, form: Map<String, String>? = null): JSONObject {
        val body = if (form != null) FormBody.Builder().apply { form.forEach { (key, value) -> add(key, value) } }.build()
            else data?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder().url(url).method(method, body)
        if (token != null) builder.header("Authorization", "Bearer $token")
        client.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val code = runCatching { JSONObject(raw).getJSONObject("error").optString("message") }.getOrDefault("")
                val message = when {
                    "INVALID_LOGIN_CREDENTIALS" in code || "INVALID_PASSWORD" in code || "EMAIL_NOT_FOUND" in code -> "Correo o contraseña incorrectos."
                    "EMAIL_EXISTS" in code -> "Este correo ya tiene cuenta. Usa Iniciar sesión."
                    "WEAK_PASSWORD" in code -> "Usa una contraseña de al menos 6 caracteres."
                    "OPERATION_NOT_ALLOWED" in code -> "Activa Email/Password en Firebase Authentication."
                    response.code == 403 -> "Acceso denegado. Revisa las reglas de Firestore y el proyecto."
                    response.code == 429 -> "Límite temporal del servicio. Prueba más tarde."
                    response.code == 401 -> "La sesión caducó. Vuelve a iniciar sesión."
                    else -> "No se pudo completar la operación (HTTP ${response.code})."
                }
                throw CloudException(response.code, message)
            }
            return if (raw.isBlank()) JSONObject() else JSONObject(raw)
        }
    }

    private class CloudException(val status: Int, message: String) : IOException(message)
    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS).build()
        private val accountMutex = Mutex()
        private val deviceLock = Any()
        private const val KEY_ALIAS = "pulse_cloud_session_v1"
        private const val CHUNK_SIZE = 400_000
        private const val MAX_BACKUP_BYTES = 32 * 1024 * 1024
        private const val MAX_CHUNKS = 84
        private fun validGeneration(value: String) = Regex("[a-f0-9-]{36}").matches(value)
        private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
        private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun stringField(value: String) = JSONObject().put("stringValue", value)
        private fun intField(value: Long) = JSONObject().put("integerValue", value.toString())
        private fun text(fields: JSONObject, key: String) = fields.getJSONObject(key).getString("stringValue")
        private fun number(fields: JSONObject, key: String) = fields.getJSONObject(key).getString("integerValue").toLong()
    }
}
