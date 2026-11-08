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

package com.drake.net.response

import com.drake.net.component.Progress
import com.drake.net.exception.ConvertException
import com.drake.net.exception.DownloadFileException
import com.drake.net.exception.NetException
import com.drake.net.reflect.typeTokenOf
import com.drake.net.request.*
import com.drake.net.tag.NetTag
import com.drake.net.utils.md5
import okhttp3.Response
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.lang.reflect.Type
import java.net.URLDecoder
import kotlin.coroutines.cancellation.CancellationException

/**
 * 按照以下顺序返回最终的下载文件的名称
 *
 * 1. 指定文件名
 * 2. 响应头文件名
 * 3. 请求URL路径
 * 4. 时间戳
 */
fun Response.fileName(): String {
    request.downloadFileName().takeUnless { it.isNullOrBlank() }?.let { return it }
    val disposition = header("Content-Disposition")
    if (disposition != null) {
        val parameters = dispositionParameters.findAll(disposition).associate { match ->
            val name = match.groupValues[1].lowercase()
            val rawValue = match.groupValues[2].trim()
            val value = if (rawValue.length >= 2 && rawValue.startsWith('"') && rawValue.endsWith('"')) {
                rawValue.substring(1, rawValue.length - 1).replace(quotedPair) { it.groupValues[1] }
            } else rawValue
            name to value
        }
        // filename* 带有字符集与语言前缀，优先于兼容旧客户端的 filename。
        parameters["filename*"]?.let { value ->
            val parts = value.split('\'', limit = 3)
            if (parts.size == 3 && parts[0].isNotBlank()) {
                try {
                    decodeFileName(parts[2], parts[0]).takeUnless { it.isBlank() }?.let { return it }
                } catch (_: Exception) {
                    // 不支持的字符集或损坏的转义回退到普通文件名。
                }
            }
        }
        parameters["filename"]?.takeUnless { it.isBlank() }?.let { return it }
    }

    var fileName = request.url.pathSegments.last()
    fileName = if (fileName.isBlank()) "unknown_${System.currentTimeMillis()}" else {
        if (request.downloadFileNameDecode()) {
            try {
                decodeFileName(fileName, "UTF-8")
            } catch (_: Exception) {
                fileName
            }
        } else fileName
    }
    return fileName
}

private val dispositionParameters = Regex("""(?:^|;)\s*([^=;\s]+)\s*=\s*("(?:\\.|[^"\\])*"|[^;]*)""")
private val quotedPair = Regex("""\\(.)""")

private fun decodeFileName(value: String, charset: String): String {
    // 文件名不是表单字段，字面上的 + 不代表空格。
    return URLDecoder.decode(value.replace("+", "%2B"), charset)
}

private fun File.requireDownloadChild(directory: File): File {
    if (canonicalFile.parentFile != directory.canonicalFile) {
        throw IOException("Download file must remain within its destination directory")
    }
    return this
}

private fun downloadChild(directory: File, name: String): File {
    // 必须在构造 File 前检查原始名称，避免 File.name 抹去路径部分。
    if (name.isBlank() || name == "." || name == ".." ||
        name.contains('/') || name.contains('\\') || name.contains('\u0000')
    ) {
        throw IOException("Download filename must be a single filename")
    }
    return File(directory, name).requireDownloadChild(directory)
}

// 同一进程只同步选名与落盘，不在网络读取期间持锁。
private val downloadFileLock = Any()

private fun availableDownloadFile(original: File, directory: File?, reserve: Boolean): File {
    val extension = original.extension.let { if (it.isEmpty()) "" else ".$it" }
    var candidate = original
    var index = 1L
    while (true) {
        if (directory != null) candidate.requireDownloadChild(directory)
        if (if (reserve) candidate.createNewFile() else !candidate.exists()) return candidate
        candidate = File(original.parentFile, "${original.nameWithoutExtension}_(${index++})$extension")
    }
}

/**
 * 下载到指定文件
 */
@Throws(DownloadFileException::class)
fun Response.file(): File? {
    var temporaryFile: File? = null
    try {
        val responseBody = body ?: return null
        return responseBody.use {
            val destination = File(request.downloadFileDir())
            val directory = destination.takeIf { it.isDirectory }
            val requestedFile = if (directory != null) downloadChild(directory, fileName()) else destination
            if (requestedFile.exists() && request.downloadMd5Verify()) {
                val md5Header = header("Content-MD5")
                if (md5Header != null && requestedFile.md5(true) == md5Header) {
                    val fileSize = requestedFile.length()
                    val progress = Progress().apply {
                        currentByteCount = fileSize
                        totalByteCount = fileSize
                        intervalByteCount = fileSize
                        finish = true
                    }
                    request.tagOf<NetTag.DownloadListeners>()?.forEach { it.onProgress(progress.snapshot()) }
                    return@use requestedFile
                }
            }

            val useTemporaryFile = request.downloadTempFile()
            val conflictRename = request.downloadConflictRename()
            val outputFile = if (useTemporaryFile) {
                // 每个请求持有独立临时文件，失败只能清除自己的输出。
                File.createTempFile(".net-", ".downloading", requestedFile.absoluteFile.parentFile)
                    .also { temporaryFile = it }
            } else if (conflictRename) {
                synchronized(downloadFileLock) { availableDownloadFile(requestedFile, directory, reserve = true) }
            } else requestedFile
            if (directory != null) outputFile.requireDownloadChild(directory)
            outputFile.sink().buffer().use { sink -> sink.writeAll(responseBody.source()) }
            if (!useTemporaryFile) return@use outputFile

            // 传输结束后才选最终名称；避免空占位文件被另一个请求的 MD5 检查误判为命中。
            synchronized(downloadFileLock) {
                val finalFile = if (conflictRename) {
                    availableDownloadFile(requestedFile, directory, reserve = false)
                } else requestedFile
                if (directory != null) finalFile.requireDownloadChild(directory)
                if (!outputFile.renameTo(finalFile)) {
                    throw IOException("Unable to move completed download to its destination")
                }
                finalFile
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // SocketException 也可能是普通断线，取消由 Call/Job 的实际状态判断。
        throw DownloadFileException(this, cause = e)
    } finally {
        temporaryFile?.delete()
    }
}

/**
 * 响应体使用转换器处理数据
 */
@Suppress("UNCHECKED_CAST")
@Throws(IOException::class)
inline fun <reified R> Response.convert(): R {
    try {
        return request.converter().onConvert<R>(typeTokenOf<R>(), this) as R
    } catch (e: Throwable) {
        closeOnConversionFailure(e)
        when (e) {
            is CancellationException, is NetException -> throw e
            else -> throw ConvertException(this, message = "An unexpected error occurred in the converter", cause = e)
        }
    }
}

/**
 * 响应体使用转换器处理数据
 * 本方法仅为兼容Java使用存在
 * @param type 如果存在泛型嵌套要求使用[typeTokenOf]获取, 否则泛型会被擦除导致无法解析
 */
@Suppress("UNCHECKED_CAST")
@Throws(IOException::class)
fun <R> Response.convert(type: Type): R {
    try {
        return request.converter().onConvert<R>(type, this) as R
    } catch (e: Throwable) {
        closeOnConversionFailure(e)
        when (e) {
            is CancellationException, is NetException -> throw e
            else -> throw ConvertException(this, message = "An unexpected error occurred in the converter", cause = e)
        }
    }
}

/** 最终转换失败才释放响应，保留转换器内部的 fallback 与成功 Response 的所有权。 */
@PublishedApi
internal fun Response.closeOnConversionFailure(failure: Throwable) {
    try {
        body?.close()
    } catch (closeFailure: Throwable) {
        if (closeFailure !== failure) failure.addSuppressed(closeFailure)
    }
}
