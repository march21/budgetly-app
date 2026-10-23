-- Crédits amortissables (immobilier, consommation) : échéances constantes. Taux annuel nominal en pourcentage,
-- décimal exact (texte). Montants en centimes. payment_label : texte du libellé de prélèvement, pour retrouver
-- les échéances parmi les opérations importées.
CREATE TABLE loan (
    id                      INTEGER PRIMARY KEY,
    name                    TEXT    NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
    lender                  TEXT,
    principal_cents         INTEGER NOT NULL CHECK (principal_cents > 0 AND principal_cents <= 100000000000),
    annual_rate             TEXT    NOT NULL,
    first_payment_date      TEXT    NOT NULL,
    term_months             INTEGER NOT NULL CHECK (term_months BETWEEN 1 AND 480),
    insurance_monthly_cents INTEGER CHECK (insurance_monthly_cents BETWEEN 0 AND 100000000),
    payment_label           TEXT,
    created_at              TEXT    NOT NULL
);
