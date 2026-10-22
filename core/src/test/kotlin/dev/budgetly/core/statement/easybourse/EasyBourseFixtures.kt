package dev.budgetly.core.statement.easybourse

import dev.budgetly.core.statement.PdfFixtureWriter
import dev.budgetly.core.statement.PdfFixtureWriter.Companion.amount

/**
 * Relevés EasyBourse fictifs (PEA et assurance vie EasyVie). Mise en page mesurée sur des relevés réels ;
 * supports, ISIN (au bon format, inventés), quantités et montants sont fictifs.
 */
object EasyBourseFixtures {

    data class PeaLine(
        val nameLines: List<String>,
        val isin: String,
        val quantity: String,
        val price: String,
        val valueCents: Long,
        val weight: String,
        val costBasis: String,
        val unrealizedCents: Long,
        /** Nom sur la même ligne que l'ISIN (sinon au-dessus et en dessous). */
        val nameOnIsinLine: Boolean = false,
    )

    const val PEA_NUMBER = "20000000001"

    val peaSections = listOf(
        "Actions France" to listOf(
            PeaLine(listOf("SOCIETE EXEMPLE"), "FR0000000011", "120", "8,50", 102_000, "40,00", "7,9000", 7_200, nameOnIsinLine = true),
        ),
        "OPCVM" to listOf(
            PeaLine(listOf("FONDS EXEMPLE", "ACTIONS EURO"), "FR0000000029", "5,79845", "68,28", 39_592, "15,53", "48,8639", 11_258),
        ),
        "Autres" to listOf(
            PeaLine(listOf("ETF EXEMPLE MONDE", "UCITS ETF"), "LU0000000035", "22", "41,58", 91_465, "35,87", "26,0300", 34_199),
        ),
    )
    const val PEA_CASH = 21_943L
    const val PEA_CONTRIBUTIONS = 220_000L

    val peaTotal get() = peaSections.flatMap { it.second }.sumOf { it.valueCents } + PEA_CASH
    val peaUnrealized get() = peaSections.flatMap { it.second }.sumOf { it.unrealizedCents }

    fun pea(totalAdjustmentCents: Long = 0): ByteArray {
        val w = PdfFixtureWriter().page()
        w.text(377f, 46f, 12f, "ÉVALUATION DE PORTEFEUILLE")
        w.text(467f, 81f, 10f, "Au 30 septembre 2026")
        w.text(340f, 156f, 10f, "MME CLAIRE DUPONT")
        w.text(34f, 251f, 10f, "VOTRE COMPTE PEA N° $PEA_NUMBER")
        w.text(34f, 284f, 9f, "Date d'ouverture de votre compte")
        w.text(304f, 284f, 9f, "03-06-2022")
        w.text(34f, 300f, 9f, "Cumul de vos versements (**)")
        w.right(341f, 300f, 9f, amount(PEA_CONTRIBUTIONS))
        w.text(344f, 300f, 9f, "€")
        w.text(256f, 337f, 9f, "Cours (*)")
        w.text(368f, 337f, 9f, "Valorisation (*)")
        w.text(71f, 342f, 9f, "Valeur")
        w.text(146f, 342f, 9f, "Code Valeur")
        w.text(209f, 342f, 9f, "Quantité")

        var y = 370f
        for ((section, rows) in peaSections) {
            w.text(37f, y, 9f, "> $section")
            y += 16f
            for (row in rows) {
                if (row.nameOnIsinLine) {
                    w.text(34f, y, 8f, row.nameLines.joinToString(" "))
                } else {
                    w.text(34f, y - 4.5f, 8f, row.nameLines[0])
                    row.nameLines.drop(1).forEachIndexed { i, part -> w.text(34f, y + 4.7f * (i + 1), 8f, part) }
                }
                w.text(143f, y, 8f, row.isin)
                w.right(248f, y, 8f, row.quantity)
                w.right(296f, y, 8f, row.price)
                w.text(309f, y, 8f, "30/09/26")
                w.right(415f, y, 8f, amount(row.valueCents))
                w.right(449f, y, 8f, row.weight)
                w.right(501f, y, 8f, row.costBasis)
                w.right(564f, y, 8f, amount(row.unrealizedCents))
                y += 19f
            }
        }
        w.text(37f, y, 9f, "> Liquidités")
        w.text(34f, y + 16f, 8f, "Espèces EUR")
        w.right(415f, y + 16f, 8f, amount(PEA_CASH))
        w.right(449f, y + 16f, 8f, "8,60")
        w.text(34f, y + 34f, 9f, "Total portefeuille au 30 septembre 2026")
        w.right(415f, y + 34f, 9f, amount(peaTotal + totalAdjustmentCents))
        w.right(449f, y + 34f, 9f, "100,00")
        w.right(564f, y + 34f, 9f, amount(peaUnrealized))
        w.text(47f, 752f, 6.5f, "Votre service Client EasyBourse est à votre disposition")
        return w.build()
    }

    data class Unit(val isin: String, val name: String, val quantity: String, val price: String, val valueCents: Long, val performance: String)

    const val AV_REFERENCE = "B0E 000000 01"
    const val AV_EURO_CENTS = 62_493L
    val avUnits = listOf(
        Unit("IE0000000041", "ETF EXEMPLE S P 500 UCITS", "0,93425", "171,32", 16_007, "27,03"),
        Unit("FR0000000052", "FONDS EXEMPLE EUROPE", "0,08833", "364,27", 3_217, "-4,68"),
        Unit("FR0000000060", "FONDS EXEMPLE MONETAIRE", "0,00107", "44 710,55", 4_784, "5,21"),
    )
    val avUnitsTotal get() = avUnits.sumOf { it.valueCents }
    val avTotal get() = AV_EURO_CENTS + avUnitsTotal

    fun lifeInsurance(): ByteArray {
        val w = PdfFixtureWriter().page()
        w.text(245f, 78f, 18f, "EASYVIE", isBold = true)
        w.text(43f, 193f, 10f, "Référence à rappeler :")
        w.text(312f, 203f, 9f, "MME CLAIRE DUPONT")
        w.text(43f, 207f, 10f, AV_REFERENCE)
        w.text(75f, 310f, 14f, "VOTRE BULLETIN DE SITUATION ANNUEL AU 31 DÉCEMBRE 2025", isBold = true)
        w.text(42f, 675f, 10f, "Votre capital au 31/12/2025 :", isBold = true)
        w.right(539f, 675f, 10f, amount(avTotal), isBold = true)
        w.text(541f, 675f, 10f, "€", isBold = true)

        w.page()
        w.text(42f, 46f, 10f, "support(s) en euros. Il tient compte également des frais forfaitaires.")
        w.text(42f, 126f, 10f, "Vos versements de cotisations (nets de frais sur versements) au cours de l'année 2025 :", isBold = true)
        w.right(539f, 126f, 10f, "600,00")
        w.text(228f, 479f, 12f, "SUPPORT(S) EN EUROS", isBold = true)
        w.text(46f, 520f, 9f, "«GESTION LIBRE»")
        w.right(321f, 524f, 9f, "18,11")
        w.right(511f, 524f, 9f, amount(AV_EURO_CENTS))
        w.text(513f, 524f, 9f, "€")
        w.text(46f, 530f, 8f, "EASYVIE Euros")
        w.text(42f, 714f, 8f, "EASYVIE Euros 3,05% 0,75% 1,15% 3,45%")
        w.right(578f, 827f, 8f, "2")

        w.page()
        w.text(190f, 593f, 12f, "SUPPORT(S) EN UNITÉS DE COMPTE", isBold = true)
        w.text(226f, 645f, 8f, "Nombre d'unités de compte")
        w.text(45f, 681f, 9f, "Fonds Actions", isBold = true)
        var y = 699f
        for ((index, unit) in avUnits.withIndex()) {
            if (index == 2) {
                // Changement de page au milieu de la liste, comme sur un vrai bulletin.
                w.page()
                w.text(226f, 43f, 8f, "Nombre d'unités de compte")
                w.text(45f, 506f - 400f, 9f, "Fonds Monétaires", isBold = true)
                y = 124f
            }
            w.text(45f, y, 9f, unit.isin, isBold = true)
            w.text(45f, y + 10f, 8f, unit.name)
            w.right(300f, y + 10f, 8f, unit.quantity)
            w.right(380f, y + 10f, 8f, unit.price)
            w.text(382f, y + 10f, 8f, "€")
            w.right(462f, y + 10f, 8f, amount(unit.valueCents))
            w.text(464f, y + 10f, 8f, "€")
            w.right(534f, y + 10f, 8f, unit.performance.replace("-", "- "))
            y += 36f
        }
        w.text(114f, y, 9f, "TOTAL", isBold = true)
        w.right(460f, y, 9f, amount(avUnitsTotal), isBold = true)
        w.text(462f, y, 9f, "€", isBold = true)
        // Annexe : les ISIN réapparaissent avec d'autres chiffres, ils doivent être ignorés.
        w.text(46f, y + 150f, 8f, "${avUnits[0].isin} - ${avUnits[0].name} - ANNEXE 2026 1,23", isBold = true)

        w.page()
        w.text(49f, 255f, 10f, "Montant des versements bruts effectués sur votre contrat")
        w.text(58f, 266f, 10f, "avant le 27/09/2017, déduction faite des versements", isBold = true)
        w.text(120f, 278f, 10f, "rachetés au 31/12/2024")
        w.right(175f, 296f, 10f, "0,00")
        w.text(178f, 296f, 10f, "€")
        w.right(440f, 296f, 10f, "1 345,00")
        w.text(443f, 296f, 10f, "€")
        return w.build()
    }
}
