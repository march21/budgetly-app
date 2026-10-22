package dev.budgetly.core.categories

import dev.budgetly.core.statement.TransactionKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RuleEngineTest {

    private fun rule(id: Long, pattern: String, category: Long, priority: Int = 100, scope: RuleScope = RuleScope.LABEL) =
        Rule(id, pattern, MatchType.CONTAINS, scope, category, priority)

    private val engine = RuleEngine(
        rules = listOf(
            rule(1, "AMAZON", 52, priority = 80),
            rule(2, "AMAZON PRIME", 41, priority = 80),
            rule(3, "Assurance Habitation", 13, scope = RuleScope.LABEL_AND_DETAILS),
            rule(4, "boulangerie", 2, priority = 20),
        ),
        internalCategoryId = 300,
        kindDefaults = mapOf(TransactionKind.FEE to 70),
    )

    private fun item(label: String, details: String = "", kind: TransactionKind = TransactionKind.CARD, internal: Boolean = false) =
        Categorizable(label, details, kind, internal)

    @Test
    fun `le motif le plus precis l'emporte a priorite egale`() {
        assertEquals(41, engine.assign(item("ACHAT CB AMAZON PRIME 01.10.26"))?.categoryId)
        assertEquals(52, engine.assign(item("ACHAT CB AMAZON EU SARL 01.10.26"))?.categoryId)
    }

    @Test
    fun `insensible a la casse, aux accents et aux espaces`() {
        assertEquals(2, engine.assign(item("ACHAT CB  Boulangérie du coin"))?.categoryId)
    }

    @Test
    fun `une regle sur les details ne s'applique qu'avec cette portee`() {
        val result = engine.assign(item("PRELEVEMENT DE ASSUREUR", details = "REF : 1 Assurance Habitation NM1"))
        assertEquals(13, result?.categoryId)
        assertEquals(CategorySource.RULE, result?.source)
    }

    @Test
    fun `virement interne avant tout, puis regle, puis type d'operation`() {
        assertEquals(Assignment(300, CategorySource.INTERNAL), engine.assign(item("VIREMENT AMAZON", internal = true)))
        assertEquals(Assignment(70, CategorySource.KIND), engine.assign(item("FRAIS DIVERS", kind = TransactionKind.FEE)))
        assertNull(engine.assign(item("ACHAT CB INCONNU 01.10.26")))
    }

    @Test
    fun `propose le commercant sans prefixe ni date comme motif`() {
        assertEquals("BOULANGERIE DU COIN", RuleEngine.suggestPattern("ACHAT CB BOULANGERIE DU COIN 09.09.26"))
        assertEquals("FOURNISSEUR ENERGIE", RuleEngine.suggestPattern("PRELEVEMENT DE FOURNISSEUR ENERGIE"))
        assertEquals("VIREMENT DE SOCIETE EXEMPLE", RuleEngine.suggestPattern("VIREMENT DE SOCIETE EXEMPLE"))
    }
}
