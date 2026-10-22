package dev.budgetly.core.statement

/** Montants au format français : « 1 057,48 ». */
object FrenchAmounts {
    private val pattern = Regex("""^\d{1,3}(?:\d{3})*,\d{2}$""")

    /**
     * « 1 057,48 » ou « 1057,48 » → 105748 centimes. Retourne null si ce n'est pas un montant.
     * Le séparateur de milliers éclate le nombre en plusieurs mots : l'appelant les joint
     * après les avoir sélectionnés par position (colonne des montants).
     */
    fun parseCents(text: String): Long? {
        val compact = text.replace(" ", "").replace(" ", "").replace(" ", "")
        if (!pattern.matches(compact)) return null
        return compact.replace(",", "").toLongOrNull()
    }
}
