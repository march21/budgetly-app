---
name: verifier-vie-privee
description: Contrôle vie privée avant un commit ou une PR sur Budgetly — aucun relevé, aucune donnée réelle, rien de sensible dans les logs, aucune sortie réseau nouvelle. À utiliser avant de committer, avant d'ouvrir une PR, et dès qu'un changement touche aux imports, aux logs, au réseau ou aux fixtures.
---

# Vérifier la vie privée avant commit

Budgetly traite les relevés bancaires de ses utilisateurs. Une fuite dans le dépôt, les logs ou le réseau est
la pire erreur possible : elle est souvent irréversible (historique Git public, logs de CI conservés).

## 1. Contrôle automatique

```bash
python tools/verifier-vie-privee.py           # fichiers suivis + modifications en cours
python tools/verifier-vie-privee.py --staged  # seulement ce qui va être commité
```

Le script refuse : PDF, bases et exports (`*.pdf`, `*.db`, `*.tsv`, `*.budgetly`, `*.csv` hors tests), et
bloque les IBAN valides (clé mod 97) hors des tests et signale les adresses e-mail. Chaque
alerte se lit : un faux positif s'explique dans la PR, un vrai positif se retire **avant** le commit.

## 2. Relecture (ce qu'un script ne voit pas)

Parcourez le diff (`git diff --staged`) et vérifiez :

- **Fixtures** : identités, adresses, IBAN, numéros de contrat, ISIN, libellés de commerçants et montants sont
  inventés, pas « légèrement modifiés » depuis un vrai relevé. Un ISIN fictif ressemble à `FR0000000011`.
- **Logs et exceptions** : aucun montant, libellé, IBAN, numéro de contrat, nom de fichier importé. On logue des
  compteurs et des types d'erreur (`e.javaClass.simpleName`), jamais `e.message` venant d'un relevé.
- **Réseau** : aucune nouvelle sortie. La seule permise : les cours, identifiés par ISIN seul (voir
  `portfolio/PriceProvider.kt`). Toute autre sortie est une décision à consigner et à justifier dans
  `docs/decisions.md`, avant le code.
- **Stockage** : toute donnée personnelle va dans la base chiffrée, jamais dans un fichier à côté, jamais dans
  le `localStorage` de l'interface (réservé aux préférences d'affichage).
- **Messages et documentation** : pas de valeur réelle dans les commits, la PR, les commentaires de code ou les
  décisions (« validé sur 3 relevés réels » oui, « total 2 744,71 € » non).
- **Montants** : centimes en `Long`, jamais de flottant.

## 3. En cas de doute

Ne committez pas. Signalez le point dans la conversation et proposez la version sans donnée. Si une donnée
réelle a déjà été poussée, le dire tout de suite : il faut réécrire l'historique (et, si le dépôt est public,
considérer la donnée comme exposée).
