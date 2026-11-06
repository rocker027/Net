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

    @Test
    fun sameNameCookiesOnDifferentPathsCoexistAndUseLongestPathFirst() {
        jar.saveFromResponse(origin, listOf(cookie("sid=root; Path=/"), cookie("sid=admin; Path=/admin")))

        assertEquals(listOf("root"), values(jar, "https://example.test/public"))
        assertEquals(listOf("admin", "root"), values(jar, "https://example.test/admin"))
        assertEquals(2, jar.getAll(origin).size)
    }

    @Test
    fun domainCookieReachesSiblingSubdomainsButNotSuffixLookalikes() {
        val login = "https://login.example.test/".toHttpUrl()
        jar.saveFromResponse(login, listOf(cookie("sid=shared; Domain=example.test; Path=/", login)))

        assertEquals(listOf("shared"), values(jar, "https://api.example.test/"))
        assertEquals(listOf("shared"), values(jar, "https://example.test/"))
        assertTrue(values(jar, "https://notexample.test/").isEmpty())
        assertTrue(values(jar, "https://example.test.evil.test/").isEmpty())
    }

    @Test
    fun sameNameCookiesOnParentAndChildDomainsCoexist() {
        val subdomain = "https://sub.example.test/".toHttpUrl()
        jar.saveFromResponse(subdomain, listOf(
            cookie("sid=parent; Domain=example.test; Path=/", subdomain),
            cookie("sid=child; Path=/", subdomain)
        ))

        assertEquals(setOf("parent", "child"), values(jar, subdomain.toString()).toSet())
        assertEquals(listOf("parent"), values(jar, "https://other.example.test/"))
    }

    @Test
    fun replacingDomainCookieWithHostOnlyCookieDoesNotExpandItsScopeOnReopen() {
        jar.saveFromResponse(origin, listOf(cookie("sid=shared; Domain=example.test; Path=/")))
        jar.saveFromResponse(origin, listOf(cookie("sid=host; Path=/")))
        val reopened = PersistentCookieJar(context, databaseName)

        assertEquals(listOf("host"), values(reopened, "https://example.test/"))
        assertTrue(values(reopened, "https://sub.example.test/").isEmpty())
        assertTrue(reopened.getAll(origin).single().hostOnly)
    }

    @Test
    fun replacingHostOnlyCookieWithDomainCookieSharesOnlyTheMatchingDomain() {
        jar.saveFromResponse(origin, listOf(cookie("sid=host; Path=/")))
        jar.saveFromResponse(origin, listOf(cookie("sid=shared; Domain=example.test; Path=/")))

        assertEquals(listOf("shared"), values(jar, "https://sub.example.test/"))
        assertEquals(1, jar.getAll(origin).size)
        assertTrue(values(jar, "https://other.test/").isEmpty())
    }

    @Test
    fun anUnrelatedOriginCannotSaveOrDeleteAnotherDomainsCookie() {
        jar.saveFromResponse(origin, listOf(cookie("sid=keep; Domain=example.test; Path=/")))
        val unrelated = "https://other.test/".toHttpUrl()
        jar.addAll(unrelated, listOf(cookie("sid=replace; Domain=example.test; Path=/")))
        jar.addAll(unrelated, listOf(cookie("sid=; Domain=example.test; Path=/; Max-Age=0")))

        assertEquals(listOf("keep"), values(jar, "https://example.test/"))
        assertTrue(jar.getAll(unrelated).isEmpty())
    }

    @Test
    fun manualRemovalIncludesVisibleDomainCookiesAcrossAllPaths() {
        val subdomain = "https://sub.example.test/public".toHttpUrl()
        val sibling = "https://other.example.test/".toHttpUrl()
        jar.saveFromResponse(subdomain, listOf(
            cookie("sid=shared; Domain=example.test; Path=/admin; Secure", subdomain),
            cookie("sid=host; Path=/", subdomain),
            cookie("keep=value; Path=/", subdomain)
        ))
        jar.saveFromResponse(sibling, listOf(cookie("sid=sibling; Path=/", sibling)))
        assertEquals(3, jar.getAll("http://sub.example.test/public".toHttpUrl()).size)

        jar.remove(subdomain, "sid")

        assertEquals(listOf("value"), values(jar, subdomain.toString()))
        assertEquals(listOf("sibling"), values(jar, sibling.toString()))
        jar.remove(subdomain)
        assertTrue(jar.getAll(subdomain).isEmpty())
        assertEquals(listOf("sibling"), values(jar, sibling.toString()))
    }

    @Test
    fun deletionFromASiblingOriginUsesCookieIdentityInsteadOfSettingHost() {
        val login = "https://login.example.test/".toHttpUrl()
        val api = "https://api.example.test/".toHttpUrl()
        jar.saveFromResponse(login, listOf(cookie("sid=shared; Domain=example.test; Path=/", login)))

        jar.saveFromResponse(api, listOf(cookie("sid=; Domain=example.test; Path=/; Max-Age=0", api)))

        assertTrue(jar.getAll(login).isEmpty())
        assertTrue(jar.getAll(api).isEmpty())
    }

    private fun cookie(header: String, url: HttpUrl = origin): Cookie = requireNotNull(Cookie.parse(url, header))

    private fun values(cookieJar: PersistentCookieJar, url: String): List<String> =
        cookieJar.loadForRequest(url.toHttpUrl()).map { it.value }
}
