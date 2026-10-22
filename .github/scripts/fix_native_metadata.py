"""Nettoie les motifs de ressources tracés par l'agent GraalVM (chemins temporaires des tests, séparateurs Windows).

L'agent écrit les chemins avec des « \\ » dans des motifs \\Q...\\E : un segment qui commence par « E »
ferme la citation trop tôt et native-image échoue (PatternSyntaxException). Les ressources du classpath
utilisent toujours « / » : on remplace les séparateurs à l'intérieur de la citation.
Usage : fix_windows_metadata.py <dossier des métadonnées>
"""
import json
import pathlib
import re
import sys

# Fichiers ouverts par les tests (dossier de données temporaire) : rien à embarquer dans l'image,
# et un chemin Windows absolu produit un motif invalide.
TEST_PATH = re.compile(r"budgetly-test-|AppData|[A-Za-z]:[\\/]|/tmp/|Temp[\\/]")


def fix_pattern(pattern: str) -> str:
    if pattern.startswith("\\Q") and pattern.endswith("\\E"):
        inner = pattern[2:-2]
        return "\\Q" + inner.replace("\\", "/") + "\\E"
    return pattern


def is_test_path(node) -> bool:
    if not isinstance(node, dict):
        return False
    value = node.get("pattern") or node.get("glob")
    return isinstance(value, str) and bool(TEST_PATH.search(value))


def walk(node):
    changed = 0
    if isinstance(node, list):
        kept = [item for item in node if not is_test_path(item)]
        changed += len(node) - len(kept)
        node[:] = kept
    if isinstance(node, dict):
        for key, value in list(node.items()):
            if key == "pattern" and isinstance(value, str):
                fixed = fix_pattern(value)
                if fixed != value:
                    node[key] = fixed
                    changed += 1
            elif key == "glob" and isinstance(value, str) and "\\" in value:
                node[key] = value.replace("\\", "/")
                changed += 1
            else:
                changed += walk(value)
    if isinstance(node, list):
        for item in node:
            changed += walk(item)
    return changed


def main() -> None:
    total = 0
    for path in pathlib.Path(sys.argv[1]).rglob("*.json"):
        data = json.loads(path.read_text(encoding="utf-8"))
        changed = walk(data)
        if changed:
            path.write_text(json.dumps(data, indent=2), encoding="utf-8")
            total += changed
    print(f"::notice title=Métadonnées natives::{total} motif(s) de ressources corrigé(s) ou retiré(s)")


if __name__ == "__main__":
    main()
