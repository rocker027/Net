/*
 * MIT License
 *
 * Copyright (c) 2023 劉強東 https://github.com/liangjingkanji
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.drake.net.cookie

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 持久化存储Cookie
 * @param dbName 数据库名称, 设置多个名称可以让不同的客户端共享不同的cookies
 */
class PersistentCookieJar(
    val context: Context,
    val dbName: String = "net_cookies",
) : CookieJar {

    private val sqlHelper: SQLiteOpenHelper = object : SQLiteOpenHelper(context, dbName, null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            createTable(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                // SQLiteOpenHelper 已包住升级事务；任何 SQL 失败都会保留旧表和版本。
                db.execSQL("ALTER TABLE cookies RENAME TO cookies_v1")
                createTable(db)
                db.rawQuery("SELECT url, cookie FROM cookies_v1 ORDER BY rowid", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val origin = originUrl(cursor.getString(0)) ?: continue
                        val value = cursor.getString(1) ?: continue
                        val cookie = Cookie.parse(origin, value) ?: continue
                        if (cookie.expiresAt > System.currentTimeMillis()) {
                            // 同一身份曾由多个 host 写入时，按最后一条可解析记录处理。
                            putCookie(db, origin, cookie)
                        } else {
                            // 新记录已经过期时，不能让先前搬入的旧值重新生效。
                            db.delete("cookies", "name = ? AND domain = ? AND path = ?", identity(cookie))
                        }
                    }
                }
                db.execSQL("DROP TABLE cookies_v1")
            }
        }
    }

    /**
     * 添加来源域名允许设置的 Cookie；身份由 name/domain/path 共同决定。
     */
    fun addAll(url: HttpUrl, cookies: List<Cookie>) {
        transaction { db ->
            cookies.forEach { cookie ->
                val parsed = Cookie.parse(url, cookie.toString()) ?: return@forEach
                // 手动 addAll 也不能让不相关的来源设置或删除其他域名的 Cookie。
                if (parsed.domain != cookie.domain || parsed.hostOnly != cookie.hostOnly) return@forEach
                if (parsed.expiresAt > System.currentTimeMillis()) {
                    putCookie(db, url, parsed)
                } else {
                    db.delete("cookies", "name = ? AND domain = ? AND path = ?", identity(parsed))
                }
            }
        }
    }

    /**
     * 获取对该 host 可见的全部有效 Cookie，包含父域的 Domain Cookie。
     * 不按 path/secure 过滤；实际请求应使用 [loadForRequest]。
     */
    fun getAll(url: HttpUrl): List<Cookie> = transaction { db ->
        readCookies(db, url)
    }

    /**
     * 删除该 host 可见的全部 Cookie，不限制 path/secure。
     * 删除共享的 Domain Cookie 也会影响其他匹配该域的 host。
     */
    fun remove(url: HttpUrl) {
        removeCookies(url, null)
    }

    /**
     * 删除该 host 可见的指定名称 Cookie，涵盖全部 path 和共享 Domain Cookie。
     */
    fun remove(url: HttpUrl, cookieName: String) {
        removeCookies(url, cookieName)
    }

    /**
     * 清除应用所有Cookie
     */
    fun clear() {
        sqlHelper.writableDatabase.delete("cookies", null, null)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return getAll(url).filter { it.matches(url) }.sortedByDescending { it.path.length }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        addAll(url, cookies)
    }

    private fun createTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE cookies (domain TEXT NOT NULL, path TEXT NOT NULL, name TEXT NOT NULL, " +
                "origin TEXT NOT NULL, cookie TEXT NOT NULL, PRIMARY KEY(domain, path, name))"
        )
    }

    private fun putCookie(db: SQLiteDatabase, origin: HttpUrl, cookie: Cookie) {
        val row = db.insertWithOnConflict("cookies", null, ContentValues().apply {
            put("domain", cookie.domain)
            put("path", cookie.path)
            put("name", cookie.name)
            put("origin", origin.host)
            put("cookie", cookie.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        check(row != -1L) { "Unable to persist cookie" }
    }

    private fun readCookies(db: SQLiteDatabase, url: HttpUrl): List<Cookie> {
        val domains = generateSequence(url.host) { host ->
            host.substringAfter('.', "").takeIf { it.isNotEmpty() }
        }.toList()
        val placeholders = domains.joinToString { "?" }
        val query = "SELECT name, domain, path, origin, cookie FROM cookies WHERE domain IN ($placeholders)"
        return db.rawQuery(query, domains.toTypedArray()).use { cursor ->
            val cookies = mutableListOf<Cookie>()
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                val domain = cursor.getString(1)
                val path = cursor.getString(2)
                val origin = originUrl(cursor.getString(3))
                val value = cursor.getString(4)
                // 必须按设置时的 host 解析，不能把 host-only Cookie 扩大到查询 host。
                val cookie = origin?.let { Cookie.parse(it, value) }
                if (cookie == null || cookie.expiresAt <= System.currentTimeMillis() ||
                    cookie.name != name || cookie.domain != domain || cookie.path != path
                ) {
                    db.delete("cookies", "name = ? AND domain = ? AND path = ? AND cookie = ?", arrayOf(name, domain, path, value))
                } else if (url.host == domain || !cookie.hostOnly && url.host.endsWith(".$domain")) {
                    cookies.add(cookie)
                }
            }
            cookies
        }
    }

    private fun removeCookies(url: HttpUrl, name: String?) {
        transaction { db ->
            readCookies(db, url).filter { name == null || it.name == name }.forEach { cookie ->
                db.delete("cookies", "name = ? AND domain = ? AND path = ?", identity(cookie))
            }
        }
    }

    private fun identity(cookie: Cookie) = arrayOf(cookie.name, cookie.domain, cookie.path)

    private fun originUrl(host: String?): HttpUrl? = try {
        host?.let { HttpUrl.Builder().scheme("https").host(it).build() }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = sqlHelper.writableDatabase
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }
}
