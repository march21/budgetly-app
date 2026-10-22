# Contribuer avec Claude

Ce dossier donne à Claude (Claude Code, ou l'application Claude reliée à votre copie du dépôt) le contexte, la
méthode et les garde-fous du projet. Ouvrez le dépôt avec Claude : `CLAUDE.md` est lu automatiquement, et les
compétences ci-dessous se déclenchent quand la tâche s'y prête (ou en les nommant : « /nouveau-connecteur »).

| Élément | Rôle |
| --- | --- |
| [`../CLAUDE.md`](../CLAUDE.md) | Règles non négociables (vie privée, montants, atomicité, natif) et conventions. Lu à chaque session. |
| [`skills/nouveau-connecteur/`](skills/nouveau-connecteur/SKILL.md) | Ajouter la prise en charge d'un relevé (banque, assurance vie, épargne salariale…) de bout en bout. |
| [`skills/verifier-vie-privee/`](skills/verifier-vie-privee/SKILL.md) | Contrôle avant commit et avant PR : aucun relevé, aucune donnée réelle, rien dans les logs. |
| [`agents/relecteur-vie-privee.md`](agents/relecteur-vie-privee.md) | Relecteur indépendant d'un diff, sous l'angle vie privée, à lancer avant d'ouvrir la PR. |
| [`../docs/decisions.md`](../docs/decisions.md) | Décisions de conception et leur journal : la référence avant toute modification structurante. |
| [`../tools/pdf/`](../tools/pdf) | Outils locaux : texte positionné d'un PDF, anonymisation de ce texte. |

## Avant de commencer : vos relevés et l'IA

Un assistant IA envoie au modèle ce qu'il lit. **Ne donnez jamais à Claude un relevé réel ni le texte extrait d'un
relevé réel.** La méthode du projet :

1. Vous extrayez le texte positionné **chez vous** : `python tools/pdf/lignes.py releve.pdf --tsv lignes.tsv`.
2. Vous l'anonymisez : `python tools/pdf/anonymiser.py lignes.tsv anonyme.tsv --garder tools/pdf/mots-structure.txt`
   (chiffres et mots remplacés, mise en page conservée), **vous relisez le résultat**, puis seulement vous le
   montrez à Claude.
3. Claude écrit le parser et un générateur de relevé fictif à partir de cette mise en page.
4. Vous vérifiez le parser sur votre vrai relevé, en local (les totaux doivent boucler), sans rien partager.

Rien de tout cela n'entre dans le dépôt : ni PDF, ni TSV, ni capture. La CI refuse tout PDF versionné.

## Ce que l'on attend d'une contribution

- Une PR = un sujet (un connecteur, une fonctionnalité, une correction).
- Tests JVM verts (`./gradlew test`) ; les tests natifs tournent en CI.
- Décision nouvelle ou modifiée consignée dans `docs/decisions.md` (section « Journal des changements », datée).
- Code et identifiants en anglais ; messages utilisateur, documentation et commits en français.
- La checklist du modèle de PR cochée honnêtement.
