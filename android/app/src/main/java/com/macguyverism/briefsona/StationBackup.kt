package com.macguyverism.briefsona

import android.content.Context
import android.util.AtomicFile
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

class StationBackup(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        backupLock.withLock {
            val dao = StationDatabase.get(applicationContext)
            val pending = dao.pending()
            val reporters = applicationContext.getSharedPreferences("reporter_profiles", Context.MODE_PRIVATE)
            val snapshot = reporters.all
            val revision = snapshot["_revision"] as? Long ?: 0L
            val syncReporters = snapshot["_synced"] != revision
            if (pending.isEmpty() && !syncReporters) return@withLock Result.success()

            val origin = BuildConfig.STATION_BACKUP_ORIGIN
            val key = BuildConfig.STATION_BACKUP_KEY
            if (origin.isBlank() || key.isBlank()) {
                pending.forEach { dao.mark(it.id, it.revision, "Backup setup required") }
                return@withLock Result.success()
            }

            val api = StationBackupApi(applicationContext, origin, key)
            val session = try {
                api.session()
            } catch (failure: IOException) {
                val retry = failure.isRetryable()
                pending.forEach { dao.mark(it.id, it.revision, if (retry) "Backup pending" else "Backup needs attention") }
                return@withLock if (retry) Result.retry() else Result.success()
            }
            var retry = false
            if (syncReporters) {
                try {
                    val response = api.jsonPost("/rest/v1/rpc/sync_briefsona_reporters",
                        JSONObject().put("local_revision", revision).put("local_library",
                            JSONObject(snapshot.filterKeys { !it.startsWith("_") })), session.token)
                    withContext(Dispatchers.Main) {
                        if (reporters.getLong("_revision", 0) == revision) {
                            val remoteRevision = response.optLong("revision", revision)
                            val changes = reporters.edit()
                            if (remoteRevision > revision) {
                                val remote = response.getJSONObject("library")
                                for (key in remote.keys()) {
                                    when (val value = remote.get(key)) {
                                        is String -> changes.putString(key, value)
                                        is Boolean -> changes.putBoolean(key, value)
                                    }
                                }
                            }
                            changes.putLong("_revision", remoteRevision).putLong("_synced", remoteRevision).apply()
                        }
                    }
                } catch (_: IOException) { retry = true }
            }
            pending.forEach { record ->
                try {
                    val profile = record.decode()
                    profile.segments.mapNotNull { it.cue }.filter { it.startsWith("file:") }.distinct().forEach {
                        api.upload(session, it)
                    }
                    api.save(session, record)
                    dao.mark(record.id, record.revision, "Backed up")
                } catch (failure: IOException) {
                    val transient = failure.isRetryable()
                    retry = retry || transient
                    dao.mark(record.id, record.revision, if (transient) "Backup pending" else "Backup needs attention")
                }
            }
            if (retry) Result.retry() else Result.success()
        }
    }

    companion object {
        private val backupLock = Mutex()
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        internal fun request(context: Context) {
            val work = OneTimeWorkRequestBuilder<StationBackup>().setConstraints(online).build()
            WorkManager.getInstance(context).enqueueUniqueWork("station-backup", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        }

        internal fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<StationBackup>(15, TimeUnit.MINUTES).setConstraints(online).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("station-backup-recovery", ExistingPeriodicWorkPolicy.KEEP, work)
        }
    }
}

private class BackupHttpError(val status: Int) : IOException("Backup HTTP $status")

private fun IOException.isRetryable(): Boolean =
    this !is java.io.FileNotFoundException && (this !is BackupHttpError || status == 408 || status == 429 || status >= 500)

internal class StationBackupApi(private val context: Context, private val origin: String, private val key: String) {
    private val identity = AtomicFile(File(context.noBackupFilesDir, "station-backup-session.json"))

    data class Session(val userId: String, val token: String, val refresh: String, val expiresAt: Long)

    fun session(): Session = synchronized(sessionLock) { loadSession() }

    companion object { private val sessionLock = Any() }

    private fun loadSession(): Session {
        try {
            val previous = if (identity.baseFile.exists()) {
                identity.openRead().bufferedReader().use { JSONObject(it.readText()) }
            } else null
            if (previous != null && previous.getString("origin") != origin) throw BackupHttpError(409)
            if (previous != null && previous.getLong("expiresAt") > System.currentTimeMillis() / 1000 + 120) {
                return Session(previous.getString("userId"), previous.getString("token"),
                    previous.getString("refresh"), previous.getLong("expiresAt"))
            }
            val response = if (previous == null) {
                jsonPost("/auth/v1/signup", JSONObject())
            } else {
                jsonPost("/auth/v1/token?grant_type=refresh_token",
                    JSONObject().put("refresh_token", previous.getString("refresh")))
            }
            val session = Session(UUID.fromString(response.getJSONObject("user").getString("id")).toString(),
                response.getString("access_token"), response.getString("refresh_token"),
                System.currentTimeMillis() / 1000 + response.getLong("expires_in"))
            if (previous != null && session.userId != previous.getString("userId")) throw BackupHttpError(409)
            val output = identity.startWrite()
            try {
                output.write(JSONObject().put("origin", origin).put("userId", session.userId)
                    .put("token", session.token).put("refresh", session.refresh).put("expiresAt", session.expiresAt)
                    .toString().toByteArray())
                identity.finishWrite(output)
            } catch (error: IOException) {
                identity.failWrite(output)
                throw error
            }
            return session
        } catch (_: org.json.JSONException) {
            throw BackupHttpError(422)
        } catch (_: IllegalArgumentException) {
            throw BackupHttpError(422)
        }
    }

    fun upload(session: Session, cue: String) {
        val file = importedCueFile(context, cue)
        file.inputStream().use { input ->
            post("/storage/v1/object/briefsona-station-cues/${session.userId}/${file.name}",
                session.token, "application/octet-stream", file.length(), upsert = true) {
                input.copyTo(it)
            }
        }
    }

    fun save(session: Session, record: StationRecord) {
        jsonPost("/rest/v1/rpc/save_briefsona_station",
            JSONObject().put("station_id", record.id).put("station_revision", record.revision)
                .put("station_profile", JSONObject(record.profile)), session.token)
    }

    internal fun jsonPost(path: String, body: JSONObject, token: String? = null): JSONObject {
        val bytes = body.toString().toByteArray()
        val response = post(path, token, "application/json", bytes.size.toLong()) { it.write(bytes) }
        return if (response.isBlank() || response == "null") JSONObject() else JSONObject(response)
    }

    private fun post(path: String, token: String?, type: String, length: Long, upsert: Boolean = false,
        write: (java.io.OutputStream) -> Unit): String {
        val connection = URL(origin + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("apikey", key)
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            connection.setRequestProperty("Content-Type", type)
            if (upsert) connection.setRequestProperty("x-upsert", "true")
            connection.setFixedLengthStreamingMode(length)
            connection.outputStream.use(write)
            val status = connection.responseCode
            if (status !in 200..299) throw BackupHttpError(status)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
