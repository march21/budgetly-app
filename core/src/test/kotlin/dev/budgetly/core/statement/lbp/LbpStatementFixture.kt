package dev.budgetly.core.statement.lbp

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Relevé Banque Postale fictif. Les coordonnées, tailles et pièges (milliers éclatés, date d'achat
 * dans le libellé, détails sur plusieurs lignes, page « (suite) », page épargne) reproduisent un relevé
 * réel ; l'identité, les IBAN et les montants sont inventés.
 */
object LbpStatementFixture {

    const val CHECKING_IBAN = "FR1420041000011234567A02012"
    const val LIVRET_A_IBAN = "FR3810011000207111111A90123"
    const val LDDS_IBAN = "FR5510011000207222222J21456"

    data class Op(val dayMonth: String, val label: String, val cents: Long, val details: List<String> = emptyList())

    data class Account(
        val title: String,
        val iban: String,
        val openingCents: Long,
        val ops: List<Op>,
        val closingAdjustmentCents: Long = 0,
    ) {
        val closingCents get() = openingCents + ops.sumOf { it.cents } + closingAdjustmentCents
    }

    data class Spec(
        val openingDate: LocalDate,
        val closingDate: LocalDate,
        val checking: Account,
        val savings: List<Account>,
        /** Nombre d'opérations du CCP sur la première page, le reste part en page « (suite) ». */
        val firstPageOps: Int = 3,
    )

    /** Relevé de septembre-octobre 2026 : CCP sur deux pages, Livret A et LDDS (sans opération). */
    fun standard(closingAdjustmentCents: Long = 0) = Spec(
        openingDate = LocalDate.of(2026, 9, 9),
        closingDate = LocalDate.of(2026, 10, 9),
        checking = Account(
            title = "Compte Courant Postal n° 12 345 67A 020",
            iban = CHECKING_IBAN,
            openingCents = 125_000,
            closingAdjustmentCents = closingAdjustmentCents,
            ops = listOf(
                Op("10/09", "VIREMENT DE MME DUPONT CLAIRE", 30_000, listOf("DEFAULT REFERENCE : 0000000000000001")),
                Op("10/09", "ACHAT CB BOULANGERIE DU COIN 09.09.26", -2_150, listOf("CARTE NUMERO 999")),
                Op(
                    "11/09", "PRELEVEMENT DE FOURNISSEUR ENERGIE", -8_900,
                    listOf("REF : ABC123 Numero de client : 0000000", "IDENT : FR00ZZZ000000 MANDAT : M0001"),
                ),
                Op("15/09", "VIREMENT DE SOCIETE EXEMPLE", 418_702, listOf("SALAIRE SEPTEMBRE")),
                Op("30/09", "ACHAT CB GRAND MAGASIN 29.09.26", -105_748, listOf("CARTE NUMERO 999")),
                Op("06/10", "VIREMENT PERMANENT POUR", -20_000, listOf("MME DUPONT CLAIRE COMPTE $LIVRET_A_IBAN")),
                Op("06/10", "COTISATION TRIMESTRIELLE", -4_995, listOf("DE VOTRE FORMULE DE COMPTE")),
                Op("06/10", "VERSEMENT CARTE 06/10/26 12H31", 65_000, listOf("VERSEMENT DAB EXEMPLEVILLE")),
            ),
        ),
        savings = listOf(
            Account("Livret Développement Durable et Solidaire n° 722 2222222 J", LDDS_IBAN, 993_206, emptyList()),
            Account(
                title = "Livret A n° 711 1111111 A",
                iban = LIVRET_A_IBAN,
                openingCents = 279_597,
                ops = listOf(
                    Op("10/09", "VIREMENT POUR", -30_000, listOf("M DUPONT CLAIRE COMPTE $CHECKING_IBAN")),
                    Op("06/10", "VIREMENT DE MME DUPONT CLAIRE", 20_000, listOf("DATE DE VALEUR : 06 10 2026")),
                ),
            ),
        ),
    )

    /** Relevé à cheval sur deux années : décembre 2026 → janvier 2027. */
    fun yearBoundary() = Spec(
        openingDate = LocalDate.of(2026, 12, 9),
        closingDate = LocalDate.of(2027, 1, 9),
        checking = Account(
            title = "Compte Courant Postal n° 12 345 67A 020",
            iban = CHECKING_IBAN,
            openingCents = 50_000,
            ops = listOf(
                Op("15/12", "ACHAT CB LIBRAIRIE 14.12.26", -3_500, listOf("CARTE NUMERO 999")),
                Op("31/12", "ACHAT CB TRAITEUR 30.12.26", -8_000, listOf("CARTE NUMERO 999")),
                Op("05/01", "VIREMENT DE SOCIETE EXEMPLE", 200_000),
            ),
        ),
        savings = emptyList(),
    )

    fun build(spec: Spec): ByteArray = PDDocument().use { document ->
        val writer = Writer(document)
        val dates = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val open = spec.openingDate.format(dates)
        val close = spec.closingDate.format(dates)

        // Page 1 : en-tête, situation des comptes, début du CCP.
        writer.page()
        writer.text(416f, 93f, 9f, "Relevé édité le 12 octobre 2026")
        writer.text(42f, 99f, 16f, "Relevé de vos comptes - n° 10")
        writer.text(91f, 148f, 9f, "> www.labanquepostale.fr")
        writer.text(312f, 209f, 9f, "MME DUPONT CLAIRE")
        writer.text(312f, 233f, 9f, "1 RUE DE L'EXEMPLE")
        writer.text(56f, 408f, 15f, "Situation de vos comptes au 9 octobre 2026")
        writer.text(524f, 408f, 15f, "Solde")
        var y = 424f
        for (account in listOf(spec.checking) + spec.savings) {
            writer.text(56f, y, 11f, account.title)
            writer.text(483f, y, 11f, "+")
            writer.right(534f, y, 11f, amount(account.closingCents))
            writer.text(537f, y, 11f, "€")
            y += 17f
        }

        val checking = spec.checking
        writer.text(54f, 505f, 15f, checking.title)
        writer.text(54f, 517f, 8f, "IBAN : ${spaced(checking.iban)} | BIC : PSSTFRPPLIL")
        writer.text(54f, 527f, 9f, "> Découvert autorisé au $close : 1 000,00 € (à régulariser sous 30 jours).")
        writer.text(54f, 586f, 9f, "> Frais et cotisations perçus depuis le $open : - 49,95 €")
        y = writer.columnHeader(632f, debitX = 434f)
        y = writer.balance(y + 19f, "Ancien solde au $open", checking.openingCents, Writer.CHECKING_COLUMNS)

        y = writer.ops(checking.ops.take(spec.firstPageOps), y + 17f, Writer.CHECKING_COLUMNS)
        writer.footer(1)

        // Page 2 : suite du CCP.
        writer.page()
        writer.pageHeader()
        writer.text(54f, 141f, 11f, "Vos opérations ${checking.title} (suite)")
        y = writer.columnHeader(163f, debitX = 434f)
        y = writer.ops(checking.ops.drop(spec.firstPageOps), y + 14f, Writer.CHECKING_COLUMNS)
        y = writer.totals(y + 14f, checking, Writer.CHECKING_COLUMNS)
        writer.balance(y + 23f, "Nouveau solde au $close", checking.closingCents, Writer.CHECKING_COLUMNS)
        writer.text(50f, 555f, 15f, "Pour votre information")
        writer.footer(2)

        // Page 3 : comptes d'épargne.
        if (spec.savings.isNotEmpty()) {
            writer.page()
            writer.pageHeader()
            writer.text(53f, 142f, 15f, "Comptes d'Épargne")
            y = writer.columnHeader(157f, debitX = 438f)
            for (account in spec.savings) {
                writer.text(66f, y + 16f, 12f, account.title)
                writer.text(66f, y + 28f, 8f, "IBAN : ${spaced(account.iban)} | BIC : PSSTFRPPCNE")
                y = writer.balance(y + 55f, "Ancien solde au $open", account.openingCents, Writer.SAVINGS_COLUMNS)
                y = writer.ops(account.ops, y + 20f, Writer.SAVINGS_COLUMNS, size = 10f)
                if (account.ops.isNotEmpty()) y = writer.totals(y + 4f, account, Writer.SAVINGS_COLUMNS)
                y = writer.balance(y + 22f, "Nouveau solde au $close", account.closingCents, Writer.SAVINGS_COLUMNS)
                y += 20f
            }
            writer.footer(3)
        }

        writer.close()
        ByteArrayOutputStream().also { document.save(it) }.toByteArray()
    }

    fun amount(cents: Long): String {
        val absolute = kotlin.math.abs(cents)
        val units = (absolute / 100).toString().reversed().chunked(3).joinToString(" ").reversed()
        return "$units,${(absolute % 100).toString().padStart(2, '0')}"
    }

    private fun spaced(iban: String) = iban.chunked(4).joinToString(" ")

    private class Writer(private val document: PDDocument) {
        private val regular = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        private val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
        private var stream: PDPageContentStream? = null
        private val height = PDRectangle.A4.height

        data class AmountColumns(val debitRight: Float, val creditRight: Float)

        companion object {
            val CHECKING_COLUMNS = AmountColumns(debitRight = 463f, creditRight = 539f)
            val SAVINGS_COLUMNS = AmountColumns(debitRight = 467f, creditRight = 539f)
        }

        fun page() {
            close()
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            stream = PDPageContentStream(document, page)
        }

        fun close() {
            stream?.close()
            stream = null
        }

        fun text(x: Float, yFromTop: Float, size: Float, value: String, isBold: Boolean = false) {
            val cs = stream!!
            cs.beginText()
            cs.setFont(if (isBold) bold else regular, size)
            cs.newLineAtOffset(x, height - yFromTop)
            cs.showText(value)
            cs.endText()
        }

        fun right(xEnd: Float, yFromTop: Float, size: Float, value: String, isBold: Boolean = false) {
            val font = if (isBold) bold else regular
            val width = font.getStringWidth(value) / 1000f * size
            text(xEnd - width, yFromTop, size, value, isBold)
        }

        fun pageHeader() {
            text(450f, 100f, 8f, "Relevé n° 10 | 12/10/2026")
            text(467f, 109f, 8f, "MME DUPONT CLAIRE")
        }

        fun columnHeader(y: Float, debitX: Float): Float {
            text(54f, y, 8f, "Date")
            text(86f, y, 8f, "Opérations")
            text(debitX, y, 8f, "Débit (€)")
            text(507f, y, 8f, "Crédit (€)")
            return y
        }

        fun balance(y: Float, label: String, cents: Long, columns: AmountColumns): Float {
            text(272f, y, 10f, label, isBold = true)
            right(if (cents < 0) columns.debitRight else columns.creditRight, y, 10f, amount(cents), isBold = true)
            return y
        }

        fun ops(ops: List<Op>, start: Float, columns: AmountColumns, size: Float = 9f): Float {
            var y = start
            for (op in ops) {
                text(54f, y, size, op.dayMonth)
                text(86f, y, size, op.label)
                right(if (op.cents < 0) columns.debitRight else columns.creditRight, y, size, amount(op.cents))
                for (detail in op.details) {
                    y += 9.6f
                    text(86f, y, 8f, detail)
                }
                y += 11f
            }
            return y
        }

        fun totals(y: Float, account: Account, columns: AmountColumns): Float {
            text(308f, y, 10f, "Total des opérations")
            right(columns.debitRight, y, 10f, amount(account.ops.filter { it.cents < 0 }.sumOf { -it.cents }))
            right(columns.creditRight, y, 10f, amount(account.ops.filter { it.cents > 0 }.sumOf { it.cents }))
            return y
        }

        fun footer(page: Int) {
            text(42f, 788f, 8f, "La Banque Postale - S.A. à Directoire et Conseil de Surveillance.")
            text(498f, 810f, 10f, "Page $page/3")
        }
    }
}
