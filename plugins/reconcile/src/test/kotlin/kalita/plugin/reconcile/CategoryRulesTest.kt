// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.reconcile

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kalita.core.BudgetItem
import kalita.core.Counterparty
import kalita.core.Dataset
import kalita.plugin.reconcile.CategoryRule.Field
import kalita.plugin.reconcile.CategoryRule.Match
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class CategoryRulesTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `fields and match kinds compare case- and space-insensitively`() {
        val contains = CategoryRule(Field.DESCRIPTION, Match.CONTAINS, "  Maxi ", 11, null)
        contains.matches("MAXI  123, BEOGRAD", null, null) shouldBe true
        contains.matches("IDEA 7, NIS", null, null) shouldBe false

        val starts = CategoryRule(Field.MERCHANT, Match.STARTS_WITH, "lidl", 11, null)
        starts.matches("whatever", "LIDL Srbija", null) shouldBe true
        // No merchant in the statement → the description's text before the first comma.
        starts.matches("Lidl 0042, Novi Sad", null, null) shouldBe true
        starts.matches("Kupovina LIDL, Novi Sad", null, null) shouldBe false

        val merchantEquals = CategoryRule(Field.MERCHANT, Match.EQUALS, "maxi 123", 11, null)
        merchantEquals.matches("MAXI 123, BEOGRAD", null, null) shouldBe true
        merchantEquals.matches("MAXI 1234, BEOGRAD", null, null) shouldBe false

        val mcc = CategoryRule(Field.MCC, Match.EQUALS, "5411", 11, null)
        mcc.matches("anything", null, "5411") shouldBe true
        mcc.matches("anything", null, null) shouldBe false
    }

    @Test
    fun `a rule with nothing to set or nothing to match never applies`() {
        CategoryRule(Field.DESCRIPTION, Match.CONTAINS, "maxi", null, null).matches("MAXI", null, null) shouldBe false
        CategoryRule(Field.DESCRIPTION, Match.CONTAINS, " ", 11, null).matches("MAXI", null, null) shouldBe false
    }

    @Test
    fun `the store round-trips in order, and adding the same pattern again moves it to the top`() {
        val store = CategoryRuleStore(dir.toFile())
        store.read().shouldBeEmpty()
        store.add(CategoryRule(Field.MERCHANT, Match.CONTAINS, "maxi", 11, null))
        store.add(CategoryRule(Field.MCC, Match.EQUALS, "5411", 44, 7))
        store.add(CategoryRule(Field.MERCHANT, Match.CONTAINS, "MAXI", 44, null))

        val rules = CategoryRuleStore(dir.toFile()).read()
        rules.map { it.pattern } shouldBe listOf("MAXI", "5411")
        rules[0].budgetItemId shouldBe 44
        rules[1].counterpartyId shouldBe 7
    }

    @Test
    fun `a malformed rule in a hand-edited file is skipped, not fatal`() {
        File(dir.toFile(), "category-rules.json").writeText(
            """{"rules":[{"field":"NOPE","match":"CONTAINS","pattern":"x","budgetItem":11},{"field":"MCC","match":"EQUALS","pattern":"5411","budgetItem":11,"counterparty":null}]}""",
        )
        CategoryRuleStore(dir.toFile()).read().map { it.pattern } shouldBe listOf("5411")
    }

    @Test
    fun `fromBankLine remembers the merchant part of the description`() {
        CategoryRule.fromBankLine("MAXI 123, BEOGRAD", null, 11, 3) shouldBe CategoryRule(Field.MERCHANT, Match.CONTAINS, "maxi 123", 11, 3)
        CategoryRule.fromBankLine("x", "Lidl Srbija", 11, null).pattern shouldBe "lidl srbija"
    }

    @Test
    fun `remembered rule falls back to description for a blank merchant`() {
        val rule = CategoryRule.fromBankLine("MAXI 123, BEOGRAD", "  ", 11, null)
        rule.matches("MAXI 123, BEOGRAD", "  ", null) shouldBe true
    }

    @Test
    fun `history falls back to description for a blank merchant`() = runTest {
        val ds = dataset(op(1, "2026-10-01", "300", item = 11, comment = "MAXI 123, BEOGRAD", from = CASH))
        HistoryCategorizer(ds).suggest("MAXI 123, NOVI SAD", "  ", null).single().budgetItemId shouldBe 11
    }

    @Test
    fun `adding a text rule replaces an equivalent whitespace normalized pattern`() {
        val store = CategoryRuleStore(dir.toFile())
        store.add(CategoryRule(Field.MERCHANT, Match.CONTAINS, "maxi  shop", 11, null))
        store.add(CategoryRule(Field.MERCHANT, Match.CONTAINS, " MAXI shop ", 44, null))
        store.read().map { it.budgetItemId } shouldBe listOf(44L)
    }

    @Test
    fun `malformed rule values do not become partial or unintended rules`() {
        File(dir.toFile(), "category-rules.json").writeText(
            """{"rules":[{"field":"MCC","match":"EQUALS","pattern":"5411","budgetItem":"bad","counterparty":7},{"field":"MCC","match":"EQUALS","pattern":null,"budgetItem":11},{"field":"MCC","match":"EQUALS","pattern":"5411","budgetItem":11}]}""",
        )
        CategoryRuleStore(dir.toFile()).read() shouldBe listOf(CategoryRule(Field.MCC, Match.EQUALS, "5411", 11, null))
    }

    @Test
    fun `the first matching rule wins and rules pointing at deleted items are skipped`() = runTest {
        val base = dataset()
        val ds = Dataset(
            operationTypes = base.operationTypes,
            budgetItems = base.budgetItems + BudgetItem(45, 11, "Старая", 2, deleted = true),
            counterparties = listOf(Counterparty(7, "Магазин")),
        )
        val rules = listOf(
            CategoryRule(Field.DESCRIPTION, Match.CONTAINS, "maxi", 45, null), // deleted item
            CategoryRule(Field.DESCRIPTION, Match.CONTAINS, "maxi", 44, 7),
            CategoryRule(Field.DESCRIPTION, Match.CONTAINS, "maxi", 11, null),
        )
        val s = RuleCategorizer(rules, ds).suggest("MAXI 123, BEOGRAD", null, null).single()
        s.budgetItemId shouldBe 44
        s.counterpartyId shouldBe 7
        s.confidence shouldBe 1.0f
        RuleCategorizer(rules, ds).suggest("IDEA", null, null).shouldBeEmpty()
    }

    @Test
    fun `rules with missing or deleted counterparties fall through`() = runTest {
        val ds = Dataset(counterparties = listOf(Counterparty(7, "Old", deleted = true), Counterparty(8, "Live")))
        val rules = listOf(
            CategoryRule(Field.MCC, Match.EQUALS, "5411", null, 9),
            CategoryRule(Field.MCC, Match.EQUALS, "5411", null, 7),
            CategoryRule(Field.MCC, Match.EQUALS, "5411", null, 8),
        )
        RuleCategorizer(rules, ds).suggest("", null, "5411").single().counterpartyId shouldBe 8
    }

    @Test
    fun `in the chain a user rule beats the journal's history for a new bank line`() = runTest {
        // History says MAXI goes to «Продукты» (11); the user's rule says «Еда» (44).
        val ds = dataset(op(1, "2026-10-01", "300", item = 11, comment = "MAXI 123, BEOGRAD", from = CASH))
        val chain = CategorizerChain(listOf(RuleCategorizer(listOf(CategoryRule(Field.MERCHANT, Match.CONTAINS, "maxi", 44, null)), ds), HistoryCategorizer(ds)))

        val report = Reconciler(ds, categorizer = chain).reconcile(listOf(tx("a", "2026-11-09", "-999.00")), CARD, RSD, emptySet(), emptySet())

        report.proposals.single().shouldBeInstanceOf<Proposal.New>().draft.budgetItemId shouldBe 44
        val historyOnly = Reconciler(ds, categorizer = HistoryCategorizer(ds)).reconcile(listOf(tx("a", "2026-11-09", "-999.00")), CARD, RSD, emptySet(), emptySet())
        historyOnly.proposals.single().shouldBeInstanceOf<Proposal.New>().draft.budgetItemId shouldBe 11
    }
}
