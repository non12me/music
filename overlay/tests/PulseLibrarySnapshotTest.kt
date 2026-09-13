package com.metrolist.music.pulse

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.InternalDatabase
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PulseLibrarySnapshotTest {
    private lateinit var database: MusicDatabase
    @Before fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = MusicDatabase(Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build())
    }
    @After fun close() { database.close() }

    @Test fun restoresFavoriteAndTitleWithoutGoogleSession() = runBlocking {
        withContext(Dispatchers.IO) { database.upsert(SongEntity(id = "sample", title = "Canción de prueba", liked = true)) }
        val copy = PulseLibrarySnapshot.export(database)
        withContext(Dispatchers.IO) { database.openHelper.writableDatabase.execSQL("DELETE FROM song") }
        PulseLibrarySnapshot.restore(database, copy)
        withContext(Dispatchers.IO) {
            database.openHelper.readableDatabase.query("SELECT title, liked FROM song WHERE id='sample'").use {
                assertTrue(it.moveToFirst())
                assertEquals("Canción de prueba", it.getString(0))
                assertEquals(1, it.getInt(1))
            }
        }
        val raw = GZIPInputStream(ByteArrayInputStream(copy)).readBytes().toString(Charsets.UTF_8)
        assertFalse(raw.contains("innerTubeCookie"))
        assertFalse(raw.contains("refresh_token"))
    }

    @Test fun incompatibleSchemaDoesNotEraseExistingLibrary() = runBlocking {
        withContext(Dispatchers.IO) { database.upsert(SongEntity(id = "keep", title = "Conservar")) }
        val copy = PulseLibrarySnapshot.export(database)
        val root = JSONObject(GZIPInputStream(ByteArrayInputStream(copy)).readBytes().toString(Charsets.UTF_8))
        root.put("schema", -1)
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(root.toString().toByteArray()) }
        try {
            PulseLibrarySnapshot.restore(database, output.toByteArray())
            fail("Should reject schema mismatch")
        } catch (_: IllegalArgumentException) { }
        withContext(Dispatchers.IO) {
            database.openHelper.readableDatabase.query("SELECT count(*) FROM song WHERE id='keep'").use {
                it.moveToFirst(); assertEquals(1, it.getInt(0))
            }
        }
    }

    @Test fun malformedRowRollsBackDeletes() = runBlocking {
        withContext(Dispatchers.IO) { database.upsert(SongEntity(id = "keep", title = "Conservar")) }
        val copy = PulseLibrarySnapshot.export(database)
        val root = JSONObject(GZIPInputStream(ByteArrayInputStream(copy)).readBytes().toString(Charsets.UTF_8))
        root.getJSONObject("tables").getJSONObject("song").getJSONArray("rows").getJSONArray(0).remove(0)
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(root.toString().toByteArray()) }
        try {
            PulseLibrarySnapshot.restore(database, output.toByteArray())
            fail("Should reject incomplete row")
        } catch (_: IllegalArgumentException) { }
        withContext(Dispatchers.IO) {
            database.openHelper.readableDatabase.query("SELECT count(*) FROM song WHERE id='keep'").use {
                it.moveToFirst(); assertEquals(1, it.getInt(0))
            }
        }
    }
}
