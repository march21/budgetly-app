-- Patrimoine : enveloppes d'investissement (PEA, assurance vie).
-- account.iban sert d'identifiant de compte : IBAN pour un compte bancaire, numéro de contrat pour une enveloppe.

-- Support d'investissement. Un fonds en euros n'a pas d'ISIN : il est identifié par son nom.
CREATE TABLE security (
    id   INTEGER PRIMARY KEY,
    isin TEXT UNIQUE,
    name TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('STOCK', 'FUND', 'ETF', 'EURO_FUND', 'OTHER'))
);

-- Positions telles qu'imprimées sur un relevé. Quantités et prix : décimaux exacts (texte).
CREATE TABLE position_snapshot (
    account_id       INTEGER NOT NULL REFERENCES account (id),
    import_id        INTEGER REFERENCES statement_import (id),
    valuation_date   TEXT    NOT NULL,
    security_id      INTEGER NOT NULL REFERENCES security (id),
    quantity         TEXT,
    unit_price       TEXT,
    price_date       TEXT,
    value_cents      INTEGER NOT NULL,
    cost_basis_unit  TEXT,
    unrealized_cents INTEGER,
    PRIMARY KEY (account_id, valuation_date, security_id)
);

-- Valeur d'une enveloppe à une date : lue sur un relevé, ou saisie à la main.
CREATE TABLE valuation (
    account_id          INTEGER NOT NULL REFERENCES account (id),
    valuation_date      TEXT    NOT NULL,
    value_cents         INTEGER NOT NULL,
    cash_cents          INTEGER NOT NULL DEFAULT 0,
    contributions_cents INTEGER,
    source              TEXT    NOT NULL CHECK (source IN ('STATEMENT', 'MANUAL')),
    import_id           INTEGER REFERENCES statement_import (id),
    PRIMARY KEY (account_id, valuation_date, source)
);

-- Cours et valeurs liquidatives récupérés (par ISIN uniquement), en euros.
CREATE TABLE price (
    security_id INTEGER NOT NULL REFERENCES security (id),
    price_date  TEXT    NOT NULL,
    price       TEXT    NOT NULL,
    source      TEXT    NOT NULL,
    PRIMARY KEY (security_id, price_date)
);

-- Résolution ISIN → symbole chez la source de cours (symbol NULL = introuvable à cette date de vérification).
CREATE TABLE price_symbol (
    security_id INTEGER PRIMARY KEY REFERENCES security (id),
    provider    TEXT NOT NULL,
    symbol      TEXT,
    status      TEXT NOT NULL,
    checked_at  TEXT NOT NULL
);
