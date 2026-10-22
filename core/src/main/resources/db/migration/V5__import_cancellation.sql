-- Annulation d'un import : le journal garde la ligne (date, impact) et note l'annulation.
-- L'empreinte du fichier est suffixée pour permettre de réimporter le même relevé.
ALTER TABLE statement_import ADD COLUMN cancelled_at TEXT;
