package com.drake.net.cookie

import android.content.Context
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class PersistentCookieJarTest {

    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var jar: PersistentCookieJar
    private val origin = "https://example.test/admin/login".toHttpUrl()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        databaseName = "cookie-test-${UUID.randomUUID()}"
        jar = PersistentCookieJar(context, databaseName)
    }

    @After
    fun tearDown() {
        jar.clear()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun secureCookieIsOnlySentOverHttpsAndToItsMatchingPath() {
        jar.saveFromResponse(origin, listOf(cookie("sid=secure; Path=/admin; Secure")))

        assertTrue(jar.loadForRequest("http://example.test/admin".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://example.test/public".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://example.test/administrator".toHttpUrl()).isEmpty())
        assertEquals(listOf("secure"), values(jar, "https://example.test/admin"))
        assertEquals(listOf("secure"), values(jar, "https://example.test/admin/child"))
        assertEquals(1, jar.getAll(origin).size)
    }

    @Test
    fun matchingPlainCookieAndExistingHostIsolationArePreserved() {
        jar.saveFromResponse(origin, listOf(cookie("public=value; Path=/")))

        assertEquals(listOf("value"), values(jar, "http://example.test/public"))
        assertTrue(jar.loadForRequest("https://other.test/".toHttpUrl()).isEmpty())
    }

    @Test
    fun deletionCookieRemovesExistingValueWithoutRemovingOtherNames() {
        jar.saveFromResponse(origin, listOf(cookie("sid=old; Path=/admin"), cookie("other=keep; Path=/")))

        jar.saveFromResponse(origin, listOf(cookie("sid=; Max-Age=0; Path=/admin")))

        assertEquals(listOf("other"), jar.getAll(origin).map { it.name })
        val reopened = PersistentCookieJar(context, databaseName)
        assertEquals(listOf("keep"), values(reopened, "https://example.test/admin"))
    }

    @Test
    fun expiredCookieDeletionDoesNotAffectTheSameNameOnAnotherHost() {
        val otherOrigin = "https://other.test/admin".toHttpUrl()
        jar.saveFromResponse(origin, listOf(cookie("sid=first; Path=/admin")))
        jar.saveFromResponse(otherOrigin, listOf(cookie("sid=second; Path=/admin", otherOrigin)))

        jar.saveFromResponse(origin, listOf(cookie("sid=expired; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/admin")))

        assertTrue(jar.getAll(origin).isEmpty())
        assertEquals(listOf("second"), values(jar, otherOrigin.toString()))
    }

    @Test
    fun deletionCookieDoesNotRemoveTheSameNameOnAnotherPath() {
        jar.saveFromResponse(origin, listOf(cookie("sid=keep; Path=/admin")))

        jar.saveFromResponse(origin, listOf(cookie("sid=; Max-Age=0; Path=/public")))

        assertEquals(listOf("keep"), values(jar, "https://example.test/admin"))
        assertEquals("/admin", jar.getAll(origin).single().path)
    }

    @Test
    fun deletionCookieRequiresTheStoredDomainIdentity() {
        val subdomain = "https://sub.example.test/admin".toHttpUrl()
        jar.saveFromResponse(subdomain, listOf(cookie("sid=keep; Domain=example.test; Path=/admin", subdomain)))

        jar.saveFromResponse(subdomain, listOf(cookie("sid=; Max-Age=0; Path=/admin", subdomain)))

        assertEquals(listOf("keep"), values(jar, subdomain.toString()))
        jar.saveFromResponse(subdomain, listOf(cookie("sid=; Max-Age=0; Domain=example.test; Path=/admin", subdomain)))
        assertTrue(jar.getAll(subdomain).isEmpty())
    }

    private fun cookie(header: String, url: HttpUrl = origin): Cookie = requireNotNull(Cookie.parse(url, header))

    private fun values(cookieJar: PersistentCookieJar, url: String): List<String> =
        cookieJar.loadForRequest(url.toHttpUrl()).map { it.value }
}
