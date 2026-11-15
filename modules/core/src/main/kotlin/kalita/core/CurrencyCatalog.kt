// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

/**
 * ISO 4217 codes of the currencies a household is likely to meet, with the Russian names people
 * give them in the «Валюты» dictionary. Used to fill `Currency.CB_ID` when a currency is added
 * without a code — the code is what a rate source identifies the currency by (FR-R12), so a
 * currency without one silently gets no rates.
 *
 * Deliberately not exhaustive, and never authoritative: a name that is not here simply yields no
 * suggestion, and a three-letter Latin name is taken as a code of its own.
 */
object CurrencyCatalog {
    private val namesByCode: Map<String, List<String>> = mapOf(
        "RUB" to listOf("рубль", "российский рубль", "рубли"),
        "USD" to listOf("доллар", "доллар сша", "доллары"),
        "EUR" to listOf("евро"),
        "RSD" to listOf("сербский динар", "динар"),
        "GBP" to listOf("фунт стерлингов", "фунт", "британский фунт"),
        "CHF" to listOf("швейцарский франк", "франк"),
        "CNY" to listOf("юань", "китайский юань"),
        "JPY" to listOf("иена", "йена", "японская иена", "японская йена"),
        "UAH" to listOf("гривна", "украинская гривна"),
        "BYN" to listOf("белорусский рубль"),
        "KZT" to listOf("тенге", "казахстанский тенге"),
        "TRY" to listOf("турецкая лира", "лира"),
        "PLN" to listOf("злотый", "польский злотый"),
        "CZK" to listOf("чешская крона"),
        "SEK" to listOf("шведская крона"),
        "NOK" to listOf("норвежская крона"),
        "DKK" to listOf("датская крона"),
        "HUF" to listOf("форинт", "венгерский форинт"),
        "RON" to listOf("румынский лей"),
        "MDL" to listOf("молдавский лей"),
        "BGN" to listOf("лев", "болгарский лев"),
        "GEL" to listOf("лари", "грузинский лари"),
        "AMD" to listOf("драм", "армянский драм"),
        "AZN" to listOf("манат", "азербайджанский манат"),
        "UZS" to listOf("сум", "узбекский сум"),
        "KGS" to listOf("сом", "киргизский сом"),
        "AED" to listOf("дирхам", "дирхам оаэ"),
        "THB" to listOf("бат", "тайский бат"),
        "VND" to listOf("донг", "вьетнамский донг"),
        "INR" to listOf("индийская рупия", "рупия"),
        "KRW" to listOf("вона", "южнокорейская вона"),
        "CAD" to listOf("канадский доллар"),
        "AUD" to listOf("австралийский доллар"),
        "ILS" to listOf("шекель", "израильский шекель"),
        "EGP" to listOf("египетский фунт"),
        "BAM" to listOf("конвертируемая марка", "боснийская марка"),
        "MKD" to listOf("македонский денар", "денар"),
    )

    private val codeByName: Map<String, String> =
        namesByCode.flatMap { (code, names) -> names.map { it to code } }.toMap()

    private val ISO = Regex("[A-Za-z]{3}")

    private fun normalize(name: String): String = name.trim().lowercase().replace('ё', 'е').replace(Regex("\\s+"), " ")

    /** The ISO code a currency called [name] most likely has, `null` when the name says nothing. */
    fun codeFor(name: String?): String? {
        val text = name?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (ISO.matches(text)) return text.uppercase()
        return codeByName[normalize(text)]
    }

    /** [cbId] as entered, or the code suggested by [name] when it is blank. */
    fun codeOrSuggestion(name: String?, cbId: String?): String? = cbId?.trim()?.takeIf { it.isNotEmpty() } ?: codeFor(name)
}
