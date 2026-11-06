package com.drake.net.cookie

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class PersistentCookieJarMigrationTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private val origin = "https://example.test/".toHttpUrl()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        databaseName = "cookie-migration-${UUID.randomUUID()}"
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun versionOneMigrationPreservesValidCookiesAndTheirOriginalScope() {
        createVersionOne().use { db ->
            insertLegacy(db, "example.test", "host", "host=local; Path=/; Secure")
            insertLegacy(db, "login.example.test", "shared", "shared=domain; Domain=example.test; Path=/admin")
            insertLegacy(db, "example.test", "expired", "expired=gone; Max-Age=0; Path=/")
            insertLegacy(db, "example.test", "broken", "not-a-cookie")
            insertLegacy(db, "bad host", "invalid", "invalid=gone; Path=/")
        }
        val jar = PersistentCookieJar(context, databaseName)

        assertEquals(setOf("host", "shared"), jar.getAll(origin).map { it.name }.toSet())
        assertEquals(listOf("domain"), jar.loadForRequest("https://api.example.test/admin".toHttpUrl()).map { it.value })
        assertTrue(jar.loadForRequest("http://example.test/".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://api.example.test/".toHttpUrl()).isEmpty())
        val reopened = PersistentCookieJar(context, databaseName)
        assertEquals(listOf("local"), reopened.loadForRequest(origin).map { it.value })
        assertTrue(reopened.getAll(origin).first { it.name == "host" }.hostOnly)
        openDatabase().use { assertEquals(2, it.version) }
    }

    @Test
    fun migrationKeepsTheLastRowForAnIdentityWithoutExpandingHostOnlyCookies() {
        createVersionOne().use { db ->
            insertLegacy(db, "sub.example.test", "sid", "sid=older; Domain=example.test; Path=/")
            insertLegacy(db, "example.test", "sid", "sid=newer; Path=/")
        }
        val jar = PersistentCookieJar(context, databaseName)

        assertEquals(listOf("newer"), jar.loadForRequest(origin).map { it.value })
        assertTrue(jar.loadForRequest("https://sub.example.test/".toHttpUrl()).isEmpty())
        assertTrue(jar.getAll(origin).single().hostOnly)
    }

    @Test
    fun aLaterExpiredLegacyRowDoesNotResurrectAnOlderDomainCookie() {
        createVersionOne().use { db ->
            insertLegacy(db, "login.example.test", "sid", "sid=older; Domain=example.test; Path=/")
            insertLegacy(db, "api.example.test", "sid", "sid=expired; Domain=example.test; Path=/; Max-Age=0")
        }
        val jar = PersistentCookieJar(context, databaseName)

        assertTrue(jar.getAll(origin).isEmpty())
        assertTrue(jar.loadForRequest("https://login.example.test/".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://api.example.test/".toHttpUrl()).isEmpty())
    }

    @Test
    fun aLaterFreshLegacyRowReplacesAnEarlierExpiredIdentity() {
        createVersionOne().use { db ->
            insertLegacy(db, "login.example.test", "sid", "sid=expired; Domain=example.test; Path=/; Max-Age=0")
            insertLegacy(db, "api.example.test", "sid", "sid=fresh; Domain=example.test; Path=/")
        }
        val jar = PersistentCookieJar(context, databaseName)

        assertEquals(listOf("fresh"), jar.loadForRequest(origin).map { it.value })
        assertEquals(listOf("fresh"), jar.loadForRequest("https://login.example.test/".toHttpUrl()).map { it.value })
        assertEquals(listOf("fresh"), jar.loadForRequest("https://api.example.test/".toHttpUrl()).map { it.value })
    }

    @Test
    fun failedMigrationRollsBackVersionOneAndCanBeRetried() {
        createVersionOne().use { db ->
            insertLegacy(db, "example.test", "sid", "sid=keep; Path=/")
            // 只在測試資料庫建立名稱衝突，令真實 migration SQL 失敗。
            db.execSQL("CREATE TABLE cookies_v1 (sentinel TEXT)")
        }
        val jar = PersistentCookieJar(context, databaseName)
        assertThrows(SQLiteException::class.java) { jar.getAll(origin) }
        openDatabase().use { db ->
            assertEquals(1, db.version)
            db.rawQuery("SELECT cookie FROM cookies WHERE name = ?", arrayOf("sid")).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("sid=keep; Path=/", cursor.getString(0))
            }
            db.execSQL("DROP TABLE cookies_v1")
        }

        assertEquals(listOf("keep"), jar.loadForRequest(origin).map { it.value })
        openDatabase().use { assertEquals(2, it.version) }
    }

    @Test
    fun concurrentExpiryCleanupDoesNotDeleteAnotherInstancesFreshReplacement() {
        val reader = PersistentCookieJar(context, databaseName)
        val writer = PersistentCookieJar(context, databaseName)
        reader.getAll(origin)
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(25) { attempt ->
                openDatabase().use { db ->
                    val inserted = db.insertWithOnConflict("cookies", null, ContentValues().apply {
                        put("origin", origin.host)
                        put("domain", origin.host)
                        put("path", "/")
                        put("name", "sid")
                        put("cookie", "sid=expired; Max-Age=0; Path=/")
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                    check(inserted != -1L) { "Unable to seed the expired cookie" }
                }
                val start = CountDownLatch(1)
                val cleanup = executor.submit { check(start.await(5, TimeUnit.SECONDS)); reader.getAll(origin) }
                val replacement = executor.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    writer.saveFromResponse(origin, listOf(requireNotNull(Cookie.parse(origin, "sid=fresh-$attempt; Path=/"))))
                }
                start.countDown()
                cleanup.get(5, TimeUnit.SECONDS)
                replacement.get(5, TimeUnit.SECONDS)
                assertEquals(listOf("fresh-$attempt"), reader.loadForRequest(origin).map { it.value })
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun createVersionOne(): SQLiteDatabase = context.openOrCreateDatabase(databaseName, 0, null).apply {
        execSQL("CREATE TABLE cookies (url TEXT, name TEXT, cookie TEXT, PRIMARY KEY(url, name))")
        version = 1
    }

    private fun openDatabase(): SQLiteDatabase = SQLiteDatabase.openDatabase(
        context.getDatabasePath(databaseName).absolutePath, null, SQLiteDatabase.OPEN_READWRITE
    )

    private fun insertLegacy(db: SQLiteDatabase, host: String, name: String, rawCookie: String) {
        db.insertOrThrow("cookies", null, ContentValues().apply {
            put("url", host)
            put("name", name)
            put("cookie", rawCookie)
        })
    }
}
