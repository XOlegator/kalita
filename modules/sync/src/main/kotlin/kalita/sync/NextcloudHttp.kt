// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.sync

import okhttp3.OkHttpClient

/**
 * OkHttp's own default `User-Agent` ("okhttp/4.12.0") is what a Nextcloud server's Login Flow v2
 * consent page ("Вы предоставляете «…» доступ...") and "Security → Devices" list show the user
 * for this app — found live, on a real device: the user had no way to tell this was Kalita asking,
 * not some unrelated app. [defaultHttpClient] sets this on every request from [NextcloudClient]
 * and [LoginFlowV2] alike via one shared [okhttp3.Interceptor], rather than each request builder
 * setting its own header.
 */
internal fun defaultHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", "Kalita").build()) }
        .build()
