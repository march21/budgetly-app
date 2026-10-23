-- Date d'ouverture d'une enveloppe (premier versement), saisie par l'utilisateur : point de départ des
-- échéances fiscales (5 ans du PEA, 8 ans de l'assurance vie). Absente des relevés lus.
ALTER TABLE account ADD COLUMN opened_on TEXT;
