/*
 * MIT License
 *
 * Copyright (c) 2026 rocker027 (maintenance fork)
 */

package com.drake.net.response

import com.drake.net.exception.DownloadFileException
import com.drake.net.tag.NetTag
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64

class ResponseExtensionTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun quotedFilenameDoesNotIncludeQuotesOrFollowingParameters() {
        response(disposition = "attachment; filename=\"test report.apk\"; size=42").use {
            assertEquals("test report.apk", it.fileName())
        }
    }

    @Test
    fun quotedFilenameMayContainSemicolonAndEscapedQuote() {
        response(disposition = "attachment; filename=\"report; \\\"final\\\".txt\"; size=42").use {
            assertEquals("report; \"final\".txt", it.fileName())
        }
    }

    @Test
    fun extendedFilenameDecodesExactCharsetAndLanguagePrefix() {
        response(disposition = "attachment; filename*=UTF-8'en'Test%20report%2B1.apk").use {
            assertEquals("Test report+1.apk", it.fileName())
        }
    }

    @Test
    fun extendedFilenameTakesPrecedenceOverCompatibilityFilename() {
        response(disposition = "attachment; filename=plain.txt; filename*=UTF-8''Test.txt").use {
            assertEquals("Test.txt", it.fileName())
        }
    }

    @Test
    fun invalidExtendedFilenameFallsBackToPlainFilename() {
        response(disposition = "attachment; filename*=UTF-8''bad%ZZ.txt; filename=plain.txt").use {
            assertEquals("plain.txt", it.fileName())
        }
    }

    @Test
    fun explicitFilenameTakesPrecedenceOverHeaderAndUrl() {
        response(disposition = "attachment; filename=remote.txt", fileName = "chosen.txt").use {
            assertEquals("chosen.txt", it.fileName())
        }
    }

    @Test
    fun urlFallbackPreservesPlusAndDecodesEncodedName() {
        response(url = "https://example.test/report%20one+two.txt", decode = true).use {
            assertEquals("report one+two.txt", it.fileName())
        }
    }

    @Test
    fun emptyUrlNameFallsBackToNonemptyGeneratedName() {
        response(url = "https://example.test/").use {
            assertTrue(it.fileName().startsWith("unknown_"))
        }
    }

    @Test
    fun ordinaryFilenameDownloadsWithinDirectoryAndClosesBody() {
        val directory = temporaryFolder.newFolder("downloads")
        val body = TrackingBody("new content")

        val file = response(directory, body, disposition = "attachment; filename=report.txt").file()

        assertEquals(File(directory, "report.txt").canonicalFile, file?.canonicalFile)
        assertEquals("new content", file?.readText())
        assertTrue(body.closed)
    }

    @Test
    fun remoteNamesWithSeparatorsOrDotSegmentsAreRejected() {
        listOf("../outside.txt", "folder/report.txt", "folder\\report.txt", ".", "..").forEachIndexed { index, name ->
            val directory = temporaryFolder.newFolder("downloads-$index")
            val body = TrackingBody("new content")

            assertThrows(DownloadFileException::class.java) {
                response(directory, body, disposition = "attachment; filename=$name").file()
            }

            assertTrue("closed for $name", body.closed)
            assertTrue("no output for $name", directory.listFiles()!!.isEmpty())
        }
        assertFalse(File(temporaryFolder.root, "outside.txt").exists())
    }

    @Test
    fun decodedHeaderAndUrlNamesCannotIntroduceDirectorySeparators() {
        val directory = temporaryFolder.newFolder("downloads")
        val headerBody = TrackingBody("header content")
        val urlBody = TrackingBody("url content")

        assertThrows(DownloadFileException::class.java) {
            response(directory, headerBody, disposition = "attachment; filename*=UTF-8''..%2Foutside.txt").file()
        }
        assertThrows(DownloadFileException::class.java) {
            response(directory, urlBody, url = "https://example.test/..%252Foutside.txt", decode = true).file()
        }

        assertTrue(headerBody.closed)
        assertTrue(urlBody.closed)
        assertFalse(File(temporaryFolder.root, "outside.txt").exists())
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test
    fun canonicalTargetCannotFollowSymlinkOutsideDownloadDirectory() {
        val directory = temporaryFolder.newFolder("downloads")
        val outside = temporaryFolder.newFile("outside.txt").apply { writeText("old content") }
        Files.createSymbolicLink(File(directory, "report.txt").toPath(), outside.toPath())
        val body = TrackingBody("new content")

        assertThrows(DownloadFileException::class.java) {
            response(directory, body, fileName = "report.txt").file()
        }

        assertEquals("old content", outside.readText())
        assertTrue(body.closed)
    }

    @Test
    fun canonicalTemporaryTargetCannotFollowSymlinkOutsideDownloadDirectory() {
        val directory = temporaryFolder.newFolder("downloads")
        val outside = temporaryFolder.newFile("outside.txt").apply { writeText("old content") }
        Files.createSymbolicLink(File(directory, "report.txt.downloading").toPath(), outside.toPath())
        val body = TrackingBody("new content")

        assertThrows(DownloadFileException::class.java) {
            response(directory, body, fileName = "report.txt", temporary = true).file()
        }

        assertEquals("old content", outside.readText())
        assertTrue(body.closed)
    }

    @Test
    fun explicitCompletePathDoesNotUseRemoteFilename() {
        val directory = temporaryFolder.newFolder("downloads")
        val target = File(directory, "chosen.txt")

        val file = response(target, disposition = "attachment; filename=../ignored.txt").file()

        assertEquals(target, file)
        assertEquals("new content", target.readText())
        assertFalse(File(temporaryFolder.root, "ignored.txt").exists())
    }

    @Test
    fun temporaryDownloadKeepsResolvedConflictNameAndExistingFiles() {
        val directory = temporaryFolder.newFolder("downloads")
        val existing = File(directory, "report.txt").apply { writeText("old content") }
        val firstConflict = File(directory, "report_(1).txt").apply { writeText("first content") }
        val body = TrackingBody("new content")

        val file = response(directory, body, fileName = "report.txt", temporary = true, conflict = true).file()

        assertEquals(File(directory, "report_(2).txt"), file)
        assertEquals("old content", existing.readText())
        assertEquals("first content", firstConflict.readText())
        assertEquals("new content", file?.readText())
        assertFalse(File(directory, "report_(2).txt.downloading").exists())
        assertTrue(body.closed)
    }

    @Test
    fun temporaryRenameFailureDoesNotReturnSuccess() {
        val directory = temporaryFolder.newFolder("downloads")
        val target = File(directory, "report.txt")
        val body = TrackingBody("new content", beforeRead = {
            check(target.mkdir())
            File(target, "keep.txt").writeText("keep content")
        })

        assertThrows(DownloadFileException::class.java) {
            response(directory, body, fileName = "report.txt", temporary = true).file()
        }

        assertEquals("keep content", File(target, "keep.txt").readText())
        assertTrue(body.closed)
    }

    @Test
    fun md5HitReturnsExistingFileAndClosesUnreadBody() {
        val directory = temporaryFolder.newFolder("downloads")
        val existing = File(directory, "report.txt").apply { writeText("old content") }
        val md5 = Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(existing.readBytes()))
        val body = TrackingBody("unused content")

        val file = response(directory, body, fileName = "report.txt", md5 = md5).file()

        assertEquals(existing, file)
        assertEquals("old content", existing.readText())
        assertEquals(0, body.readCount)
        assertTrue(body.closed)
    }

    @Test
    fun readFailureClosesBodyAndDoesNotReplaceFinalFile() {
        val directory = temporaryFolder.newFolder("downloads")
        val existing = File(directory, "report.txt").apply { writeText("old content") }
        val body = TrackingBody("new content", beforeRead = { throw IOException("fixture read failure") })

        assertThrows(DownloadFileException::class.java) {
            response(directory, body, fileName = "report.txt", temporary = true).file()
        }

        assertEquals("old content", existing.readText())
        assertTrue(body.closed)
    }

    @Test
    fun outputCreationFailureClosesUnreadBody() {
        val target = File(temporaryFolder.root, "missing-directory/report.txt")
        val body = TrackingBody("unused content")

        assertThrows(DownloadFileException::class.java) {
            response(target, body).file()
        }

        assertEquals(0, body.readCount)
        assertTrue(body.closed)
        assertFalse(target.exists())
    }

    private fun response(
        destination: File = temporaryFolder.root,
        body: ResponseBody = TrackingBody("new content"),
        disposition: String? = null,
        fileName: String? = null,
        url: String = "https://example.test/from-url.txt",
        decode: Boolean = false,
        temporary: Boolean = false,
        conflict: Boolean = false,
        md5: String? = null
    ): Response {
        val request = Request.Builder().url(url)
            .tag(NetTag.DownloadFileDir::class.java, NetTag.DownloadFileDir(destination))
            .tag(NetTag.DownloadFileNameDecode::class.java, NetTag.DownloadFileNameDecode(decode))
            .tag(NetTag.DownloadTempFile::class.java, NetTag.DownloadTempFile(temporary))
            .tag(NetTag.DownloadFileConflictRename::class.java, NetTag.DownloadFileConflictRename(conflict))
            .tag(NetTag.DownloadFileMD5Verify::class.java, NetTag.DownloadFileMD5Verify(md5 != null))
        if (fileName != null) request.tag(NetTag.DownloadFileName::class.java, NetTag.DownloadFileName(fileName))
        return Response.Builder().request(request.build()).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body(body).apply {
                if (disposition != null) header("Content-Disposition", disposition)
                if (md5 != null) header("Content-MD5", md5)
            }.build()
    }

    private class TrackingBody(content: String, beforeRead: (() -> Unit)? = null) : ResponseBody() {
        var closed = false
        var readCount = 0
        private val bytes = content.toByteArray()
        private val bufferedSource = object : ForwardingSource(Buffer().write(bytes)) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (readCount++ == 0) beforeRead?.invoke()
                return super.read(sink, byteCount)
            }

            override fun close() {
                closed = true
                super.close()
            }
        }.buffer()

        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = bytes.size.toLong()
        override fun source(): BufferedSource = bufferedSource
    }
}
