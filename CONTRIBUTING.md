# Contribuer à Budgetly

Merci de votre intérêt ! Budgetly lit des relevés bancaires : la première règle de toute contribution est de
**ne jamais exposer de donnée réelle**, ni la vôtre ni celle de quelqu'un d'autre.

## Règles du projet

Elles sont détaillées dans [`CLAUDE.md`](CLAUDE.md) (lu aussi par les assistants IA) et
[`docs/decisions.md`](docs/decisions.md). En bref :

- Rien ne sort de la machine, sauf les requêtes de cours identifiées par ISIN seul et la vérification de mise à
  jour (désactivable). Pas de télémétrie, pas de service cloud, pas d'IA pour le parsing.
- Jamais de relevé réel dans le dépôt, les issues, les PR ou les logs. Les tests utilisent des relevés
  **générés par le code** avec des données inventées.
- Jamais de montant, libellé, IBAN ou numéro de contrat dans les logs.
- Montants en centimes (`Long`), jamais en flottants.
- Tout import est atomique et validé (soldes ou somme des lignes) avant écriture.
- Ce qui tourne en JVM doit tourner en image native GraalVM (tests `nativeTest` en CI).
- Code et identifiants en anglais ; messages, documentation, commits et PR en français.

## Ajouter un connecteur

Un connecteur = un établissement × un type de document. Étapes :

1. **Relever la mise en page, en local.**
   ```bash
   pip install pdfminer.six
   python tools/pdf/lignes.py releve.pdf --tsv lignes.tsv
   ```
   Pour partager la mise en page dans une issue (ou avec un assistant IA), anonymisez d'abord, puis relisez :
   ```bash
   python tools/pdf/anonymiser.py lignes.tsv anonyme.tsv --garder tools/pdf/mots-structure.txt
   ```
   Chiffres et mots sont remplacés, positions conservées. Les totaux ne bouclent plus : c'est normal.
2. **Écrire un relevé fictif** avec `PdfFixtureWriter` (voir `LbpStatementFixture`, `EasyBourseFixtures`,
   `NatixisFixtures`) : même mise en page, données inventées, totaux qui bouclent, et une variante faussée.
3. **Écrire le parser** derrière `StatementParser` (relevé de mouvements) ou `PortfolioParser` (photo d'un
   portefeuille), lecture par positions de colonnes, `supports` strict, exceptions sans donnée.
4. **Tester** : reconnaissance et refus, extraction exacte, contrôle des totaux, rejet du relevé faussé.
5. **Brancher** le parser (`@Bean` dans `config/AppConfig.kt`) et **documenter** (`docs/decisions.md` :
   périmètre et journal ; liste des relevés de `README.md` et de l'écran d'import).
6. **Valider sur votre vrai relevé, en local** : import par l'application, contrôles verts. Dites-le dans la PR
   (« validé sur 2 relevés réels ») sans jamais donner de valeur.

Le pas à pas détaillé, avec les interfaces et les pièges, est dans
[`.claude/skills/nouveau-connecteur/SKILL.md`](.claude/skills/nouveau-connecteur/SKILL.md) : il sert aussi bien à
un humain qu'à Claude.

## Avec Claude

Le dépôt est prêt pour [Claude Code](https://claude.com/claude-code) : voir [`.claude/README.md`](.claude/README.md).
Ne lui donnez jamais un relevé réel ni son texte brut : seulement le dump anonymisé et relu.

## Avant d'ouvrir une PR

```bash
python tools/verifier-vie-privee.py --staged   # aucun relevé, aucune base, aucun IBAN réel
cd core && ./gradlew test                       # tests JVM
cd ui && npm run build                          # interface
```

La CI rejoue ces contrôles et les tests en image native. Remplissez la checklist du modèle de PR.

## Licence

En contribuant, vous acceptez que votre contribution soit publiée sous [AGPL-3.0-or-later](LICENSE).
