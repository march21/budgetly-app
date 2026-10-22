#!/usr/bin/env python3
"""Contrôle vie privée du dépôt Budgetly, avant commit et en CI.

    python tools/verifier-vie-privee.py            # fichiers suivis + modifications en cours
    python tools/verifier-vie-privee.py --staged   # seulement ce qui va être commité

Bloquant (code de sortie 1) : relevés, bases, sauvegardes ou exports versionnés ; IBAN valide (clé mod 97)
hors des tests. Avertissement : adresses e-mail hors domaines d'exemple. Les valeurs trouvées ne sont jamais
affichées, seulement le fichier et la ligne.
"""
import re
import subprocess
import sys

FORBIDDEN_EXT = re.compile(r"\.(pdf|db|sqlite|sqlite3|tsv|budgetly)$", re.I)
CSV_ALLOWED = re.compile(r"^(core/src/test/|ui/src/.*\.test\.)")
TEST_PATH = re.compile(r"(^|/)src/test/")
SKIP_PATH = re.compile(r"^(LICENSE|ui/package-lock\.json|desktop/src-tauri/Cargo\.lock)$")
IBAN = re.compile(r"\b([A-Z]{2}\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,4})?)\b")
EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
EMAIL_OK = re.compile(r"@(example\.(com|org|net|fr)|exemple\.(fr|com)|anthropic\.com|users\.noreply\.github\.com)$", re.I)


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True, check=True).stdout


def iban_valid(raw):
    s = raw.replace(" ", "")
    if not 15 <= len(s) <= 34:
        return False
    rearranged = s[4:] + s[:4]
    digits = "".join(str(int(c, 36)) for c in rearranged)
    return int(digits) % 97 == 1


def main():
    staged = "--staged" in sys.argv
    if staged:
        files = git("diff", "--cached", "--name-only", "--diff-filter=ACMR").split()
    else:
        files = set(git("ls-files").split()) | set(git("ls-files", "--others", "--exclude-standard").split())
        files = sorted(files)

    errors, warnings = [], []
    for path in files:
        if FORBIDDEN_EXT.search(path) or (path.lower().endswith(".csv") and not CSV_ALLOWED.search(path)):
            errors.append(f"{path} : type de fichier interdit (relevé, base, sauvegarde ou export)")
            continue
        if SKIP_PATH.search(path):
            continue
        try:
            if staged:
                text = git("show", f":{path}")
            else:
                with open(path, encoding="utf-8") as f:
                    text = f.read()
        except (UnicodeDecodeError, FileNotFoundError, IsADirectoryError, subprocess.CalledProcessError):
            continue
        for n, line in enumerate(text.splitlines(), 1):
            if not TEST_PATH.search(path):
                for m in IBAN.finditer(line):
                    if iban_valid(m.group(1)):
                        errors.append(f"{path}:{n} : IBAN valide hors des tests")
            for m in EMAIL.finditer(line):
                if not EMAIL_OK.search(m.group(0)):
                    warnings.append(f"{path}:{n} : adresse e-mail")

    for w in warnings:
        print(f"avertissement  {w}")
    for e in errors:
        print(f"BLOQUANT       {e}")
    if errors:
        print(f"\n{len(errors)} problème(s) bloquant(s). Retirez ces données avant de committer.")
        return 1
    print(f"Contrôle vie privée : {len(files)} fichier(s), aucun problème bloquant"
          + (f", {len(warnings)} avertissement(s) à relire." if warnings else "."))
    return 0


if __name__ == "__main__":
    sys.exit(main())
