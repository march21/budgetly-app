-- Note libre sur une opération (chiffrée avec le reste de la base, jamais journalisée).
ALTER TABLE txn ADD COLUMN note TEXT;

-- Ventilation : une opération répartie sur plusieurs catégories. La somme des parts = montant de l'opération ;
-- l'opération elle-même n'a alors plus de catégorie (category_source = 'SPLIT').
CREATE TABLE txn_split (
    txn_id       INTEGER NOT NULL REFERENCES txn (id),
    position     INTEGER NOT NULL,
    category_id  INTEGER NOT NULL REFERENCES category (id),
    amount_cents INTEGER NOT NULL,
    PRIMARY KEY (txn_id, position)
);
CREATE INDEX txn_split_category ON txn_split (category_id);

-- Séries récurrentes détectées que l'utilisateur a écartées (« ce n'est pas une charge fixe »).
CREATE TABLE recurring_ignored (
    series_key TEXT PRIMARY KEY,
    created_at TEXT NOT NULL
);
