package dev.budgetly.core.milestones

import java.time.LocalDate

/**
 * Règles fiscales françaises utilisées pour les repères. Chaque valeur est datée et sourcée dans
 * `docs/decisions.md` (section « Repères fiscaux ») : à revoir à chaque loi de finances.
 */
object FiscalRules {
    /** Vérifiées le 23/10/2026 (service-public.fr, fiches F2385 et F2329). */
    val VERIFIED_ON: LocalDate = LocalDate.of(2026, 10, 23)

    /** PEA : retraits partiels sans clôture et gains exonérés d'impôt sur le revenu après 5 ans. */
    const val PEA_YEARS = 5L
    const val PEA_CEILING_CENTS = 15_000_000L

    /** Assurance vie : abattement annuel sur les gains des rachats après 8 ans. */
    const val LIFE_INSURANCE_YEARS = 8L
    const val LIFE_INSURANCE_ALLOWANCE_SINGLE_CENTS = 460_000L
    const val LIFE_INSURANCE_ALLOWANCE_COUPLE_CENTS = 920_000L

    /** Plafonds des versements sur les livrets réglementés (les intérêts peuvent les dépasser). */
    const val LIVRET_A_CEILING_CENTS = 2_295_000L
    const val LDDS_CEILING_CENTS = 1_200_000L

    /** Prélèvements sociaux sur les gains : taux général 2026 ; assurance vie, PEL, CEL au taux de 17,2 %. */
    const val SOCIAL_LEVIES_GENERAL = 0.186
    const val SOCIAL_LEVIES_LIFE_INSURANCE = 0.172

    /** Un repère « bientôt » : échéance à moins de 6 mois, plafond à 90 % ou plus. */
    const val SOON_MONTHS = 6L
    const val NEAR_CEILING_RATIO = 0.9
}
