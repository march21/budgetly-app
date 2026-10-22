---
name: nouveau-connecteur
description: Ajouter la prise en charge d'un nouveau relevé PDF (banque, livret, PEA, assurance vie, épargne salariale) dans Budgetly — parser, relevé fictif de test, branchement, documentation. À utiliser dès qu'on parle d'un nouvel établissement ou d'un nouveau type de document.
---

# Ajouter un connecteur (parser de relevé)

Un connecteur = **un établissement × un type de document**, derrière une interface commune. Aucun réseau,
aucun service externe : tout le parsing se fait localement, à partir du texte positionné du PDF.

## 0. Garde-fous (à respecter à chaque étape)

- **Ne demandez pas et ne lisez pas de relevé réel** (PDF, capture, texte extrait brut). Si l'utilisateur en
  propose un, expliquez pourquoi c'est à éviter et proposez la méthode d'anonymisation ci-dessous.
- Aucune donnée réelle dans le dépôt : ni fichier, ni nom, ni IBAN, ni numéro de contrat, ni ISIN détenu, ni
  libellé de commerçant tiré d'un relevé, ni montant. Les tests utilisent un relevé **généré par le code**.
- Jamais de montant, libellé, IBAN ou numéro de contrat dans les logs ni dans les messages d'exception.
- Montants en centimes (`Long`), quantités et cours en `BigDecimal`. Jamais de `Double` pour de l'argent.

## 1. Obtenir la mise en page, sans les données

Demandez à l'utilisateur, chez lui :

```bash
pip install pdfminer.six
python tools/pdf/lignes.py releve.pdf --tsv lignes.tsv
python tools/pdf/anonymiser.py lignes.tsv anonyme.tsv --garder tools/pdf/mots-structure.txt
```

puis de **relire** `anonyme.tsv` et de vous le transmettre (ou seulement les pages utiles). Chaque ligne :
`page`, `y` (depuis le haut), puis `mot␟x0␟x1`. Les chiffres sont brouillés : la structure (colonnes, bords
droits des montants, lignes de libellé sur plusieurs lignes, en-têtes, totaux) est ce qui compte.

Relevez : le texte qui identifie le document (pour `supports`), la période ou la date, l'identifiant du compte
ou du contrat, les colonnes et leur **bord droit** (les montants sont alignés à droite), comment débit et
crédit se distinguent, les lignes de total imprimées (elles servent au contrôle), les pages à ignorer.

## 2. Choisir le contrat

| Document | Interface | Modèle produit | Contrôle obligatoire |
| --- | --- | --- | --- |
| Relevé de compte (mouvements) | `statement/StatementParser` | `ParsedStatement` → comptes, soldes, opérations | solde initial + mouvements = solde final, pour chaque compte |
| Photo d'un portefeuille (PEA, AV, PEE…) | `statement/portfolio/PortfolioParser` | `ParsedPortfolio` → positions, liquidités, total | somme des lignes + liquidités = total imprimé |

Exemples à suivre : `statement/lbp/` (relevé de comptes, plusieurs comptes par PDF), `statement/easybourse/`
(PEA et assurance vie), `statement/natixis/` (épargne salariale, disponibilité par date). Outils partagés :
`FrenchAmounts`, `portfolio/PortfolioText` (`numberGroups`, `byColumn`, `centsOf`, `frenchDate`, `normalize`).

## 3. Écrire, dans cet ordre

1. **Le relevé fictif** `core/src/test/.../statement/<etablissement>/<Nom>Fixture(s).kt`, avec
   `PdfFixtureWriter` : mêmes coordonnées que la mise en page relevée, identité, numéros et montants **inventés**
   (IBAN au bon format mais faux, ISIN fictifs du type `FR0000000011`). Les totaux du fictif doivent boucler,
   et une variante avec un total faussé doit exister pour tester le rejet.
2. **Le parser** `core/src/main/.../statement/<etablissement>/<Nom>Parser.kt` : `id` stable du type
   `etablissement-type-document`, `supports` strict (deux marqueurs du document au moins, pour ne pas voler le
   document d'un autre parser), `parse` qui lève `StatementParseException` avec un message sans donnée.
   Lecture par **positions** (colonnes par bord droit), jamais par regex sur une ligne entière.
3. **Les tests** `<Nom>ParserTest` : reconnaissance (et refus des autres documents, et réciproquement pour les
   parsers existants), extraction exacte, total qui boucle, total faussé détecté. Si le document crée un
   nouveau type de compte ou de donnée, un test de bout en bout par l'API (voir `portfolio/PeeFlowTest`).
4. **Le branchement** : un `@Bean` dans `config/AppConfig.kt`. Nouveau type d'enveloppe : `AccountType`,
   `ledger/Ledger.kt` (ordre, masquage), `portfolio/PortfolioService.kt`, `ui/src/api.ts`.
5. **La documentation** : tableau « Périmètre » et section dédiée de `docs/decisions.md`, entrée datée du
   journal ; liste des documents pris en charge dans `ui/src/ImportsPage.tsx` et `README.md`.

## 4. Vérifier

- `cd core && ./gradlew test` (JVM). Les tests natifs (`nativeTest`) tournent en CI : un nouveau chemin de
  PDFBox (police intégrée, profil couleur) peut manquer à l'image native ; la CI le signale.
- L'utilisateur lance le parser sur **son** relevé, en local, et confirme que les contrôles passent (nombre de
  lignes, totaux), sans vous communiquer les valeurs.
- Avant de proposer le commit : compétence `verifier-vie-privee`, puis l'agent `relecteur-vie-privee` sur le diff.

## 5. Finir

Commit et PR en français, une PR par connecteur, checklist du modèle de PR cochée. Dans la description :
l'établissement, le type de document, ce qui est lu, ce qui ne l'est pas, et la façon dont il a été vérifié
(« validé localement sur N relevés réels, totaux exacts ») — jamais de valeurs.
