# Budgetly — Décisions actées

_Dernière mise à jour : 20/10/2026. Ce document fait référence : toute évolution d'une décision doit y être consignée (avec date et raison). Il est synchronisé avec le document `decisions.md` du projet Budgetly sur claude.ai._

## 1. Objectif
- Agréger les comptes et savoir **où va l'argent chaque mois** (flux : dépenses, catégories, budget).
- **Suivre les investissements** (patrimoine : positions, valorisation, performance).
- **Une belle interface** est un critère différenciant, pas un bonus.

## 2. Périmètre
| Enveloppe | Établissement | Document | V1 |
|---|---|---|---|
| CCP + LDDS + Livret A | La Banque Postale | Relevé de comptes mensuel (3 comptes dans un PDF) | Oui |
| PEA | EasyBourse | Évaluation de portefeuille (positions, sans transactions) | Oui |
| Assurance Vie EasyVie (CNP) | EasyBourse | Bulletin de situation annuel (pas de situation à la demande) | Oui |
| Compte courant | Boursorama | — | Après la V1 |
| PEE (épargne salariale, FCPE) | Natixis Interépargne | Avis d'opération (situation des avoirs à chaque versement) | 0.2.0 |
| Crypto | — | — | Plus tard |

## 3. Synchronisation des données
- **Import de relevés PDF**, déposés manuellement par l'utilisateur ; **exports CSV et OFX** pour les banques sans connecteur PDF (depuis la 0.5.0).
- **Un parser par établissement × type de document**, derrière une interface commune. Un PDF peut alimenter plusieurs comptes.
- Les PDF sont du texte natif : **pas d'OCR**. Extraction **avec positions** (PDFBox `TextPosition`) pour attribuer chaque montant à sa colonne.
- **Validation obligatoire de chaque import**, selon le type de document :
  - relevé de compte : solde initial + mouvements = solde final ;
  - évaluation ou bulletin de positions : somme des lignes = total (tolérance 0,01 €) ;
  - export CSV / OFX : deux soldes encadrent les opérations et doivent concorder avec elles. Ils viennent du fichier quand il les donne (colonne de solde, contrôlée **ligne par ligne** dans un ordre reconstitué pour chaque journée, car l'ordre des opérations d'une même journée varie selon les banques ; solde comptable OFX `LEDGERBAL`), sinon de l'utilisateur, tels qu'affichés par sa banque. Pas de soldes, pas d'import.
  Sinon rejet.
- **Dédoublonnage** des transactions entre relevés qui se chevauchent. Un export ne reprend pas les libellés du relevé PDF : pour un export, une opération du même compte, du même jour et du même montant déjà connue compte comme doublon (au plus autant que d'opérations connues).
- **Exports CSV** : encodage (UTF-8 sinon Windows-1252) et séparateur détectés ; correspondance des colonnes proposée d'après les en-têtes, corrigeable, mémorisée par en-tête dans la base chiffrée. **OFX** 1.x (SGML) et 2.x (XML) ; le compte est reconnu quand son IBAN contient l'identifiant OFX (banque + guichet + compte). Le fichier reste en mémoire le temps de choisir les colonnes : oublié dès que l'aperçu est prêt, au verrouillage, au plus tard après 30 minutes (purge chaque minute, comme les aperçus). Les fichiers déposés (relevés, exports) ne passent jamais par le disque : seuil multipart à 64 Mo, au-delà seules les sauvegardes chiffrées. QIF non pris en charge (pas de solde, dates ambiguës).
- **Relevés de test fictifs générés par le code** (`LbpStatementFixture`), qui reproduisent la mise en page mesurée sur un relevé réel (coordonnées, tailles, pièges) avec identité, IBAN et montants inventés. Aucune donnée dérivée d'un vrai relevé n'est versionnée. **Les PDF réels ne vont jamais dans le dépôt.**
- Les polices des relevés Banque Postale ont un encodage propre (accents et « € » mal décodés) : les parsers ne s'appuient que sur des mots ASCII, des formes (dates, montants) et des positions.
- Import en deux temps : **aperçu** (rien n'est écrit, aperçu gardé 30 min en mémoire) puis **validation** par l'utilisateur, en une transaction.
- Virement interne = libellé ou détails citant l'IBAN d'un compte suivi ; sa contrepartie (montant opposé, ±3 jours) est liée.
- AV : bulletin annuel uniquement. Les relevés fournissent les **positions** ; la valorisation intermédiaire se fait via les **valeurs liquidatives publiques par ISIN**, affichée comme **« estimée »** et recalée à chaque bulletin (mandat d'arbitrage : les parts changent en cours d'année). Saisie manuelle de snapshot en dernier recours.
- **Cours et VL** : interface `PriceProvider` avec sources en chaîne, ISIN uniquement. Google Finance exclu (plus d'API depuis 2012). Premier candidat : Yahoo Finance (non officiel) ; repli : saisie manuelle. Couverture à mesurer sur les ISIN réels au début de la phase 3.
- Virements vers des comptes non suivis (Boursorama) = **virements internes** ; versements vers AV/PEE = **épargne**, jamais des dépenses.
- Pistes écartées pour l'instant (à réévaluer plus tard) : agrégateurs DSP2 (ne couvrent pas AV/PEA ; GoCardless fermé aux nouveaux comptes), scraping local via Woob.

## 4. Vie privée (privacy first)
- Données **100 % locales**, rien ne sort de la machine.
- **Base de données chiffrée au repos** : SQLite + SQLite3MultipleCiphers (`io.github.willena:sqlite-jdbc`), schéma SQLCipher v4.
- **Aucune télémétrie**.
- Appels externes limités à la récupération des cours par **ISIN uniquement**, jamais les quantités ni les montants.
- **Seule autre sortie : la vérification de mise à jour**, faite par la coque au lancement (désactivable dans Réglages, réglage `updates.json` hors de la base car lu avant l'ouverture du coffre). Une requête GET vers `latest.json` de la dernière version GitHub, sans paramètre ni identifiant (en-têtes `User-Agent` du plugin et `Accept` seulement) ; GitHub et son CDN voient l'adresse IP et l'heure. Activée par défaut, choix du propriétaire du projet ; fichier présent mais illisible = désactivée. Installation seulement sur accord explicite, après vérification de la signature (clé publique minisign embarquée ; clé privée dans l'environnement GitHub `release`, donnée aux seuls tags `v*` poussés, et seulement au job qui signe les installeurs déjà construits, sans rien compiler ; CLI Tauri figé par `desktop/package-lock.json`, actions épinglées par commit). Windows : installation automatique (NSIS, mode passif, cœur arrêté avant) ; Linux : l'application ouvre la page de la version (paquet .deb à installer par-dessus).
- Aucun service cloud (y compris LLM) pour le parsing.
- **Mot de passe maître** : clé de la base dérivée par Argon2id (64 Mio, 3 passes) ; seuls le sel et les paramètres sont écrits (`vault.json`). La base reste verrouillée au démarrage ; toute API de données répond 423 tant que le coffre est fermé. Mot de passe perdu = données irrécupérables (assumé).
- **PDF importés jamais conservés.** Seul un journal d'import est gardé : date, utilisateur, hash du fichier, impact (comptes touchés, transactions et positions créées).
- **Accès local protégé** : le cœur refuse tout `Host` autre que `127.0.0.1` / `localhost` (DNS rebinding) et exige un cookie de session issu d'un jeton aléatoire généré par la coque à chaque lancement (un site web ouvert dans le navigateur ne peut pas appeler l'API).

## 4 bis. Catégorisation et bilan
- Catégories sur **deux niveaux** (catégorie / sous-catégorie), chacune d'une **nature** : dépense, revenu, épargne (versement vers AV, PEE…) ou « entre mes comptes ». Une sous-catégorie hérite de la nature de son parent.
- Ordre de classement d'une opération : **choix manuel** (jamais écrasé) > **virement entre mes comptes** > **règle** > **type d'opération** (frais, retraits).
- Règle = « le libellé (ou les détails) contient un motif », insensible à la casse et aux accents. Règles utilisateur prioritaires sur les règles par défaut ; à priorité égale, le motif le plus long gagne.
- Règles par défaut **génériques uniquement** (organismes nationaux : impôts, opérateurs, prêts, AV, PEE, frais). Aucune règle tirée d'un relevé personnel n'est versionnée ; les règles personnelles naissent du reclassement (« classer aussi les opérations semblables »), avec un motif proposé (le commerçant, sans préfixe ni date).
- Bilan mensuel par **date d'opération** : virements entre mes comptes exclus ; une opération non classée compte comme dépense (débit) ou revenu (crédit). Taux d'épargne = (revenus − dépenses) / revenus, versements d'épargne compris dans la part épargnée.
- Budget = montant mensuel fixe par catégorie de dépenses ; celui d'une catégorie principale cumule ceux de ses sous-catégories.

- **Charges fixes** : séries détectées automatiquement dans les relevés (même motif de libellé, montant voisin à ±35 %, une fois par mois, intervalles de 20 à 40 jours ; au moins 3 occurrences, ou 2 à montant identique). Un commerçant fréquenté plusieurs fois le même mois ne donne une série qu'à montant exact (deux abonnements du même opérateur), jamais pour des courses. Variable si les montants précédents s'écartent de plus de 10 % ; changement de montant signalé sinon. Série arrêtée au-delà de 1,6 intervalle sans occurrence, nouvelle si absente du premier mois de données et apparue dans les deux derniers. L'utilisateur peut écarter une série (`recurring_ignored`). Tout est calculé par rapport à la dernière opération connue, pas à la date du jour (les relevés arrivent en retard).
- **Fin de mois prévue** : solde des comptes courants au dernier relevé + occurrences attendues des séries actives d'ici la fin du mois + dépenses courantes estimées (moyenne journalière, hors séries, des trois mois précédents × jours restants). Alertes de budget : dépassé, en passe de l'être au rythme actuel (extrapolé après le premier cinquième du mois), ou consommé à 80 %.
- **Ventilation** : une opération peut être répartie sur plusieurs catégories (`txn_split`, somme des parts = montant, parts du même signe) ; elle compte pour chaque part dans les bilans, n'a plus de catégorie propre (`SPLIT`) et n'est jamais reclassée par une règle. **Note** libre par opération, retrouvée par la recherche, chiffrée avec la base.

## 4 ter. Patrimoine
- Relevés d'investissement = **photo des positions** à une date (pas de transactions) : PEA EasyBourse (« Évaluation de portefeuille »), assurance vie EasyVie (bulletin annuel). Contrôle : somme des lignes + liquidités = total imprimé (et somme des plus-values = total imprimé pour le PEA).
- Positions : quantités et prix en décimaux exacts (texte en base), valorisations en centimes. Un fonds en euros n'a pas d'ISIN : identifié par son nom.
- Cumul des versements : lu sur le relevé PEA ; reconstitué pour l'AV depuis la page fiscale (versements au 31/12 N-1) + versements de l'année. Plus-value = valeur − versements.
- Valeur d'une enveloppe, par ordre de préférence : **saisie manuelle** plus récente que tout, sinon **estimation** (parts du dernier relevé × dernier cours connu), sinon **valeur du relevé**. Le cours imprimé sur un relevé reste la référence pour sa date.
- Cours : Yahoo Finance (recherche par ISIN puis historique quotidien), derrière `PriceProvider`. Garde-fous : euros uniquement, et cours à ±25 % du cours du relevé autour de sa date, sinon le symbole est écarté (mauvaise part). Introuvable = revérifié après 30 jours. Déclenché uniquement à la demande (« Actualiser les cours »).
- Patrimoine dans le temps : à chaque date connue, chaque compte compte pour sa dernière valeur connue. Vue par **fin de mois** (comptes et livrets / investissements), le mois en cours aux valeurs actuelles ; un compte n'apparaît qu'à partir de son premier relevé (dix ans au plus).
- **Rendement d'une enveloppe** : taux de rendement interne (TRI) depuis le premier relevé qui imprime le cumul des versements, jusqu'à la valeur actuelle. Les versements d'une période (écart du cumul entre deux relevés) ne sont pas datés : placés au milieu de l'intervalle. Annualisé seulement sur un an ou plus, sinon rendement de la période ; rien sous 30 jours ni sans cumul des versements (PEE). Les taux sont des nombres à virgule, jamais les montants.
- **Répartition par classe d'actifs** : comptes courants, livrets et épargne, liquidités des enveloppes, monétaire (supports dont le nom contient « monétaire », « trésorerie », « money market » : faute de classification sur les relevés), fonds en euros, actions et fonds. Une enveloppe se répartit comme les supports de son dernier relevé ; une valeur saisie à la main garde ces proportions.
- **PEE Natixis** (avis d'opération d'épargne salariale, envoyé à chaque versement) : seule la page « Situation de vos avoirs » est lue (placements par date de disponibilité, valeur de part, parts, montant, total). Contrôle : somme des lignes = total = somme par date de disponibilité. Les FCPE n'ont pas d'ISIN sur l'avis : identifiés par leur nom, valeur au relevé (ou saisie manuelle), pas de cours automatique. Pas de cumul des versements sur ce document. Identifiant de l'enveloppe : numéro d'entreprise (un PEE par employeur). Répartition disponible / bloqué conservée à chaque relevé.
- `account.iban` sert d'identifiant de compte : IBAN, ou numéro de contrat pour une enveloppe.

## 4 quater. Sauvegardes, mot de passe, annulation
- **Sauvegarde** = archive `.budgetly` contenant la base **telle qu'elle est sur disque (chiffrée)**, `vault.json` (sel et paramètres Argon2id) et un en-tête sans donnée personnelle. Copie faite en tenant l'unique connexion du pool : personne n'écrit pendant la copie. Restaurer demande le mot de passe en vigueur à la création de la sauvegarde.
- Sauvegarde **automatique** à l'ouverture du coffre si la dernière date de plus de 20 h ; les 7 dernières sont gardées. Sauvegarde **de sécurité** avant tout changement de mot de passe. Sauvegardes manuelles jamais supprimées automatiquement. Dossier : `<données>/sauvegardes`.
- **Restauration** uniquement coffre verrouillé ou non créé : la sauvegarde est vérifiée avec le mot de passe avant tout changement, les données en place sont mises de côté (`avant-restauration-<date>`), et remises si l'ouverture (migrations comprises) échoue.
- **Changement de mot de passe** : `PRAGMA rekey` avec une clé dérivée d'un nouveau sel. Ordre résistant aux pannes : nouveaux paramètres écrits dans `vault.next.json` → rechiffrement → promotion. Au déverrouillage, si l'ancien mot de passe échoue et que `vault.next.json` existe, il est essayé et promu.
- **Annulation d'import** : seul le dernier import de chacun des comptes touchés est annulable (un import plus récent a pu écarter comme doublons des opérations de celui-ci). Opérations, soldes, positions et valorisations de l'import sont supprimés ; un compte resté vide disparaît. Le journal garde la ligne, marquée annulée, et l'empreinte du fichier est suffixée pour permettre de le réimporter.
- **Export CSV** : séparateur « ; », virgule décimale, UTF-8 avec BOM (Excel), cellules commençant par `=`, `+` ou `@` neutralisées. Le fichier exporté n'est pas chiffré, l'interface le dit.
- **Thème** clair / sombre / système, mémorisé dans le navigateur (préférence d'affichage, pas une donnée).
- **Verrouillage automatique** côté cœur après une période sans activité (5, 15 par défaut, 30, 60 min ou jamais ; réglage dans la base chiffrée). L'activité = toute requête de données + un signal de l'interface à la frappe ou au clic (au plus un par minute) ; l'état du coffre, interrogé toutes les 20 s par l'interface, n'en est pas. Au verrouillage, les aperçus d'import gardés en mémoire sont oubliés.
- **Import de plusieurs relevés** : aperçus un par un, puis validation dans l'ordre chronologique (début de période), chaque import restant atomique ; doublons et virements internes sont recalculés à la validation, comme pour un import isolé.
- **Aide au classement** : les opérations non classées sont regroupées par motif proposé (libellé normalisé sans préfixe technique ni date), les plus fréquentes d'abord, pour créer une règle en un clic ; reclassement manuel en masse (2 000 opérations au plus par requête).

## 5. Architecture
- **Cœur headless** : API HTTP + parsers PDF + base de données. Toute la logique métier y vit.
- **UI web (React)** qui ne communique qu'avec l'API. **L'UI buildée est servie par le cœur** : même binaire en mode desktop et en mode LAN, pas de CORS.
- **Mode desktop (V1)** : coque **Tauri** qui lance le cœur en sidecar sur **127.0.0.1 uniquement**, attend qu'il écoute, puis navigue vers l'UI servie par le cœur.
- **Mode réseau local (plus tard)** : même cœur déployé en service (NAS, mini-PC, Docker), accès navigateur. Un seul code, seule la configuration change.
- Modèle de données conçu pour ne pas figer le mono-utilisateur.
- Persistance : **Spring JDBC (`JdbcClient`) + Flyway**, pas de JPA (plus léger, plus simple en natif).

## 6. Stack technique
- Cœur : **Kotlin 1.9 + Spring Boot 3.5 + PDFBox 3**, Java 21.
- Packaging : **image native GraalVM** (pas de JVM embarquée dans l'app desktop).
- UI : **React + Vite + TypeScript**.
- Desktop : **Tauri 2**.
- Règle : **build natif GraalVM dans la CI dès la phase 0**, avec tests de parsing PDF, de chiffrement et d'API exécutés en natif. Dev quotidien en JVM classique.
- **Coût de la CI** (dépôt public, minutes gratuites) : JVM, UI et natif tournent sur chaque PR et sur `main`. Les paquets ne sont jamais construits automatiquement sur une PR (étiquette `paquets`) ; lancement manuel limité à Windows par défaut, sans retraçage JVM sauf demande. Un merge dont le titre contient `[skip ci]` ne relance rien.
- **Paquets** (workflow « Paquets desktop », manuel ou tag `v*`) : installeur Windows NSIS par utilisateur (sans droits administrateur), paquet `.deb` pour Linux. Chaque OS trace ses propres métadonnées natives (bibliothèque SQLite et JNI AWT propres à la plateforme) puis compile son image. AppImage écartée pour l’instant (linuxdeploy échoue en CI, cause non investiguée) ; macOS hors périmètre tant qu'aucun Mac n'est en jeu.
- Bibliothèques JDK de l'image native (`libawt`…, `awt.dll`…, chargées par PDFBox) livrées dans `lib/` à côté des ressources de l'application. Mesuré en CI : Linux les trouve via `-Djava.library.path` ou `LD_LIBRARY_PATH`, Windows uniquement via `PATH` (ou à côté de l'exécutable) ; sans indication, l'extraction PDF échoue. La coque passe les deux.
- **Métadonnées natives versionnées** (`META-INF/native-image/dev.budgetly/core`) : union des traces de l'agent sous Linux et sous Windows, triée pour des diffs lisibles. Les images natives partent de ce jeu (plus de dépendance au job JVM) ; chaque CI retrace sous l'agent et signale en avertissement ce qui manquerait. Régénération : étiquette `metadonnees-natives` sur une PR (export des traces en commentaire), puis `native_metadata.py merge`.
- Embarqués en plus : profils de couleur AWT (`sun/java2d/cmm/profiles/*.pf`) et tous les jeux de caractères (`-H:+AddAllCharsets`), manques révélés en natif par un test de PDF à police TrueType intégrée et couleur ICC.
- Métadonnées tracées nettoyées avant compilation : l'agent enregistre les fichiers temporaires des tests comme ressources, ce qui casse la compilation sous Windows (motif invalide).

## 7. Exigences du futur mode LAN
- Authentification obligatoire dès qu'on écoute hors localhost.
- HTTPS avec certificat local (mkcert ou CA perso).
- **Jamais exposé sur Internet** ; accès distant uniquement via VPN (WireGuard / Tailscale).

## 8. Dette provisoire assumée
- Port fixe 47811.
- Icônes provisoires.
- Installeurs non signés par un certificat de code : Windows SmartScreen avertira au premier lancement (les mises à jour, elles, sont signées par la clé de l'application).
- Téléchargements (sauvegarde, CSV) depuis la fenêtre desktop : natifs sous Windows (WebView2), non vérifiés sous Linux (WebKitGTK) ; les sauvegardes restent de toute façon dans le dossier de données affiché.

## 9. Questions ouvertes
- Couverture réelle de Yahoo Finance sur les ISIN détenus : affichée par l'application à chaque « Actualiser les cours » ; envisager une seconde source si trop de supports restent introuvables (fonds non cotés en bourse surtout).

## 9 bis. Feuille de route
Suivie dans les issues GitHub, par ordre de priorité :
1. Import CSV / OFX générique (#3) — condition de l'adoption par d'autres banques que celles couvertes.
2. Patrimoine dans le temps et performance réelle (#4).
3. Repères fiscaux et échéances (#5).
4. Objectifs d'épargne et taux d'épargne (#6).
5. Bilan annuel et comparaisons (#7).
6. Suivi des crédits (#8).
7. Plus tard : mode réseau local, foyer et consultation mobile (#9).

## 10. Journal des changements
- 23/10/2026 — Version 0.6.0. Patrimoine : fins de mois, rendement des enveloppes (TRI, versements déduits) et répartition par classe d'actifs (#4).
- 23/10/2026 — Version 0.5.0. Import des exports CSV et OFX (#3) : correspondance des colonnes, soldes de contrôle obligatoires, doublons par date et montant face aux relevés PDF.
- 22/10/2026 — Feuille de route publiée en issues (#3 à #9).
- 22/10/2026 — Publication : la première tentative de la 0.4.0 s'est arrêtée à la signature (installeurs téléchargés dans des sous-dossiers) ; artefacts mis à plat avant signature et publication, version et tag recréés.
- 22/10/2026 — Version 0.4.0. Mise à jour automatique, désormais possible avec le dépôt public : vérification au démarrage (désactivable), installeurs signés, publication des versions sur GitHub avec `latest.json` depuis un tag `v*`. Remplace la décision de la 0.2.0.
- 22/10/2026 — Projet ouvert : dépôt public `budgetly-app` repart d'un commit unique (historique privé conservé à part, sans relevé mais avec des métadonnées personnelles), licence AGPL-3.0-or-later. Guide de contribution, dossier `.claude/` (compétences connecteur et vie privée, agent relecteur), outils d'extraction et d'anonymisation de mise en page, contrôle vie privée scripté rejoué en CI. Jobs natifs de nouveau sur chaque PR (minutes gratuites en public).
- 22/10/2026 — CI économe en minutes (quota du plan gratuit épuisé par les paquets Windows) : natif après merge, paquets à la demande.
- 22/10/2026 — Version 0.3.0. Où va l'argent : charges fixes et abonnements (détection, alertes de nouveau prélèvement et de changement de montant), fin de mois prévue et alertes de budget, ventilation et notes. Migration V8.
- 22/10/2026 — Version 0.2.0. Parser PEE Natixis (avis d'opération), validé hors dépôt sur un avis réel (3 lignes, total et disponibilité exacts). Mise à jour automatique écartée : le dépôt est privé et l'appli ne doit embarquer aucun jeton ; installation manuelle par-dessus (données conservées dans le dossier de l'application).
- 21/10/2026 — Améliorations : import de plusieurs relevés, règles suggérées et reclassement en masse, verrouillage automatique, métadonnées natives versionnées (Linux + Windows) avec contrôle de dérive ; profils de couleur et jeux de caractères ajoutés à l'image.
- 21/10/2026 — Phase 4 : sauvegardes chiffrées (quotidienne, manuelle, de sécurité) et restauration vérifiée, changement de mot de passe par `PRAGMA rekey` résistant aux pannes, annulation du dernier import d'un compte, export CSV, écran Réglages, thème clair/sombre, alerte de relevé manquant. Paquets Windows (NSIS) et Linux (deb) construits en CI ; emplacement des bibliothèques natives mesuré par plateforme.
- 21/10/2026 — Phase 3 : parsers PEA EasyBourse et EasyVie validés hors dépôt sur les relevés réels (7 et 19 lignes, totaux exacts), positions et valorisations, cours Yahoo par ISIN avec garde-fous, estimation, saisie manuelle, écran Patrimoine. Mesure de couverture déplacée dans l'application (pas de requête de cours depuis l'environnement de développement).
- 21/10/2026 — Phase 2 : catégories à quatre natures, moteur de règles, reclassement avec création de règle, budgets, bilan mensuel et historique 12 mois. Mesure sur un relevé réel : 24 % des opérations classées par les seules règles par défaut (le reste = commerçants locaux), 82 % attendus après une douzaine de règles personnelles.
- 21/10/2026 — CI : traçage natif unique dans le job JVM, images de tests (`-Ob`) et d'application construites en parallèle (≈ 25 → 14 min).
- 20/10/2026 — Phase 1 : coffre Argon2id (fin de la clé par variable d'environnement), parser Banque Postale validé sur un relevé réel (3 comptes, soldes et totaux exacts), import en deux temps, doublons par empreinte, virements internes. Fixtures : générateur fidèle à la mise en page plutôt que script d'anonymisation (aucune donnée réelle dérivée dans le dépôt).
- 20/10/2026 — Phase 0 validée en CI (JVM, natif, Tauri). PDFBox impose AWT en natif : métadonnées tracées par l'agent GraalVM, libs AWT livrées avec le binaire.
- 20/10/2026 — Phase 0 : UI servie par le cœur ; jeton de lancement + contrôle du Host ; SQLite3MultipleCiphers pour le chiffrement ; Spring JDBC + Flyway ; clé de base provisoire par variable d'environnement.
- 20/10/2026 — UI : React retenu (stack la mieux connue).
- 20/10/2026 — PDF jamais conservés, journal d'import uniquement (minimiser la surface de données).
- 20/10/2026 — Pas de situation AV à la demande chez EasyBourse : valorisation estimée entre bulletins annuels.
- 20/10/2026 — Source des cours : Google Finance écarté (pas d'API) ; Yahoo Finance en premier candidat derrière une interface `PriceProvider`.
- 20/10/2026 — Boursorama et Natixis (PEE) sortis de la V1 (jugés non prioritaires). V1 recentrée sur La Banque Postale et EasyBourse (PEA + AV).
- 20/10/2026 — Analyse de relevés réels : double règle de validation, extraction positionnelle, valorisation AV « estimée », fixtures anonymisées obligatoires.
