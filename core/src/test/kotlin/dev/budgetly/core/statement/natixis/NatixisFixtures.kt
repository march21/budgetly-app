package dev.budgetly.core.statement.natixis

import dev.budgetly.core.statement.PdfFixtureWriter
import dev.budgetly.core.statement.PdfFixtureWriter.Companion.amount
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Avis d'opération Natixis Interépargne fictif. Mise en page mesurée sur un avis réel (page 1 : versement,
 * page 2 : situation des avoirs) ; entreprise, personne, placements, parts et montants sont inventés.
 */
object NatixisFixtures {
    const val COMPANY = "777001"

    data class Row(val nameLines: List<String>, val availableOn: String, val price: String, val quantity: String) {
        val valueCents: Long
            get() = BigDecimal(price.replace(",", ".")).multiply(BigDecimal(quantity.replace(",", ".")))
                .movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
    }

    val rows = listOf(
        Row(listOf("FONDS EXEMPLE ACTIONS", "MONDE I"), "01/06/2025", "35,12345", "4,0000"),
        Row(listOf("FONDS EXEMPLE ACTIONS", "MONDE I"), "01/06/2029", "35,12345", "30,1234"),
        Row(listOf("FONDS EXEMPLE ACTIONS", "MONDE I"), "01/06/2030", "35,12345", "10,5000"),
        Row(listOf("FONDS EXEMPLE", "MONETAIRE"), "01/06/2031", "102,01000", "2,0000"),
    )

    val total get() = rows.sumOf { it.valueCents }

    fun notice(totalAdjustmentCents: Long = 0): ByteArray {
        val w = PdfFixtureWriter().page()
        w.text(44f, 100.6f, 13f, "Avis d'opération", isBold = true)
        w.text(44f, 113.3f, 12f, "Epargne Salariale", isBold = true)
        w.text(326f, 120.6f, 5f, "8403 000$COMPANY PEE")
        w.text(44f, 197f, 9f, "UES EXEMPLE", isBold = true)
        w.text(326f, 197f, 9f, "M. JEAN TEST", isBold = true)
        w.text(47f, 276.2f, 10f, "Le 7 octobre 2026")
        w.text(46f, 362.4f, 12f, "Récapitulatif de vos avoirs", isBold = true)
        w.text(46f, 379.2f, 12f, "au 07/10/2026", isBold = true)
        w.text(380f, 360.2f, 7f, "AVOIRS DISPONIBLES", isBold = true)
        w.right(547f, 360.2f, 7f, amount(rows.first().valueCents), isBold = true)
        w.text(380f, 394.2f, 7f, "MONTANT TOTAL*", isBold = true)
        w.right(547f, 394.2f, 7f, amount(total), isBold = true)
        w.text(51f, 426.5f, 12f, "Versement dans votre PEE - Plan d'Épargne Entreprise au titre de l'année 2026", isBold = true)
        w.text(59f, 509.7f, 7f, "FONDS EXEMPLE ACTIONS MONDE I", isBold = true)
        w.right(238f, 509.7f, 7f, "50,00", isBold = true)
        w.text(249f, 509.7f, 7f, "06/10/2026", isBold = true)
        w.text(59f, 525.5f, 8f, "TOTAL", isBold = true)
        w.right(238f, 525.5f, 8f, "50,00", isBold = true)
        w.text(70f, 700f, 8f, "VOS IDENTIFIANTS N° d'entreprise : $COMPANY")
        w.text(70f, 720f, 8f, "NATIXIS INTERÉPARGNE - Service 8403 - 14029 CAEN Cedex 9")

        w.page()
        w.text(25f, 87.8f, 11f, "Situation de vos avoirs au 07 octobre 2026", isBold = true)
        w.text(25f, 138f, 11f, "Par produit, par date de disponibilité et placement", isBold = true)
        w.text(228f, 154.5f, 8f, "Date de", isBold = true)
        w.text(290f, 154.5f, 8f, "Valeur", isBold = true)
        w.text(348f, 154.5f, 8f, "Nombre", isBold = true)
        w.text(411f, 154.5f, 8f, "Montant", isBold = true)
        w.text(28f, 159.3f, 8f, "Placement", isBold = true)
        w.text(220f, 163.9f, 8f, "disponibilité", isBold = true)
        w.text(415f, 163.9f, 8f, "total *", isBold = true)
        w.text(28f, 184.5f, 10f, "PEE - Plan d'Épargne Entreprise", isBold = true)
        rows.forEachIndexed { i, row ->
            val y = 202.5f + i * 21.6f
            w.text(28f, y - 4.6f, 8f, row.nameLines[0])
            w.text(222f, y, 8f, row.availableOn)
            w.right(327f, y, 8f, row.price)
            w.right(390f, y, 8f, row.quantity)
            w.right(454f, y, 8f, amount(row.valueCents))
            w.text(477f, y, 8f, "06/10/2026")
            w.text(28f, y + 4.8f, 8f, row.nameLines[1])
        }
        val totalY = 202.5f + rows.size * 21.6f
        w.text(211f, totalY, 8f, "TOTAL*", isBold = true)
        w.right(453f, totalY, 8f, amount(total + totalAdjustmentCents), isBold = true)
        w.text(456f, totalY, 8f, "€", isBold = true)
        w.text(354f, totalY + 13.7f, 6f, "* Avant frais et hors prélèvements sociaux sur plus values latentes")
        w.text(51f, 771f, 6f, "Natixis Interepargne : informations sur vos données personnelles")
        return w.build()
    }
}
