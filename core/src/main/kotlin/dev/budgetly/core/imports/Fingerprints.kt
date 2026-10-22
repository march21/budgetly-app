package dev.budgetly.core.imports

import dev.budgetly.core.statement.ParsedTransaction
import java.security.MessageDigest
import java.text.Normalizer
import java.util.HexFormat

object Fingerprints {

    fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /**
     * Empreinte de dédoublonnage : compte + date + montant + libellé normalisé + rang parmi les opérations
     * identiques du même jour. Deux relevés qui se chevauchent produisent la même empreinte pour la même opération.
     */
    fun of(iban: String, transactions: List<ParsedTransaction>): List<String> {
        val seen = mutableMapOf<String, Int>()
        return transactions.map { t ->
            val base = "$iban|${t.bookingDate}|${t.amountCents}|${normalizeLabel(t.label)}"
            val rank = seen.merge(base, 1, Int::plus)!!
            sha256("$base|$rank".toByteArray())
        }
    }

    private fun normalizeLabel(label: String) =
        Normalizer.normalize(label, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            .uppercase().replace(Regex("\\s+"), " ").trim()
}

fun maskIban(iban: String) = "${iban.take(4)} •••• ${iban.takeLast(4)}"
