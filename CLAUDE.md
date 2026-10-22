# Budgetly — consignes pour Claude

Lire `docs/decisions.md` avant toute modification structurante : il fait référence. Toute décision nouvelle ou modifiée y est consignée (section « Journal des changements », datée, avec la raison).

## Règles non négociables
- Vie privée : rien ne sort de la machine, sauf des requêtes de cours identifiées par ISIN seul et la vérification de mise à jour de la coque (désactivable, sans aucun paramètre). Pas de télémétrie, pas de service cloud ni LLM pour le parsing.
- Jamais de PDF réel dans le dépôt ni dans les logs. Fixtures générées par le code, avec des données fictives.
- Jamais de montant, libellé, IBAN ou numéro de contrat dans les logs.
- Montants en centimes (`Long`), jamais en flottants.
- Tout import est atomique et validé (solde ou somme des lignes) avant écriture.
- Ce qui tourne en JVM doit tourner en natif : toute nouvelle dépendance ou réflexion est couverte par un test exécuté par `nativeTest`.

## Conventions
- Cœur : Kotlin, Spring Boot 3.5, Spring JDBC (`JdbcClient`) + Flyway, pas de JPA.
- Un parser = un établissement × un type de document, derrière une interface commune.
- UI : React + TypeScript ; elle ne parle qu'à l'API du cœur.
- Langue : code et identifiants en anglais, messages utilisateur et documentation en français.

## Contribuer avec Claude
- Méthode, compétences et agents : `.claude/README.md`.
- Nouveau connecteur : compétence `nouveau-connecteur`. Ne jamais demander ni lire un relevé réel ou son texte brut : uniquement le dump produit par `tools/pdf/anonymiser.py`, relu par la personne.
- Avant commit ou PR : compétence `verifier-vie-privee` (`python tools/verifier-vie-privee.py`), puis l'agent `relecteur-vie-privee` sur le diff.
- Contributions sous AGPL-3.0-or-later ; règles humaines dans `CONTRIBUTING.md`.
