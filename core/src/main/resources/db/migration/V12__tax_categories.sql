-- Catégories utiles à la déclaration des revenus (aide à la déclaration). Ajoutées sans écraser une catégorie
-- existante : une catégorie de même code ou de même nom au même niveau est conservée telle quelle.
INSERT OR IGNORE INTO category (id, parent_id, code, name, kind, system) VALUES
    (91,  NULL, 'donations',       'Dons',                               'EXPENSE', 0),
    (92,  91,   'donations-help',  'Dons aux personnes en difficulté',   'EXPENSE', 0),
    (93,  NULL, 'home-services',   'Emploi à domicile',                  'EXPENSE', 0),
    (94,  NULL, 'childcare',       'Garde d''enfants hors du domicile',  'EXPENSE', 0),
    (203, 200,  'retirement',      'Épargne retraite (PER)',             'SAVINGS', 0);
