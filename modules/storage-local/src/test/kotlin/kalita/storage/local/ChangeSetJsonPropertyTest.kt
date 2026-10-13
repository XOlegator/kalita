// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.local

import io.kotest.matchers.shouldBe
import kalita.storage.api.TempKey
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import java.math.BigDecimal
import java.time.LocalDateTime

/** The history journal must give back exactly the values it was given — any text, number or date. */
class ChangeSetJsonPropertyTest {
    @Provide
    fun values(): Arbitrary<Any?> = Arbitraries.oneOf(
        Arbitraries.just<Any?>(null),
        Arbitraries.longs().map<Any?> { it },
        Arbitraries.of(true, false).map<Any?> { it },
        Arbitraries.strings().all().ofMaxLength(40).map<Any?> { it },
        Arbitraries.doubles().map<Any?> { it },
        Arbitraries.bigDecimals().ofScale(4).between(BigDecimal("-100000000"), BigDecimal("100000000")).map<Any?> { it },
        Arbitraries.longs().between(0, 4_000_000_000L).map<Any?> { LocalDateTime.of(1990, 1, 1, 0, 0, 0).plusSeconds(it) },
        Arbitraries.strings().ofMinLength(1).ofMaxLength(10).map<Any?> { TempKey(it) },
    )

    @Property(tries = 500)
    fun `value survives encode and decode unchanged`(@ForAll("values") value: Any?) {
        val decoded = ChangeSetJson.decodeValue(ChangeSetJson.encodeValue(value))
        if (value is BigDecimal) (decoded as BigDecimal).compareTo(value) shouldBe 0 else decoded shouldBe value
    }
}
