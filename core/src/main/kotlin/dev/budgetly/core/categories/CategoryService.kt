package dev.budgetly.core.categories

import dev.budgetly.core.imports.ApiException
import dev.budgetly.core.statement.TransactionKind
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

data class CategoryView(
    val id: Long,
    val parentId: Long?,
    val name: String,
    val kind: CategoryKind,
    val system: Boolean,
    val budgetCents: Long?,
    val ruleCount: Int,
    val transactionCount: Int,
)

data class RuleView(
    val id: Long,
    val pattern: String,
    val matchType: MatchType,
    val scope: RuleScope,
    val categoryId: Long,
    val categoryName: String,
    val priority: Int,
)

data class NewCategory(val name: String, val parentId: Long? = null, val kind: CategoryKind? = null)

data class SplitPart(val categoryId: Long, val amountCents: Long)

data class NewRule(val pattern: String, val categoryId: Long, val scope: RuleScope = RuleScope.LABEL)

/** Libellés non classés regroupés par motif de règle proposé : de quoi créer une règle en un clic. */
data class RuleSuggestion(
    val pattern: String,
    val count: Int,
    val totalCents: Long,
    val sampleLabel: String,
    val lastDate: java.time.LocalDate,
)

@Service
class CategoryService(private val jdbc: JdbcClient, private val clock: Clock) {

    fun categories(): List<CategoryView> =
        jdbc.sql(
            """
            select c.id, c.parent_id, c.name, c.kind, c.system, b.monthly_cents,
                   (select count(*) from category_rule r where r.category_id = c.id),
                   (select count(*) from txn t where t.category_id = c.id)
            from category c left join budget b on b.category_id = c.id
            order by case c.kind when 'EXPENSE' then 0 when 'INCOME' then 1 when 'SAVINGS' then 2 else 3 end,
                     coalesce(c.parent_id, c.id), c.parent_id is not null, c.name
            """.trimIndent(),
        ).query { rs, _ ->
            CategoryView(
                id = rs.getLong(1),
                parentId = rs.getLong(2).takeUnless { rs.wasNull() },
                name = rs.getString(3),
                kind = CategoryKind.valueOf(rs.getString(4)),
                system = rs.getInt(5) == 1,
                budgetCents = rs.getLong(6).takeUnless { rs.wasNull() },
                ruleCount = rs.getInt(7),
                transactionCount = rs.getInt(8),
            )
        }.list()

    fun rules(): List<RuleView> =
        jdbc.sql(
            """
            select r.id, r.pattern, r.match_type, r.scope, r.category_id, c.name, r.priority
            from category_rule r join category c on c.id = r.category_id
            order by r.priority, length(r.pattern) desc, r.id
            """.trimIndent(),
        ).query { rs, _ ->
            RuleView(
                id = rs.getLong(1),
                pattern = rs.getString(2),
                matchType = MatchType.valueOf(rs.getString(3)),
                scope = RuleScope.valueOf(rs.getString(4)),
                categoryId = rs.getLong(5),
                categoryName = rs.getString(6),
                priority = rs.getInt(7),
            )
        }.list()

    @Transactional
    fun createCategory(request: NewCategory): Long {
        val name = request.name.trim()
        if (name.isEmpty()) throw badRequest("Le nom de la catégorie est vide.")
        val kind = if (request.parentId != null) {
            val parent = categories().firstOrNull { it.id == request.parentId } ?: throw notFound()
            if (parent.parentId != null) throw badRequest("Deux niveaux au plus : catégorie et sous-catégorie.")
            parent.kind
        } else {
            request.kind ?: throw badRequest("Précisez s'il s'agit d'une dépense, d'un revenu ou d'épargne.")
        }
        if (kind == CategoryKind.TRANSFER) throw badRequest("La catégorie des virements entre vos comptes est unique.")
        val exists = jdbc.sql("select count(*) from category where name = ? and coalesce(parent_id, 0) = coalesce(?, 0)")
            .params(name, request.parentId).query(Long::class.java).single() > 0
        if (exists) throw ApiException(HttpStatus.CONFLICT, "CATEGORY_EXISTS", "Cette catégorie existe déjà.")
        jdbc.sql("insert into category (parent_id, name, kind) values (?, ?, ?)").params(request.parentId, name, kind.name).update()
        return jdbc.sql("select last_insert_rowid()").query(Long::class.java).single()
    }

    @Transactional
    fun renameCategory(id: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw badRequest("Le nom de la catégorie est vide.")
        if (jdbc.sql("update category set name = ? where id = ?").params(trimmed, id).update() == 0) throw notFound()
    }

    /** Supprime une catégorie (et ses sous-catégories) : leurs opérations repassent en classement automatique. */
    @Transactional
    fun deleteCategory(id: Long) {
        val category = categories().firstOrNull { it.id == id } ?: throw notFound()
        if (category.system) throw badRequest("Cette catégorie est nécessaire au fonctionnement de Budgetly.")
        val ids = listOf(id) + categories().filter { it.parentId == id }.map { it.id }
        val marks = ids.joinToString(",") { "?" }
        jdbc.sql("update txn set category_id = null, category_source = null where category_id in ($marks)").params(ids).update()
        // Une ventilation qui utilisait la catégorie est défaite : l'opération revient au classement automatique.
        jdbc.sql(
            "update txn set category_id = null, category_source = null where id in (select txn_id from txn_split where category_id in ($marks))",
        ).params(ids).update()
        jdbc.sql("delete from txn_split where txn_id in (select id from txn where category_source is null) ").update()
        jdbc.sql("delete from category_rule where category_id in ($marks)").params(ids).update()
        jdbc.sql("delete from budget where category_id in ($marks)").params(ids).update()
        jdbc.sql("delete from category where parent_id = ?").param(id).update()
        jdbc.sql("delete from category where id = ?").param(id).update()
        recategorize()
    }

    @Transactional
    fun setBudget(categoryId: Long, monthlyCents: Long?) {
        val category = categories().firstOrNull { it.id == categoryId } ?: throw notFound()
        if (category.kind != CategoryKind.EXPENSE) throw badRequest("Un budget ne se fixe que sur une catégorie de dépenses.")
        if (monthlyCents == null || monthlyCents == 0L) {
            jdbc.sql("delete from budget where category_id = ?").param(categoryId).update()
        } else {
            if (monthlyCents < 0) throw badRequest("Le budget doit être positif.")
            jdbc.sql("insert into budget (category_id, monthly_cents) values (?, ?) on conflict(category_id) do update set monthly_cents = excluded.monthly_cents")
                .params(categoryId, monthlyCents).update()
        }
    }

    /** Règle créée par l'utilisateur : prioritaire sur les règles par défaut, appliquée aussitôt. */
    @Transactional
    fun createRule(request: NewRule): Long {
        val pattern = request.pattern.trim()
        if (pattern.length < 3) throw badRequest("Le motif doit contenir au moins 3 caractères.")
        categories().firstOrNull { it.id == request.categoryId } ?: throw notFound()
        jdbc.sql(
            "insert into category_rule (pattern, match_type, scope, category_id, priority, created_at) values (?, 'CONTAINS', ?, ?, ?, ?) " +
                "on conflict(pattern, match_type, scope) do update set category_id = excluded.category_id, priority = excluded.priority",
        ).params(pattern, request.scope.name, request.categoryId, USER_RULE_PRIORITY, clock.instant().toString()).update()
        recategorize()
        return jdbc.sql("select id from category_rule where pattern = ? and match_type = 'CONTAINS' and scope = ?")
            .params(pattern, request.scope.name).query(Long::class.java).single()
    }

    @Transactional
    fun deleteRule(id: Long) {
        if (jdbc.sql("delete from category_rule where id = ?").param(id).update() == 0) throw notFound()
        recategorize()
    }

    /**
     * Classe une opération à la main (`categoryId`), ou la rend au classement automatique (`null`).
     * Avec `rulePattern`, crée aussi la règle pour les opérations semblables, passées et futures.
     */
    @Transactional
    fun categorizeTransaction(transactionId: Long, categoryId: Long?, rulePattern: String?) {
        if (categoryId != null) categories().firstOrNull { it.id == categoryId } ?: throw notFound()
        jdbc.sql("delete from txn_split where txn_id = ?").param(transactionId).update()
        val updated = if (categoryId == null) {
            jdbc.sql("update txn set category_id = null, category_source = null where id = ?").param(transactionId).update()
        } else {
            jdbc.sql("update txn set category_id = ?, category_source = 'MANUAL' where id = ?")
                .params(categoryId, transactionId).update()
        }
        if (updated == 0) throw notFound()
        if (categoryId != null && !rulePattern.isNullOrBlank()) createRule(NewRule(rulePattern, categoryId))
        recategorize()
    }

    /** Classe à la main plusieurs opérations dans la même catégorie (ou les rend au classement automatique). */
    @Transactional
    fun categorizeTransactions(ids: List<Long>, categoryId: Long?): Int {
        if (ids.isEmpty()) return 0
        if (ids.size > 2_000) throw badRequest("2 000 opérations au plus à la fois.")
        if (categoryId != null) categories().firstOrNull { it.id == categoryId } ?: throw notFound()
        var updated = 0
        for (chunk in ids.distinct().chunked(500)) {
            val marks = chunk.joinToString(",") { "?" }
            jdbc.sql("delete from txn_split where txn_id in ($marks)").params(chunk).update()
            updated += if (categoryId == null) {
                jdbc.sql("update txn set category_id = null, category_source = null where id in ($marks)").params(chunk).update()
            } else {
                jdbc.sql("update txn set category_id = ?, category_source = 'MANUAL' where id in ($marks)")
                    .params(listOf(categoryId) + chunk).update()
            }
        }
        recategorize()
        return updated
    }

    /** Motifs les plus fréquents parmi les opérations non classées (les plus utiles à transformer en règle). */
    fun ruleSuggestions(limit: Int): List<RuleSuggestion> {
        data class Row(val label: String, val cents: Long, val date: java.time.LocalDate)
        val rows = jdbc.sql("select label, amount_cents, operation_date from txn where category_id is null and (category_source is null or category_source <> 'SPLIT')")
            .query { rs, _ -> Row(rs.getString(1), rs.getLong(2), java.time.LocalDate.parse(rs.getString(3))) }
            .list()
        return rows.groupBy { RuleEngine.suggestPattern(it.label) }
            .filterKeys { it.length >= 3 }
            .map { (pattern, group) ->
                RuleSuggestion(
                    pattern = pattern,
                    count = group.size,
                    totalCents = group.sumOf { it.cents },
                    sampleLabel = group.maxBy { it.date }.label,
                    lastDate = group.maxOf { it.date },
                )
            }
            .sortedWith(compareByDescending<RuleSuggestion> { it.count }.thenByDescending { Math.abs(it.totalCents) })
            .take(limit.coerceIn(1, 100))
    }

    /**
     * Ventile une opération sur plusieurs catégories (au moins deux parts, du même signe que l'opération,
     * dont la somme fait exactement son montant). Une liste vide défait la ventilation.
     */
    @Transactional
    fun splitTransaction(transactionId: Long, parts: List<SplitPart>) {
        val amount = jdbc.sql("select amount_cents from txn where id = ?").param(transactionId).query(Long::class.java)
            .optional().orElseThrow { notFound() }
        jdbc.sql("delete from txn_split where txn_id = ?").param(transactionId).update()
        if (parts.isEmpty()) {
            jdbc.sql("update txn set category_id = null, category_source = null where id = ?").param(transactionId).update()
            recategorize()
            return
        }
        if (parts.size < 2) throw badRequest("Une ventilation compte au moins deux parts.")
        if (parts.size > 20) throw badRequest("20 parts au plus.")
        if (parts.any { it.amountCents == 0L || (it.amountCents < 0) != (amount < 0) }) {
            throw badRequest("Chaque part doit être non nulle et du même signe que l'opération.")
        }
        if (parts.sumOf { it.amountCents } != amount) throw badRequest("La somme des parts doit être égale au montant de l'opération.")
        val known = categories().map { it.id }.toSet()
        if (parts.any { it.categoryId !in known }) throw notFound()
        parts.forEachIndexed { i, part ->
            jdbc.sql("insert into txn_split (txn_id, position, category_id, amount_cents) values (?, ?, ?, ?)")
                .params(transactionId, i, part.categoryId, part.amountCents).update()
        }
        jdbc.sql("update txn set category_id = null, category_source = 'SPLIT' where id = ?").param(transactionId).update()
    }

    fun setNote(transactionId: Long, note: String?) {
        val text = note?.trim()?.takeIf { it.isNotEmpty() }
        if (text != null && text.length > 500) throw badRequest("500 caractères au plus.")
        if (jdbc.sql("update txn set note = ? where id = ?").params(text, transactionId).update() == 0) throw notFound()
    }

    /** Réapplique le classement automatique à toutes les opérations non classées à la main. */
    @Transactional
    fun recategorize(): Int {
        val engine = engine()
        val rows = jdbc.sql(
            "select id, label, details, kind, internal_transfer, category_id, category_source from txn " +
                "where category_source is null or category_source not in ('MANUAL', 'SPLIT')",
        ).query { rs, _ ->
            Triple(
                rs.getLong(1),
                Categorizable(rs.getString(2), rs.getString(3), TransactionKind.valueOf(rs.getString(4)), rs.getInt(5) == 1),
                rs.getLong(6).takeUnless { rs.wasNull() } to rs.getString(7),
            )
        }.list()
        var changed = 0
        for ((id, t, current) in rows) {
            val assignment = engine.assign(t)
            val next = assignment?.categoryId to assignment?.source?.name
            if (next != current) {
                jdbc.sql("update txn set category_id = ?, category_source = ? where id = ?")
                    .params(next.first, next.second, id).update()
                changed++
            }
        }
        return changed
    }

    private fun engine(): RuleEngine {
        val rules = jdbc.sql("select id, pattern, match_type, scope, category_id, priority from category_rule").query { rs, _ ->
            Rule(rs.getLong(1), rs.getString(2), MatchType.valueOf(rs.getString(3)), RuleScope.valueOf(rs.getString(4)), rs.getLong(5), rs.getInt(6))
        }.list()
        val codes = jdbc.sql("select code, id from category where code is not null")
            .query { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().toMap()
        val kindDefaults = buildMap {
            codes["bank-fees"]?.let { put(TransactionKind.FEE, it) }
            codes["cash"]?.let { put(TransactionKind.WITHDRAWAL, it) }
        }
        return RuleEngine(rules, codes.getValue("internal"), kindDefaults)
    }

    private fun badRequest(message: String) = ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message)

    private fun notFound() = ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Élément introuvable.")

    companion object {
        const val USER_RULE_PRIORITY = 20
    }
}
