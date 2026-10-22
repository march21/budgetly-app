## Objet

<!-- Ce que change la PR, et pourquoi. Pour un connecteur : établissement, type de document, ce qui est lu. -->

## Vérification

<!-- Comment c'est vérifié. Pour un connecteur : « validé localement sur N relevés réels, contrôles verts ».
     Jamais de montant, libellé, IBAN, numéro de contrat, nom ou capture d'un relevé réel. -->

## Checklist vie privée

- [ ] Aucun relevé réel, ni dump, ni capture dans le diff, la description ou les commentaires.
- [ ] Fixtures générées par le code, avec des données inventées (pas « modifiées » depuis un vrai relevé).
- [ ] Aucun montant, libellé, IBAN ou numéro de contrat dans les logs ou les messages d'exception.
- [ ] Aucune nouvelle sortie réseau (sinon : décision justifiée dans `docs/decisions.md`).
- [ ] `python tools/verifier-vie-privee.py` passe.

## Checklist technique

- [ ] Montants en centimes (`Long`).
- [ ] Import atomique et validé (soldes ou somme des lignes) avant écriture.
- [ ] Tests ajoutés ; `./gradlew test` passe (le natif tourne en CI).
- [ ] `docs/decisions.md` mis à jour si une décision est nouvelle ou modifiée.
