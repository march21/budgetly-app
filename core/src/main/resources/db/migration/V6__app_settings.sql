-- Réglages de l'application stockés dans la base chiffrée (clé → valeur texte).
CREATE TABLE app_setting (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
