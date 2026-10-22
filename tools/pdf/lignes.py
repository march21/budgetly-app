"""Texte positionné d'un relevé PDF, ligne par ligne, regroupé comme le fait le cœur (PdfTextExtractor).

Sert à concevoir un parser : chaque mot avec son abscisse de début et de fin, chaque ligne avec sa position
verticale (depuis le haut de la page), sa taille et sa police. Tout reste sur votre machine.

    pip install pdfminer.six
    python tools/pdf/lignes.py mon-releve.pdf                   # lecture
    python tools/pdf/lignes.py mon-releve.pdf --tsv lignes.tsv  # format échangeable (anonymisable)

Ne versionnez jamais le PDF ni ce que cet outil en tire : c'est votre relevé, en clair.
"""
import argparse
import sys

from pdfminer.high_level import extract_pages
from pdfminer.layout import LTChar


def _chars(obj):
    if isinstance(obj, LTChar):
        yield obj
    elif hasattr(obj, "__iter__"):
        for child in obj:
            yield from _chars(child)


def lignes(path):
    """Lignes du document : {page, y, taille, police, mots: [(texte, x0, x1)]}."""
    out = []
    for numero, page in enumerate(extract_pages(path), 1):
        hauteur = page.height
        glyphes = [(c.x0, c.x1, hauteur - c.y0 - c.size * 0.2, c.get_text(), c.size, c.fontname) for c in _chars(page)]
        glyphes = sorted((g for g in glyphes if g[3].strip()), key=lambda g: g[2])
        rangees = []
        for g in glyphes:
            if rangees and abs(rangees[-1][0][2] - g[2]) <= 2.0:
                rangees[-1].append(g)
            else:
                rangees.append([g])
        for rangee in rangees:
            rangee.sort(key=lambda g: g[0])
            mots, courant, debut, fin = [], "", 0.0, 0.0
            for g in rangee:
                ecart = max(g[4] * 0.278 * 0.5, 0.8)
                if courant and g[0] - fin > ecart:
                    mots.append((courant, round(debut, 1), round(fin, 1)))
                    courant = ""
                if not courant:
                    debut = g[0]
                courant += g[3]
                fin = g[1]
            if courant:
                mots.append((courant, round(debut, 1), round(fin, 1)))
            out.append({
                "page": numero,
                "y": round(rangee[0][2], 1),
                "taille": round(rangee[0][4], 1),
                "police": rangee[0][5].split("+")[-1],
                "mots": mots,
            })
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("pdf")
    parser.add_argument("--tsv", help="écrire les lignes au format TSV (page, y, puis mot␟x0␟x1 par colonne)")
    args = parser.parse_args()
    data = lignes(args.pdf)
    if args.tsv:
        with open(args.tsv, "w", encoding="utf-8") as f:
            for l in data:
                f.write(f"{l['page']}\t{l['y']}\t" + "\t".join(f"{t}\x1f{a}\x1f{b}" for t, a, b in l["mots"]) + "\n")
        print(f"{len(data)} lignes écrites dans {args.tsv}", file=sys.stderr)
        return
    for l in data:
        mots = "  ".join(f"{t}@{a:.0f}-{b:.0f}" for t, a, b in l["mots"])
        print(f"p{l['page']} y={l['y']:6.1f} {l['taille']:4.1f} {l['police'][:14]:14} | {mots}")


if __name__ == "__main__":
    main()
