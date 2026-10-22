# Budgetly

Application desktop personnelle, 100 % locale, pour savoir où va l'argent chaque mois et suivre ses investissements (comptes courants, livrets, PEA, assurance vie).

- Import de relevés PDF, parsés localement (aucun cloud, aucune télémétrie).
- Base SQLite chiffrée au repos, sauvegardes chiffrées (automatiques chaque jour, restaurables depuis l'écran de déverrouillage).
- Cœur Kotlin / Spring Boot compilé en image native GraalVM, UI React, coque desktop Tauri.

Décisions de conception : [`docs/decisions.md`](docs/decisions.md).

## Relevés pris en charge

| Établissement | Document | Ce qui est importé |
| --- | --- | --- |
| La Banque Postale | Relevé de comptes (compte courant, livrets) | Opérations, soldes, plusieurs comptes par relevé |
| EasyBourse | Relevé de portefeuille PEA | Positions, liquidités, valorisation |
| EasyVie | Relevé de contrat d'assurance vie | Supports, valorisation |
| Natixis (épargne salariale) | Avis d'opération PEE | Positions et disponibilité par date |

Votre banque n'y est pas ? C'est la contribution la plus utile : voir [Contribuer](#contribuer).

## Installation

Installeurs Windows (sans droits administrateur) et Debian/Ubuntu : page **Releases** du dépôt. Une nouvelle
version s'installe par-dessus l'ancienne ; les données restent dans le dossier de l'application. À partir de la
0.4.0, l'application propose elle-même les nouvelles versions au lancement (désactivable dans Réglages).

## Structure

| Dossier | Contenu |
| --- | --- |
| `core/` | Cœur headless : API HTTP, parsers PDF, base chiffrée. Sert aussi l'UI buildée. |
| `ui/` | Interface React (Vite + TypeScript). |
| `desktop/` | Coque Tauri : lance le cœur en sidecar sur 127.0.0.1 et affiche l'UI. |

## Développement

Prérequis : JDK 21, Node 22, Rust stable (+ dépendances système Tauri pour la coque).

```bash
# 1. Cœur (JVM). Au premier lancement, l'UI demande de créer le mot de passe maître.
#    Données dans ~/.budgetly (BUDGETLY_DATA_DIR pour changer de dossier).
cd core
./gradlew bootRun

# 2. UI en mode dev (proxy /api vers le cœur)
cd ui
npm install
npm run dev
```

Importer un relevé : onglet « Importer un relevé » de l'UI (aperçu, contrôle des soldes, puis validation).

Inspecter le texte positionné d'un relevé (rien n'est stocké) :

```bash
curl -F file=@mon-releve.pdf http://127.0.0.1:47811/api/dev/pdf-text
```

Tests :

```bash
cd core
./gradlew test         # JVM
./gradlew nativeTest   # image native (GraalVM requis)
```

Coque desktop (après `./gradlew nativeCompile` dans `core/`) : le binaire prend le suffixe de la plateforme,
les bibliothèques JDK qui l'accompagnent (`libawt`…, chargées par PDFBox) vont dans `binaries/lib/`.

```bash
mkdir -p desktop/src-tauri/binaries/lib
cp core/build/native/nativeCompile/budgetly-core \
   desktop/src-tauri/binaries/budgetly-core-$(rustc -vV | sed -n 's/host: //p')
cp core/build/native/nativeCompile/*.so desktop/src-tauri/binaries/lib/   # *.dll sous Windows
cd desktop/src-tauri
cargo run
```

Installeurs : le workflow « Paquets desktop » produit l'installeur Windows (NSIS, sans droits administrateur) et
le paquet Debian/Ubuntu, en artefacts de la CI (déclenchement manuel ou tag `v*`).

## Vie privée

- Ne jamais versionner de relevé réel : `*.pdf` est ignoré et la CI refuse tout PDF suivi.
- Les fixtures de test sont générées par le code (`PdfFixtureWriter`), avec des données inventées.
- `python tools/verifier-vie-privee.py` avant chaque commit (la CI le rejoue).
- Le cœur n'écoute que sur 127.0.0.1, refuse tout `Host` étranger et exige un cookie issu du jeton de lancement de la coque.

## Contribuer

Les contributions sont bienvenues, en particulier les **connecteurs** (un parser par établissement et type de
document). Guide : [`CONTRIBUTING.md`](CONTRIBUTING.md). Pour contribuer avec Claude, le dossier
[`.claude/`](.claude/README.md) fournit le contexte, la méthode pas à pas et les garde-fous vie privée.

## Licence

[AGPL-3.0-or-later](LICENSE). Toute version modifiée distribuée ou proposée en service doit publier son code
sous la même licence.
