-- Objectifs d'épargne : un montant cible, éventuellement une date, suivi par le solde d'un compte ou par un
-- montant mis de côté saisi à la main. Montants en centimes.
CREATE TABLE savings_goal (
    id            INTEGER PRIMARY KEY,
    name          TEXT    NOT NULL,
    target_cents  INTEGER NOT NULL CHECK (target_cents > 0),
    target_date   TEXT,
    account_id    INTEGER REFERENCES account (id) ON DELETE SET NULL,
    saved_cents   INTEGER,
    monthly_cents INTEGER,
    created_at    TEXT    NOT NULL
);
