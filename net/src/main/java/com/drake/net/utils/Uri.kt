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

package com.drake.net.utils

import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.drake.net.NetConfig
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.FileNotFoundException

fun Uri.fileName(): String? {
    return DocumentFile.fromSingleUri(NetConfig.app, this)?.name
}

fun Uri.mediaType(): MediaType? {
    val fileName = DocumentFile.fromSingleUri(NetConfig.app, this)?.name
    val fileExtension = MimeTypeMap.getFileExtensionFromUrl(fileName)
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension)?.toMediaTypeOrNull()
}

/**
 * 当Uri指向的文件不存在时将抛出异常[FileNotFoundException]
 */
@Throws(FileNotFoundException::class)
fun Uri.toRequestBody(): RequestBody {
    val contentResolver = NetConfig.app.contentResolver
    val contentLength = queryContentLength()
    val contentType = mediaType()
    return object : RequestBody() {
        override fun contentType(): MediaType? {
            return contentType
        }

        override fun contentLength() = contentLength

        override fun writeTo(sink: BufferedSink) {
            val input = contentResolver.openInputStream(this@toRequestBody)
                ?: throw FileNotFoundException("Unable to open URI content")
            input.use {
                sink.writeAll(it.source())
            }
        }
    }
}

private fun Uri.queryContentLength(): Long {
    // DocumentFile.length() 会把未知长度当成 0，必须保留 null 与空文件的区别。
    return NetConfig.app.contentResolver.query(this, arrayOf(OpenableColumns.SIZE), null, null, null)
        .use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) return@use -1L
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex < 0 || cursor.isNull(sizeIndex)) -1L
            else cursor.getLong(sizeIndex).takeIf { it >= 0 } ?: -1L
        }
}
