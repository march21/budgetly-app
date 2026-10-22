-- Montants en centimes (INTEGER), dates ISO-8601 (TEXT).

CREATE TABLE owner (
    id   INTEGER PRIMARY KEY,
    name TEXT NOT NULL
);

INSERT INTO owner (id, name) VALUES (1, 'Moi');

CREATE TABLE account (
    id          INTEGER PRIMARY KEY,
    owner_id    INTEGER NOT NULL REFERENCES owner (id),
    institution TEXT    NOT NULL,
    type        TEXT    NOT NULL,
    name        TEXT    NOT NULL,
    iban        TEXT    NOT NULL UNIQUE,
    currency    TEXT    NOT NULL DEFAULT 'EUR',
    created_at  TEXT    NOT NULL
);

-- Journal d'import : jamais le PDF, seulement son empreinte et ce qu'il a produit.
CREATE TABLE statement_import (
    id           INTEGER PRIMARY KEY,
    owner_id     INTEGER NOT NULL REFERENCES owner (id),
    file_sha256  TEXT    NOT NULL UNIQUE,
    parser_id    TEXT    NOT NULL,
    imported_at  TEXT    NOT NULL,
    period_start TEXT    NOT NULL,
    period_end   TEXT    NOT NULL,
    impact_json  TEXT    NOT NULL
);

CREATE TABLE statement_balance (
    import_id     INTEGER NOT NULL REFERENCES statement_import (id),
    account_id    INTEGER NOT NULL REFERENCES account (id),
    opening_date  TEXT    NOT NULL,
    opening_cents INTEGER NOT NULL,
    closing_date  TEXT    NOT NULL,
    closing_cents INTEGER NOT NULL,
    PRIMARY KEY (import_id, account_id)
);

CREATE TABLE txn (
    id                INTEGER PRIMARY KEY,
    account_id        INTEGER NOT NULL REFERENCES account (id),
    import_id         INTEGER NOT NULL REFERENCES statement_import (id),
    booking_date      TEXT    NOT NULL,
    operation_date    TEXT    NOT NULL,
    amount_cents      INTEGER NOT NULL,
    label             TEXT    NOT NULL,
    details           TEXT    NOT NULL DEFAULT '',
    kind              TEXT    NOT NULL,
    fingerprint       TEXT    NOT NULL UNIQUE,
    internal_transfer INTEGER NOT NULL DEFAULT 0,
    transfer_txn_id   INTEGER REFERENCES txn (id)
);

CREATE INDEX txn_account_date ON txn (account_id, booking_date);
CREATE INDEX txn_operation_date ON txn (operation_date);
