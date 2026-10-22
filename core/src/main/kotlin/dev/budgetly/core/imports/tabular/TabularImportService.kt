package dev.budgetly.core.imports.tabular

import com.fasterxml.jackson.databind.ObjectMapper
import dev.budgetly.core.imports.ApiException
import dev.budgetly.core.imports.Fingerprints
import dev.budgetly.core.imports.ImportPreview
import dev.budgetly.core.imports.ImportService
import dev.budgetly.core.imports.PreviewStatus
import dev.budgetly.core.statement.AccountRef
import dev.budgetly.core.statement.AccountType
import dev.budgetly.core.statement.Balance
import dev.budgetly.core.statement.ParsedAccountStatement
import dev.budgetly.core.statement.ParsedStatement
import dev.budgetly.core.statement.ParsedTransaction
import dev.budgetly.core.vault.VaultLockedEvent
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Résumé d'un fichier OFX avant le choix du compte. */
data class OfxSummary(
    val accountMasked: String?,
    val transactionCount: Int,
    val firstDate: LocalDate?,
    val lastDate: LocalDate?,
    val closingCents: Long?,
    val closingDate: LocalDate?,
)

/** Ce que l'interface affiche pour choisir les colonnes : premières lignes du fichier, correspondance proposée. */
data class TabularAnalysis(
    val token: String?,
    val alreadyImported: Boolean,
    val format: TabularFormat,
    val encoding: String,
    val delimiter: String?,
    val rows: List<List<String>>,
    val rowCount: Int,
    val columnCount: Int,
    val suggestedMapping: CsvMapping?,
    val mappingRemembered: Boolean,
    val ofx: OfxSummary?,
    val suggestedAccountId: Long?,
)

/** Compte cible : un compte existant, ou un nouveau compte décrit par l'utilisateur. */
data class TabularAccountChoice(
    val existingAccountId: Long? = null,
    val name: String? = null,
    val type: AccountType? = null,
    val institution: String? = null,
)

/**
 * Soldes de contrôle saisis par l'utilisateur, tels qu'affichés par sa banque : obligatoires quand le fichier
 * n'en fournit pas (pas de colonne de solde ; OFX sans solde de début).
 */
data class TabularPreviewRequest(
    val mapping: CsvMapping? = null,
    val account: TabularAccountChoice,
    val openingCents: Long? = null,
    val closingCents: Long? = null,
)

/**
 * Import des exports CSV et OFX des banques. Même parcours que les relevés PDF (aperçu puis validation, tout ou
 * rien) et même exigence : deux soldes encadrent les opérations et doivent concorder avec elles.
 * Le fichier reste en mémoire 30 minutes au plus, le temps de choisir les colonnes ; il n'est jamais écrit.
 */
@Service
@RegisterReflectionForBinding(CsvMapping::class, TabularAnalysis::class, TabularPreviewRequest::class, OfxSummary::class)
class TabularImportService(
    private val imports: ImportService,
    private val jdbc: JdbcClient,
    private val json: ObjectMapper,
    private val clock: Clock,
) {
    private class Pending(val createdAt: Instant, val hash: String, val text: String, val format: TabularFormat)

    private val pending = ConcurrentHashMap<String, Pending>()
    private val random = SecureRandom()

    fun analyze(bytes: ByteArray): TabularAnalysis {
        purgeExpired()
        val hash = Fingerprints.sha256(bytes)
        val decoded = TabularText.decode(bytes)
        val format = TabularText.formatOf(decoded.text)
        val alreadyImported = jdbc.sql("select count(*) from statement_import where file_sha256 = ?")
            .param(hash).query(Long::class.java).single() > 0
        val token = if (alreadyImported) null else UUID.randomUUID().toString().also {
            pending[it] = Pending(clock.instant(), hash, decoded.text, format)
        }
        if (format == TabularFormat.OFX) {
            val ofx = OfxStatement.read(decoded.text)
            return TabularAnalysis(
                token = token, alreadyImported = alreadyImported, format = format, encoding = decoded.encoding,
                delimiter = null, rows = emptyList(), rowCount = ofx.transactions.size, columnCount = 0,
                suggestedMapping = null, mappingRemembered = false,
                ofx = OfxSummary(
                    accountMasked = ofx.accountId?.let { "•••• ${it.takeLast(4)}" },
                    transactionCount = ofx.transactions.size,
                    firstDate = ofx.transactions.firstOrNull()?.bookingDate,
                    lastDate = ofx.transactions.lastOrNull()?.bookingDate,
                    closingCents = ofx.closing?.cents,
                    closingDate = ofx.closing?.date,
                ),
                suggestedAccountId = ofx.accountId?.let(::accountMatching),
            )
        }
        val delimiter = TabularText.detectDelimiter(decoded.text)
        val rows = TabularText.rows(decoded.text, delimiter)
        val suggested = CsvStatement.suggest(rows)
        val remembered = suggested?.headerRow?.let { rememberedMapping(rows[it]) }
            ?.takeIf { m -> m.firstDataRow == suggested.firstDataRow }
        return TabularAnalysis(
            token = token, alreadyImported = alreadyImported, format = format, encoding = decoded.encoding,
            delimiter = delimiter.toString(), rows = rows.take(12), rowCount = rows.size,
            columnCount = rows.maxOfOrNull { it.size } ?: 0,
            suggestedMapping = remembered ?: suggested, mappingRemembered = remembered != null,
            ofx = null, suggestedAccountId = null,
        )
    }

    fun preview(token: String, request: TabularPreviewRequest): ImportPreview {
        purgeExpired()
        val file = pending[token]
            ?: throw ApiException(HttpStatus.NOT_FOUND, "FILE_EXPIRED", "Le fichier n'est plus en mémoire : déposez-le à nouveau.")
        val account = resolveAccount(request.account, if (file.format == TabularFormat.OFX) OfxStatement.read(file.text).accountId else null)

        val transactions: List<ParsedTransaction>
        var opening: Balance? = null
        var closing: Balance? = null
        val parserId: String
        if (file.format == TabularFormat.OFX) {
            val ofx = OfxStatement.read(file.text)
            if (ofx.problems.isNotEmpty()) return rejected(ofx.problems)
            transactions = ofx.transactions
            closing = ofx.closing
            parserId = "ofx-generique"
        } else {
            val mapping = request.mapping
                ?: throw ApiException(HttpStatus.BAD_REQUEST, "MAPPING_REQUIRED", "Indiquez la correspondance des colonnes.")
            val rows = TabularText.rows(file.text, TabularText.detectDelimiter(file.text))
            val read = CsvStatement.read(rows, mapping)
            if (read.problems.isNotEmpty()) return rejected(read.problems)
            transactions = read.transactions
            opening = read.opening
            closing = read.closing
            parserId = "csv-generique"
            mapping.headerRow?.let { remember(rows[it], mapping) }
        }

        val first = transactions.first().bookingDate
        val last = transactions.last().bookingDate
        opening = opening ?: request.openingCents?.let { Balance(first, it) }
        closing = closing ?: request.closingCents?.let { Balance(last, it) }
        if (opening == null || closing == null) {
            throw ApiException(
                HttpStatus.BAD_REQUEST, "BALANCES_REQUIRED",
                "Ce fichier ne donne pas les deux soldes qui permettent de contrôler les opérations : " +
                    "saisissez le solde avant la première opération et le solde après la dernière, tels qu'affichés par votre banque.",
            )
        }
        val statement = ParsedStatement(
            parserId = parserId,
            institution = account.institution,
            accounts = listOf(ParsedAccountStatement(account.ref, opening, closing, null, null, transactions)),
        )
        return imports.previewStatement(file.hash, statement, looseDuplicates = true)
    }

    private class Target(val ref: AccountRef, val institution: String)

    private fun resolveAccount(choice: TabularAccountChoice, ofxAccountId: String?): Target {
        choice.existingAccountId?.let { id ->
            return jdbc.sql("select type, name, iban, institution from account where id = ?").param(id)
                .query { rs, _ -> Target(AccountRef(AccountType.valueOf(rs.getString(1)), rs.getString(2), rs.getString(3)), rs.getString(4)) }
                .optional()
                .orElseThrow { ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Compte introuvable.") }
        }
        val name = choice.name?.trim().orEmpty()
        val type = choice.type
        if (name.isEmpty() || type == null) {
            throw ApiException(HttpStatus.BAD_REQUEST, "ACCOUNT_REQUIRED", "Choisissez un compte existant, ou donnez un nom et un type au nouveau compte.")
        }
        if (type !in TRANSACTION_ACCOUNTS) {
            throw ApiException(HttpStatus.BAD_REQUEST, "ACCOUNT_TYPE", "Un export d'opérations s'importe dans un compte courant ou un livret.")
        }
        val identifier = ofxAccountId ?: "CSV${randomSuffix()}"
        if (ofxAccountId != null && accountMatching(ofxAccountId) != null) {
            throw ApiException(HttpStatus.CONFLICT, "ACCOUNT_EXISTS", "Ce compte existe déjà : choisissez-le dans la liste.")
        }
        return Target(AccountRef(type, name, identifier), choice.institution?.trim()?.ifEmpty { null } ?: "Import CSV / OFX")
    }

    /** Compte existant dont l'IBAN contient l'identifiant OFX (banque + guichet + numéro de compte). */
    private fun accountMatching(ofxAccountId: String): Long? =
        jdbc.sql("select id, iban from account").query { rs, _ -> rs.getLong(1) to rs.getString(2) }.list()
            .firstOrNull { (_, iban) -> iban == ofxAccountId || (ofxAccountId.length >= 11 && iban.contains(ofxAccountId)) }?.first

    private fun randomSuffix(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..10).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun rejected(problems: List<String>) = ImportPreview(
        previewId = null, status = PreviewStatus.REJECTED, parserId = null, institution = null,
        periodStart = null, periodEnd = null, accounts = emptyList(), transactions = emptyList(), issues = problems.take(10),
    )

    /** Correspondance mémorisée par en-tête (dans la base chiffrée) : la même banque produit les mêmes en-têtes. */
    private fun headerKey(header: List<String>) = "csv-mapping:" + Fingerprints.sha256(header.joinToString("\u001F").lowercase().toByteArray())

    private fun rememberedMapping(header: List<String>): CsvMapping? =
        jdbc.sql("select value from app_setting where key = ?").param(headerKey(header)).query(String::class.java).optional()
            .map { runCatching { json.readValue(it, CsvMapping::class.java) }.getOrNull() }.orElse(null)

    private fun remember(header: List<String>, mapping: CsvMapping) {
        jdbc.sql("insert into app_setting (key, value) values (?, ?) on conflict(key) do update set value = excluded.value")
            .params(headerKey(header), json.writeValueAsString(mapping)).update()
    }

    /** Coffre verrouillé : les fichiers en attente sont oubliés. */
    @EventListener
    fun onLocked(event: VaultLockedEvent) = pending.clear()

    private fun purgeExpired() {
        val limit = clock.instant().minus(Duration.ofMinutes(30))
        pending.entries.removeIf { it.value.createdAt.isBefore(limit) }
    }

    companion object {
        val TRANSACTION_ACCOUNTS = setOf(AccountType.CHECKING, AccountType.LIVRET_A, AccountType.LDDS, AccountType.SAVINGS)
    }
}
