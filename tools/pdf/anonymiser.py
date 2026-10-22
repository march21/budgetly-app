"""Anonymise un fichier TSV produit par `lignes.py --tsv` en gardant la mise en page.

- Chaque chiffre est remplacé par un chiffre pseudo-aléatoire (le format reste : « 1 234,56 », « 06/10/2026 »,
  longueur des numéros), de façon cohérente : un même mot donne toujours le même résultat dans le fichier.
- Les mots en lettres sont remplacés par des lettres de même longueur et de même casse, SAUF ceux de la liste
  blanche (`--garder`, mots de structure du relevé : « Solde », « Date », « TOTAL »…), qui restent lisibles.
- Positions, tailles et polices sont conservées : c'est tout ce qu'il faut pour écrire un parser.

    python tools/pdf/anonymiser.py lignes.tsv anonyme.tsv --garder mots-structure.txt

Les totaux ne bouclent plus après anonymisation : vérifiez le parser sur le vrai relevé, chez vous, et ne
partagez (avec une personne ou un assistant IA) que la version anonymisée. Relisez-la avant de la partager :
un outil automatique peut laisser passer un nom ou un numéro dans un format inattendu.
"""
import argparse
import hashlib
import random
import re


def _graine(secret: str, mot: str) -> random.Random:
    return random.Random(hashlib.sha256((secret + "\x00" + mot).encode()).digest())


def anonymiser_mot(mot: str, garder: set, secret: str) -> str:
    if mot.lower().strip(".:,;()*") in garder:
        return mot
    rnd = _graine(secret, mot)
    out = []
    for c in mot:
        if c.isdigit():
            out.append(str(rnd.randrange(10)))
        elif c.isalpha():
            lettre = chr(rnd.randrange(26) + ord("a"))
            out.append(lettre.upper() if c.isupper() else lettre)
        else:
            out.append(c)
    return "".join(out)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("entree")
    parser.add_argument("sortie")
    parser.add_argument("--garder", help="fichier texte : un mot de structure à garder lisible par ligne")
    parser.add_argument("--secret", default=None, help="graine (par défaut aléatoire : deux passages diffèrent)")
    args = parser.parse_args()
    garder = set()
    if args.garder:
        with open(args.garder, encoding="utf-8") as f:
            garder = {l.strip().lower() for l in f if l.strip()}
    secret = args.secret or hashlib.sha256(random.randbytes(32)).hexdigest()
    with open(args.entree, encoding="utf-8") as f, open(args.sortie, "w", encoding="utf-8") as out:
        for ligne in f:
            colonnes = ligne.rstrip("\n").split("\t")
            mots = []
            for col in colonnes[2:]:
                texte, x0, x1 = col.split("\x1f")
                mots.append(f"{anonymiser_mot(texte, garder, secret)}\x1f{x0}\x1f{x1}")
            out.write("\t".join(colonnes[:2] + mots) + "\n")


if __name__ == "__main__":
    main()
