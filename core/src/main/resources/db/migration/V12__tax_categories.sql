-- Catégories utiles à la déclaration des revenus (aide à la déclaration).
-- Une catégorie que l'utilisateur a déjà créée sous le même nom, au même niveau et sans code, est reprise
-- (elle reçoit le code, ses opérations comptent) ; sinon la catégorie est créée. Rien n'est écrasé.
UPDATE category SET code = 'donations'
WHERE parent_id IS NULL AND name = 'Dons' AND code IS NULL
  AND NOT EXISTS (SELECT 1 FROM category WHERE code = 'donations');
INSERT INTO category (parent_id, code, name, kind, system)
SELECT NULL, 'donations', 'Dons', 'EXPENSE', 0 WHERE NOT EXISTS (SELECT 1 FROM category WHERE code = 'donations');

UPDATE category SET code = 'donations-help'
WHERE parent_id = (SELECT id FROM category WHERE code = 'donations') AND name = 'Dons aux personnes en difficulté' AND code IS NULL
  AND NOT EXISTS (SELECT 1 FROM category WHERE code = 'donations-help');
INSERT INTO category (parent_id, code, name, kind, system)
SELECT (SELECT id FROM category WHERE code = 'donations'), 'donations-help', 'Dons aux personnes en difficulté', 'EXPENSE', 0
WHERE NOT EXISTS (SELECT 1 FROM category WHERE code = 'donations-help');

UPDATE category SET code = 'home-services'
WHERE parent_id IS NULL AND name = 'Emploi à domicile' AND code IS NULL
  AND NOT EXISTS (SELECT 1 FROM category WHERE code = 'home-services');
INSERT INTO category (parent_id, code, name, kind, system)
SELECT NULL, 'home-services', 'Emploi à domicile', 'EXPENSE', 0 WHERE NOT EXISTS (SELECT 1 FROM category WHERE code = 'home-services');

UPDATE category SET code = 'childcare'
WHERE parent_id IS NULL AND name = 'Garde d''enfants hors du domicile' AND code IS NULL
  AND NOT EXISTS (SELECT 1 FROM category WHERE code = 'childcare');
INSERT INTO category (parent_id, code, name, kind, system)
SELECT NULL, 'childcare', 'Garde d''enfants hors du domicile', 'EXPENSE', 0 WHERE NOT EXISTS (SELECT 1 FROM category WHERE code = 'childcare');

UPDATE category SET code = 'retirement'
WHERE parent_id = 200 AND name = 'Épargne retraite (PER)' AND code IS NULL
  AND NOT EXISTS (SELECT 1 FROM category WHERE code = 'retirement');
INSERT INTO category (parent_id, code, name, kind, system)
SELECT 200, 'retirement', 'Épargne retraite (PER)', 'SAVINGS', 0
WHERE NOT EXISTS (SELECT 1 FROM category WHERE code = 'retirement') AND EXISTS (SELECT 1 FROM category WHERE id = 200);
