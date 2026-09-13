package com.metrolist.music.pulse

import android.database.Cursor
import android.util.Base64
import com.metrolist.music.db.MusicDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Logical, transactional backup: no WAL races, account tokens, passwords or API keys. */
object PulseLibrarySnapshot {
    private val tables = listOf("song", "artist", "album", "playlist", "podcast", "song_artist_map", "song_album_map",
        "album_artist_map", "playlist_song_map", "search_history", "format", "lyrics", "event", "related_song_map",
        "set_video_id", "playCount", "recognition_history", "speed_dial_item")
    private const val MAX_JSON_BYTES = 128 * 1024 * 1024

    suspend fun export(database: MusicDatabase): ByteArray = withContext(Dispatchers.IO) {
        val root = JSONObject().put("format", 1)
        database.withTransaction {
            val db = openHelper.writableDatabase
            root.put("schema", db.version)
            val data = JSONObject()
            tables.forEach { table ->
                db.query("SELECT * FROM `$table`").use { cursor ->
                    val rows = JSONArray()
                    while (cursor.moveToNext()) {
                        val row = JSONArray()
                        for (index in 0 until cursor.columnCount) {
                            row.put(when (cursor.getType(index)) {
                                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                                Cursor.FIELD_TYPE_BLOB -> JSONObject().put("blob", Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP))
                                else -> cursor.getString(index)
                            })
                        }
                        rows.put(row)
                    }
                    data.put(table, JSONObject().put("columns", JSONArray(cursor.columnNames.toList())).put("rows", rows))
                }
            }
            root.put("tables", data)
        }
        val raw = root.toString().toByteArray(Charsets.UTF_8)
        require(raw.size <= MAX_JSON_BYTES) { "La biblioteca supera el tamaño de copia admitido." }
        ByteArrayOutputStream().also { buffer -> GZIPOutputStream(buffer).use { it.write(raw) } }.toByteArray()
    }

    suspend fun restore(database: MusicDatabase, compressed: ByteArray) = withContext(Dispatchers.IO) {
        val output = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(compressed)).use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= MAX_JSON_BYTES) { "Copia demasiado grande." }
                output.write(buffer, 0, count)
            }
        }
        val root = JSONObject(output.toString("UTF-8"))
        require(root.getInt("format") == 1) { "Formato de copia no compatible." }
        val data = root.getJSONObject("tables")
        require(data.keys().asSequence().toSet() == tables.toSet()) { "La copia no contiene toda la biblioteca." }
        database.withTransaction {
            val db = openHelper.writableDatabase
            require(db.version == root.getInt("schema")) { "La copia pertenece a otra versión de la biblioteca. Usa primero la misma versión de Pulse." }
            // Validate every column before touching local records. Names used in SQL come only from the current schema.
            val columnsByTable = tables.associateWith { table ->
                val columns = db.query("SELECT * FROM `$table` LIMIT 0").use { it.columnNames.toList() }
                val backupColumns = data.getJSONObject(table).getJSONArray("columns")
                require((0 until backupColumns.length()).map { backupColumns.getString(it) } == columns) { "Columnas incompatibles: $table" }
                columns
            }
            db.execSQL("PRAGMA defer_foreign_keys = ON")
            tables.asReversed().forEach { db.execSQL("DELETE FROM `$it`") }
            tables.forEach { table ->
                val columns = columnsByTable.getValue(table)
                val sql = "INSERT INTO `$table` (${columns.joinToString(",") { "`$it`" }}) VALUES (${columns.joinToString(",") { "?" }})"
                val rows = data.getJSONObject(table).getJSONArray("rows")
                val statement = db.compileStatement(sql)
                try {
                    for (index in 0 until rows.length()) {
                        val row = rows.getJSONArray(index)
                        require(row.length() == columns.size) { "Registro incompleto en $table." }
                        statement.clearBindings()
                        for (column in columns.indices) {
                            val value = row.get(column)
                            when (value) {
                                JSONObject.NULL -> statement.bindNull(column + 1)
                                is Int, is Long -> statement.bindLong(column + 1, (value as Number).toLong())
                                is Number -> statement.bindDouble(column + 1, value.toDouble())
                                is String -> statement.bindString(column + 1, value)
                                is JSONObject -> statement.bindBlob(column + 1, Base64.decode(value.getString("blob"), Base64.NO_WRAP))
                                else -> error("Tipo de dato incompatible.")
                            }
                        }
                        statement.executeInsert()
                    }
                } finally { statement.close() }
            }
            db.query("PRAGMA foreign_key_check").use { require(!it.moveToFirst()) { "Relaciones incompletas; restauración cancelada." } }
        }
    }
}
