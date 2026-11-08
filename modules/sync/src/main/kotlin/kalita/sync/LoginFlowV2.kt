// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/** Credentials obtained from Login Flow v2 — never the account password itself (§10.8). */
data class NextcloudCredentials(val server: String, val loginName: String, val appPassword: String)

/** One started Login Flow v2 handshake (dev_plan.md §10.8/FR-S1): open [loginUrl] in a browser, then poll with [LoginFlowV2.poll]. */
data class LoginFlowSession(val loginUrl: String, internal val pollEndpoint: String, internal val pollToken: String)

/**
 * Nextcloud Login Flow v2: no account password ever reaches Kalita. [start] gets a one-time
 * [LoginFlowSession.loginUrl] to open in a browser, where the user logs in on Nextcloud's own page
 * and grants access; [poll] returns an app password once they do. Field names/shape confirmed live
 * against a real server, not from memory of the documentation: `POST .../index.php/login/v2` →
 * `{"poll": {"token", "endpoint"}, "login": "<url>"}`; polling `poll.endpoint` with `token` answers
 * `404` while the user hasn't confirmed yet, then `200` with `{"server", "loginName", "appPassword"}`.
 */
class LoginFlowV2(private val http: OkHttpClient = OkHttpClient()) {
    fun start(server: String): LoginFlowSession {
        val req = Request.Builder().url("${server.trimEnd('/')}/index.php/login/v2").post(FormBody.Builder().build()).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw NextcloudHttpException(resp.code, resp.body.string())
            val json = Json.parseToJsonElement(resp.body.string()).jsonObject
            val poll = json.getValue("poll").jsonObject
            return LoginFlowSession(
                loginUrl = json.getValue("login").jsonPrimitive.content,
                pollEndpoint = poll.getValue("endpoint").jsonPrimitive.content,
                pollToken = poll.getValue("token").jsonPrimitive.content,
            )
        }
    }

    /** `null` while the user hasn't confirmed yet (server answers 404) — call again on a timer until it isn't. */
    fun poll(session: LoginFlowSession): NextcloudCredentials? {
        val body = FormBody.Builder().add("token", session.pollToken).build()
        val req = Request.Builder().url(session.pollEndpoint).post(body).build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) throw NextcloudHttpException(resp.code, resp.body.string())
            val json = Json.parseToJsonElement(resp.body.string()).jsonObject
            return NextcloudCredentials(
                server = json.getValue("server").jsonPrimitive.content,
                loginName = json.getValue("loginName").jsonPrimitive.content,
                appPassword = json.getValue("appPassword").jsonPrimitive.content,
            )
        }
    }
}
