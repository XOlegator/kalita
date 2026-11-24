// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import kalita.core.BudgetItem
import kalita.core.Dataset
import kalita.core.OperationKind
import java.text.Normalizer
import java.util.Locale

/** An independent, deliberately small vocabulary; a hint never modifies the dataset. */
internal object SerbianReceiptDictionary {
    private val vocabulary = mapOf(
        "mleko" to "молоко", "hleb" to "хлеб", "hljeb" to "хлеб",
        "sir" to "сыр", "jaja" to "яйца", "brašno" to "мука", "šećer" to "сахар",
        "pirinač" to "рис", "krompir" to "картофель",
        "jabuka" to "яблоки", "jabuke" to "яблоки", "banana" to "бананы", "banane" to "бананы",
        "piletina" to "курица", "svinjetina" to "свинина", "junetina" to "говядина",
        "deterdžent" to "бытовая химия", "omekšivač" to "бытовая химия",
        "izbeljivač" to "бытовая химия", "sredstvo za čišćenje" to "бытовая химия",
    )
    private val words = Regex("[\\p{L}\\p{M}\\p{N}]+")
    private val cyrillic = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'ђ' to "đ", 'е' to "e",
        'ж' to "ž", 'з' to "z", 'и' to "i", 'ј' to "j", 'к' to "k", 'л' to "l", 'љ' to "lj",
        'м' to "m", 'н' to "n", 'њ' to "nj", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s",
        'т' to "t", 'ћ' to "ć", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "č",
        'џ' to "dž", 'ш' to "š",
    )

    /** Returns the sole matching active expense item, or null if either meaning or item is ambiguous. */
    fun suggest(text: String, dataset: Dataset): BudgetItem? {
        val tokens = words.findAll(transliterate(text)).map { it.value }.toList()
        val meanings = vocabulary.filterKeys { phrase ->
            val parts = phrase.split(' ')
            tokens.windowed(parts.size).any { it == parts }
        }.values.toSet()
        val meaning = meanings.singleOrNull() ?: return null
        val expenseRoots = dataset.operationTypes.filter { !it.deleted && it.kind == OperationKind.Expense }
            .mapNotNull { it.budgetRootId }.toSet()
        val otherRoots = dataset.operationTypes.filter { !it.deleted && it.kind != OperationKind.Expense }
            .mapNotNull { it.budgetRootId }.toSet()
        if (expenseRoots.isEmpty()) return null

        fun eligible(item: BudgetItem): Boolean {
            val visited = mutableSetOf<Long>()
            var current: BudgetItem? = item
            var expense = false
            var other = false
            while (current != null) {
                if (current.deleted || !visited.add(current.id)) return false
                if (current.id in expenseRoots) expense = true
                if (current.id in otherRoots) other = true
                val parent = current.parentId ?: break
                current = dataset.budgetItem(parent) ?: return false
            }
            return expense && !other
        }

        return dataset.budgetItems.filter {
            it.name?.trim()?.lowercase(Locale.ROOT) == meaning && eligible(it)
        }.singleOrNull()
    }

    private fun transliterate(text: String): String = buildString {
        for (char in Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)) {
            append(cyrillic[char] ?: char.toString())
        }
    }
}