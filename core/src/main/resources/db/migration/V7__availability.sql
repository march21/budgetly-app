-- Épargne salariale : répartition des avoirs par date de disponibilité, à la date d'un relevé.
CREATE TABLE availability_snapshot (
    account_id     INTEGER NOT NULL REFERENCES account (id),
    import_id      INTEGER NOT NULL REFERENCES statement_import (id),
    valuation_date TEXT    NOT NULL,
    available_on   TEXT    NOT NULL,
    value_cents    INTEGER NOT NULL,
    PRIMARY KEY (account_id, valuation_date, available_on)
);
