-- Objectifs d'épargne : un montant cible, éventuellement une date, suivi par le solde d'un compte ou par un
-- montant mis de côté saisi à la main. Montants en centimes.
CREATE TABLE savings_goal (
    id            INTEGER PRIMARY KEY,
    name          TEXT    NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
    target_cents  INTEGER NOT NULL CHECK (target_cents > 0 AND target_cents <= 100000000000),
    target_date   TEXT,
    account_id    INTEGER REFERENCES account (id) ON DELETE SET NULL,
    saved_cents   INTEGER CHECK (saved_cents BETWEEN 0 AND 100000000000),
    monthly_cents INTEGER CHECK (monthly_cents BETWEEN 0 AND 100000000000),
    created_at    TEXT    NOT NULL
);
