/*
 * MIT License
 * Copyright (c) 2026 rocker027 (maintenance fork)
 */
package com.drake.net.okhttp

import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.Request

/**
 * Net 的 Builder 與公開 OkHttp setter 共用同一份 metadata，保留呼叫順序。
 * 不讀取 OkHttp 的 internal 欄位，也不把請求交由全域側車持有。
 */
internal class NetRequestBuilder : Request.Builder() {

    var netHeaders = Headers.Builder()
        private set
    val netTags = linkedMapOf<Class<*>, Any?>()
    private val appliedTagTypes = mutableSetOf<Class<*>>()

    override fun header(name: String, value: String) = apply {
        netHeaders.set(name, value)
    }

    override fun addHeader(name: String, value: String) = apply {
        netHeaders.add(name, value)
    }

    override fun removeHeader(name: String) = apply {
        netHeaders.removeAll(name)
    }

    override fun headers(headers: Headers) = apply {
        netHeaders = headers.newBuilder()
    }

    override fun cacheControl(cacheControl: CacheControl) = apply {
        val value = cacheControl.toString()
        if (value.isEmpty()) removeHeader("Cache-Control") else header("Cache-Control", value)
    }

    override fun tag(tag: Any?) = apply {
        tag(Any::class.java, tag)
    }

    override fun <T> tag(type: Class<in T>, tag: T?) = apply {
        if (tag == null) netTags.remove(type) else netTags[type] = type.cast(tag)
    }

    fun flushMetadata() {
        super.headers(netHeaders.build())
        for (type in appliedTagTypes + netTags.keys) {
            @Suppress("UNCHECKED_CAST")
            super.tag(type as Class<Any>, netTags[type])
        }
        appliedTagTypes.clear()
        appliedTagTypes.addAll(netTags.keys)
    }

    override fun build(): Request {
        flushMetadata()
        return super.build()
    }
}
