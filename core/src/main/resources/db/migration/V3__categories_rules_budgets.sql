-- Catégories hiérarchiques (catégorie / sous-catégorie) et nature comptable :
--   EXPENSE  dépense · INCOME revenu · SAVINGS versement vers une enveloppe d'épargne (AV, PEE…)
--   TRANSFER mouvement entre mes propres comptes : exclu des revenus et des dépenses.
CREATE TABLE category (
    id        INTEGER PRIMARY KEY,
    parent_id INTEGER REFERENCES category (id),
    code      TEXT UNIQUE,
    name      TEXT    NOT NULL,
    kind      TEXT    NOT NULL CHECK (kind IN ('EXPENSE', 'INCOME', 'SAVINGS', 'TRANSFER')),
    system    INTEGER NOT NULL DEFAULT 0,
    UNIQUE (parent_id, name)
);

-- Règle : si le libellé (ou les détails) contient le motif, l'opération va dans la catégorie.
CREATE TABLE category_rule (
    id          INTEGER PRIMARY KEY,
    pattern     TEXT    NOT NULL,
    match_type  TEXT    NOT NULL CHECK (match_type IN ('CONTAINS', 'REGEX')),
    scope       TEXT    NOT NULL CHECK (scope IN ('LABEL', 'LABEL_AND_DETAILS')),
    category_id INTEGER NOT NULL REFERENCES category (id) ON DELETE CASCADE,
    priority    INTEGER NOT NULL DEFAULT 100,
    created_at  TEXT    NOT NULL,
    UNIQUE (pattern, match_type, scope)
);

-- Budget mensuel par catégorie (montant positif, en centimes).
CREATE TABLE budget (
    category_id   INTEGER PRIMARY KEY REFERENCES category (id) ON DELETE CASCADE,
    monthly_cents INTEGER NOT NULL CHECK (monthly_cents >= 0)
);

-- Origine de la catégorie : MANUAL (choix de l'utilisateur, jamais écrasé), INTERNAL (virement entre mes comptes),
-- RULE (règle), KIND (déduite du type d'opération, ex. frais bancaires).
ALTER TABLE txn ADD COLUMN category_id INTEGER REFERENCES category (id);
ALTER TABLE txn ADD COLUMN category_source TEXT;
CREATE INDEX txn_category ON txn (category_id);

-- Catégories par défaut (renommables et complétables).
INSERT INTO category (id, parent_id, code, name, kind, system) VALUES
    (1,  NULL, 'food',            'Alimentation',              'EXPENSE',  0),
    (2,  1,    'groceries',       'Courses',                   'EXPENSE',  0),
    (3,  1,    'restaurants',     'Restaurants et cafés',      'EXPENSE',  0),
    (10, NULL, 'housing',         'Logement',                  'EXPENSE',  0),
    (11, 10,   'rent-loan',       'Loyer et prêt',             'EXPENSE',  0),
    (12, 10,   'energy',          'Énergie',                   'EXPENSE',  0),
    (13, 10,   'home-insurance',  'Assurance habitation',      'EXPENSE',  0),
    (14, 10,   'telecom',         'Internet et téléphone',     'EXPENSE',  0),
    (20, NULL, 'transport',       'Transports',                'EXPENSE',  0),
    (21, 20,   'public-transport','Transports en commun',      'EXPENSE',  0),
    (22, 20,   'fuel',            'Carburant',                 'EXPENSE',  0),
    (23, 20,   'car-insurance',   'Assurance auto',            'EXPENSE',  0),
    (30, NULL, 'health',          'Santé',                     'EXPENSE',  0),
    (40, NULL, 'leisure',         'Loisirs',                   'EXPENSE',  0),
    (41, 40,   'subscriptions',   'Abonnements',               'EXPENSE',  0),
    (42, 40,   'sport',           'Sport',                     'EXPENSE',  0),
    (50, NULL, 'shopping',        'Shopping',                  'EXPENSE',  0),
    (51, 50,   'clothing',        'Vêtements',                 'EXPENSE',  0),
    (52, 50,   'online',          'Achats en ligne',           'EXPENSE',  0),
    (60, NULL, 'taxes',           'Impôts et taxes',           'EXPENSE',  0),
    (70, NULL, 'bank-fees',       'Frais bancaires',           'EXPENSE',  0),
    (80, NULL, 'cash',            'Retraits',                  'EXPENSE',  0),
    (90, NULL, 'other-expenses',  'Autres dépenses',           'EXPENSE',  0),
    (100, NULL, 'salary',         'Salaire',                   'INCOME',   0),
    (101, NULL, 'refunds',        'Remboursements',            'INCOME',   0),
    (102, NULL, 'interest',       'Intérêts',                  'INCOME',   0),
    (103, NULL, 'other-income',   'Autres revenus',            'INCOME',   0),
    (200, NULL, 'savings',        'Épargne',                   'SAVINGS',  0),
    (201, 200,  'life-insurance', 'Assurance vie',             'SAVINGS',  0),
    (202, 200,  'employee-savings','Épargne salariale',        'SAVINGS',  0),
    (300, NULL, 'internal',       'Entre mes comptes',         'TRANSFER', 1);

-- Règles génériques par défaut (organismes courants). Les règles personnelles naissent de la recatégorisation.
INSERT INTO category_rule (pattern, match_type, scope, category_id, priority, created_at) VALUES
    ('ECHEANCE PRET',                  'CONTAINS', 'LABEL', 11, 50, '2026-10-21T00:00:00Z'),
    ('PRELEVEMENT DE EDF',             'CONTAINS', 'LABEL', 12, 50, '2026-10-21T00:00:00Z'),
    ('ENGIE',                          'CONTAINS', 'LABEL', 12, 60, '2026-10-21T00:00:00Z'),
    ('TOTALENERGIES',                  'CONTAINS', 'LABEL', 12, 60, '2026-10-21T00:00:00Z'),
    ('Assurance Habitation',           'CONTAINS', 'LABEL_AND_DETAILS', 13, 60, '2026-10-21T00:00:00Z'),
    ('PRELEVEMENT DE SFR',             'CONTAINS', 'LABEL', 14, 50, '2026-10-21T00:00:00Z'),
    ('PRELEVEMENT DE ORANGE',          'CONTAINS', 'LABEL', 14, 50, '2026-10-21T00:00:00Z'),
    ('PRELEVEMENT DE FREE',            'CONTAINS', 'LABEL', 14, 50, '2026-10-21T00:00:00Z'),
    ('PRELEVEMENT DE BOUYGUES',        'CONTAINS', 'LABEL', 14, 50, '2026-10-21T00:00:00Z'),
    ('LA POSTE MOBILE',                'CONTAINS', 'LABEL', 14, 50, '2026-10-21T00:00:00Z'),
    ('SNCF',                           'CONTAINS', 'LABEL', 21, 60, '2026-10-21T00:00:00Z'),
    ('RATP',                           'CONTAINS', 'LABEL', 21, 60, '2026-10-21T00:00:00Z'),
    ('ILEVIA',                         'CONTAINS', 'LABEL', 21, 60, '2026-10-21T00:00:00Z'),
    ('Direct Assurance',               'CONTAINS', 'LABEL_AND_DETAILS', 23, 60, '2026-10-21T00:00:00Z'),
    ('DIRECTION GENERALE DES FINANCES','CONTAINS', 'LABEL_AND_DETAILS', 60, 40, '2026-10-21T00:00:00Z'),
    ('DIRECTION GENERALE D',           'CONTAINS', 'LABEL', 60, 45, '2026-10-21T00:00:00Z'),
    ('CARREFOUR',                      'CONTAINS', 'LABEL', 2, 70, '2026-10-21T00:00:00Z'),
    ('LECLERC',                        'CONTAINS', 'LABEL', 2, 70, '2026-10-21T00:00:00Z'),
    ('AUCHAN',                         'CONTAINS', 'LABEL', 2, 70, '2026-10-21T00:00:00Z'),
    ('LIDL',                           'CONTAINS', 'LABEL', 2, 70, '2026-10-21T00:00:00Z'),
    ('INTERMARCHE',                    'CONTAINS', 'LABEL', 2, 70, '2026-10-21T00:00:00Z'),
    ('MONOPRIX',                       'CONTAINS', 'LABEL', 2, 70, '2026-10-21T00:00:00Z'),
    ('FRANPRIX',                       'CONTAINS', 'LABEL', 2, 70, '2026-10-21T00:00:00Z'),
    ('ALDI',                           'CONTAINS', 'LABEL', 2, 70, '2026-10-21T00:00:00Z'),
    ('AMAZON',                         'CONTAINS', 'LABEL', 52, 80, '2026-10-21T00:00:00Z'),
    ('AMZ DIGITAL',                    'CONTAINS', 'LABEL', 41, 75, '2026-10-21T00:00:00Z'),
    ('CNP',                            'CONTAINS', 'LABEL', 201, 40, '2026-10-21T00:00:00Z'),
    ('EASYVIE',                        'CONTAINS', 'LABEL_AND_DETAILS', 201, 40, '2026-10-21T00:00:00Z'),
    ('EPARGNE SAL',                    'CONTAINS', 'LABEL', 202, 40, '2026-10-21T00:00:00Z'),
    ('COTISATION',                     'CONTAINS', 'LABEL', 70, 90, '2026-10-21T00:00:00Z'),
    ('MINIMUM FORFAITAIRE',            'CONTAINS', 'LABEL', 70, 90, '2026-10-21T00:00:00Z'),
    ('AVANTAGE SEUIL',                 'CONTAINS', 'LABEL', 70, 90, '2026-10-21T00:00:00Z'),
    ('INTERETS',                       'CONTAINS', 'LABEL', 102, 90, '2026-10-21T00:00:00Z'),
    ('RETRAIT DAB',                    'CONTAINS', 'LABEL', 80, 90, '2026-10-21T00:00:00Z');
