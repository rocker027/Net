package com.drake.net.utils

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import com.drake.net.Net
import com.drake.net.NetConfig
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class UriRequestBodyTest {
    private val uri = Uri.parse("content://net-uri-upload/document/fixture")
    private lateinit var provider: MetadataProvider

    @Before
    fun setUp() {
        NetConfig.app = RuntimeEnvironment.getApplication()
        provider = Robolectric.buildContentProvider(MetadataProvider::class.java)
            .create("net-uri-upload").get()
    }

    @Test
    fun unknownSizeUploadsNonemptyContentThroughMultipart() {
        provider.size = null
        shadowOf(NetConfig.app.contentResolver).registerInputStream(uri, ByteArrayInputStream("payload".toByteArray()))
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("ok"))

            assertEquals("ok", Net.post(server.url("/upload").toString()) {
                param("file", uri)
            }.execute<String>())

            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("chunked", request.getHeader("Transfer-Encoding"))
            assertTrue(request.body.readUtf8().contains("payload"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun knownEmptyFileKeepsZeroLength() {
        provider.size = 0
        shadowOf(NetConfig.app.contentResolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf()))
        val body = uri.toRequestBody()
        val buffer = Buffer()

        assertEquals(0L, body.contentLength())
        body.writeTo(buffer)
        assertEquals(0L, buffer.size)
    }

    @Test
    fun knownSizeMatchesWrittenBytes() {
        provider.size = 7
        shadowOf(NetConfig.app.contentResolver).registerInputStream(uri, ByteArrayInputStream("payload".toByteArray()))
        val body = uri.toRequestBody()
        val buffer = Buffer()

        assertEquals(7L, body.contentLength())
        body.writeTo(buffer)
        assertEquals("payload", buffer.readUtf8())
    }

    @Test
    fun unavailableOrNegativeMetadataIsUnknown() {
        provider.size = null
        assertEquals(-1L, uri.toRequestBody().contentLength())
        provider.size = -5
        assertEquals(-1L, uri.toRequestBody().contentLength())
        provider.omitSizeColumn = true
        assertEquals(-1L, uri.toRequestBody().contentLength())
        provider.emptyCursor = true
        assertEquals(-1L, uri.toRequestBody().contentLength())
    }

    @Test
    fun missingInputStreamFailsInsteadOfUploadingEmptyContent() {
        provider.size = 0
        shadowOf(NetConfig.app.contentResolver).registerInputStreamSupplier(uri) { null }
        val body = uri.toRequestBody()

        assertThrows(FileNotFoundException::class.java) { body.writeTo(Buffer()) }
    }

    @Test
    fun openingFailureIsPropagated() {
        val failure = FileNotFoundException("provider unavailable")
        shadowOf(NetConfig.app.contentResolver).registerInputStreamSupplier(uri) { throw failure }
        val body = uri.toRequestBody()

        assertSame(failure, assertThrows(FileNotFoundException::class.java) { body.writeTo(Buffer()) })
    }

    @Test
    fun readingFailureIsPropagatedAndClosesTheStream() {
        val failure = IOException("provider read failed")
        var closed = false
        val stream = object : InputStream() {
            override fun read(): Int = throw failure
            override fun close() { closed = true }
        }
        shadowOf(NetConfig.app.contentResolver).registerInputStream(uri, stream)
        val body = uri.toRequestBody()

        assertSame(failure, assertThrows(IOException::class.java) { body.writeTo(Buffer()) })
        assertTrue(closed)
    }

    class MetadataProvider : ContentProvider() {
        var size: Long? = null
        var omitSizeColumn = false
        var emptyCursor = false

        override fun onCreate() = true
        override fun getType(uri: Uri) = "text/plain"
        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? = null
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?
        ): Cursor {
            val columns = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
                .filterNot { omitSizeColumn && it == OpenableColumns.SIZE }.toTypedArray()
            return MatrixCursor(columns).apply {
                if (!emptyCursor) addRow(columns.map {
                    when (it) {
                        OpenableColumns.DISPLAY_NAME -> "upload.txt"
                        OpenableColumns.SIZE -> size
                        else -> null
                    }
                }.toTypedArray())
            }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    }
}
