// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.sync

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

// Field names/shape (poll.token, poll.endpoint, login, then server/loginName/appPassword) were
// confirmed live against a real Nextcloud server (dev_plan.md Этап 9 срез 1), not assumed from
// memory of the documentation — FakeNextcloudServer reproduces exactly that shape.
class LoginFlowV2Test {
    private lateinit var server: FakeNextcloudServer
    private lateinit var flow: LoginFlowV2

    @BeforeEach
    fun setUp() {
        server = FakeNextcloudServer()
        flow = LoginFlowV2()
    }

    @AfterEach
    fun tearDown() = server.close()

    @Test
    fun `start returns a login URL and poll endpoint, poll stays null until the user confirms`() {
        val session = flow.start(server.baseUrl)

        session.loginUrl shouldStartWith server.baseUrl
        flow.poll(session) shouldBe null // the user hasn't finished the browser side yet

        val credentials = NextcloudCredentials(server.baseUrl, server.loginName, "generated-app-password")
        server.confirmLogin(session.pollToken, credentials)

        flow.poll(session) shouldBe credentials
    }

    @Test
    fun `each login flow gets its own token`() {
        val first = flow.start(server.baseUrl)
        val second = flow.start(server.baseUrl)

        first.pollToken shouldNotBe second.pollToken
    }

    @Test
    fun `start identifies this app to the server, not OkHttp's own default User-Agent`() {
        flow.start(server.baseUrl)

        server.lastLoginStartUserAgent shouldBe "Kalita"
    }
}
