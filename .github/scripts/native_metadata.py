"""Métadonnées natives GraalVM (format *-config.json de l'agent) : union de plusieurs jeux, et écart entre deux jeux.

  native_metadata.py merge <sortie> <dossier>...   union (Linux + Windows), écrite triée pour des diffs stables
  native_metadata.py missing <versionné> <tracé>   entrées tracées absentes du jeu versionné (code 0, annotation)

Les entrées sont identifiées par leur nom de type (ou motif de ressource) et leur condition ; les listes internes
(méthodes, champs) sont fusionnées, les drapeaux booléens combinés par « ou ».
"""
import json
import pathlib
import sys

FILES = [
    "reflect-config.json",
    "jni-config.json",
    "resource-config.json",
    "proxy-config.json",
    "serialization-config.json",
    "predefined-classes-config.json",
    "reachability-metadata.json",
]


def dumps(value) -> str:
    return json.dumps(value, sort_keys=True, ensure_ascii=False)


def entry_key(entry):
    """Identité d'une entrée : son nom (type, motif) et ce qui la distingue d'une homonyme (condition, module,
    signature d'une surcharge de méthode)."""
    if isinstance(entry, dict):
        name = entry.get("name") or entry.get("type") or entry.get("pattern") or entry.get("glob")
        if name is not None:
            return dumps([name, entry.get("condition"), entry.get("module"), entry.get("parameterTypes")])
    return dumps(entry)


def is_entry_list(node) -> bool:
    """Liste d'entrées (ensemble, l'ordre ne compte pas) ; une liste de valeurs simples, elle, est un tout
    ordonné (types de paramètres d'une méthode, interfaces d'un proxy)."""
    return isinstance(node, list) and all(isinstance(x, dict) for x in node)


def merge_entry(a: dict, b: dict) -> dict:
    out = dict(a)
    for k, v in b.items():
        if k not in out:
            out[k] = v
        elif isinstance(v, bool) and isinstance(out[k], bool):
            out[k] = out[k] or v
        elif is_entry_list(v) and is_entry_list(out[k]):
            out[k] = merge_list(out[k], v)
        elif isinstance(v, dict) and isinstance(out[k], dict):
            out[k] = merge_node(out[k], v)
    return out


def merge_list(a: list, b: list) -> list:
    merged = {}
    for item in a + b:
        key = entry_key(item)
        if key in merged and isinstance(item, dict) and isinstance(merged[key], dict):
            merged[key] = merge_entry(merged[key], item)
        else:
            merged.setdefault(key, item)
    return [merged[k] for k in sorted(merged)]


def merge_node(a, b):
    if is_entry_list(a) and is_entry_list(b):
        return merge_list(a, b)
    if isinstance(a, list) and isinstance(b, list):
        return a
    if isinstance(a, dict) and isinstance(b, dict):
        out = dict(a)
        for k, v in b.items():
            out[k] = merge_node(out[k], v) if k in out else v
        return out
    return a


def normalize(node):
    """Tri récursif des listes pour une sortie stable."""
    if is_entry_list(node):
        return merge_list([], [normalize(x) for x in node])
    if isinstance(node, list):
        return [normalize(x) for x in node]
    if isinstance(node, dict):
        return {k: normalize(v) for k, v in node.items()}
    return node


def flatten(node, prefix="") -> set:
    """Ensemble de chemins « entrée|champ|élément » : sert à calculer ce qui manque."""
    out = set()
    if isinstance(node, list) and not is_entry_list(node):
        out.add(prefix + dumps(node))
    elif isinstance(node, list):
        for item in node:
            key = entry_key(item)
            out.add(prefix + key)
            if isinstance(item, dict):
                out |= flatten(item, prefix + key + "|")
    elif isinstance(node, dict):
        for k, v in node.items():
            if isinstance(v, (list, dict)):
                out |= flatten(v, prefix + k + "|")
            elif v is True:
                out.add(prefix + k)
    return out


def load(directory: pathlib.Path, name: str):
    path = directory / name
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None


def merge(output: pathlib.Path, inputs: list) -> None:
    output.mkdir(parents=True, exist_ok=True)
    for name in FILES:
        merged = None
        for directory in inputs:
            data = load(directory, name)
            if data is None:
                continue
            merged = data if merged is None else merge_node(merged, data)
        if merged is not None:
            (output / name).write_text(json.dumps(normalize(merged), indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


# Entrées propres à Budgetly (classes, migrations, tests) : couvertes par Spring AOT et vérifiées par nativeTest.
OWN = (
    '["dev.budgetly.',
    '["[Ldev.budgetly.',
    '["java.lang.Object", null, null, null]|methods|',
    'resources|includes|["\\\\Qdb/migration/',
    'resources|includes|["\\\\Qdev/budgetly/',
)


def missing(committed: pathlib.Path, traced: pathlib.Path) -> int:
    gaps = []
    for name in FILES:
        new = load(traced, name)
        if new is None:
            continue
        old = load(committed, name) or ([] if isinstance(new, list) else {})
        # Les classes de Budgetly sont couvertes par les indications générées par Spring AOT à la compilation
        # (et vérifiées par nativeTest) : seules les dépendances comptent ici.
        gaps += [f"{name}: {path}" for path in sorted(flatten(new) - flatten(old)) if not path.startswith(OWN)]
    if gaps:
        text = "\n".join(gaps[:60]) + (f"\n… et {len(gaps) - 60} autres" if len(gaps) > 60 else "")
        print("::warning title=Métadonnées natives à compléter::"
              + f"{len(gaps)} entrée(s) tracée(s) absentes du jeu versionné :\n{text}".replace("%", "%25").replace("\n", "%0A"))
    else:
        print("::notice title=Métadonnées natives::le jeu versionné couvre tout ce que les tests ont tracé")
    return len(gaps)


if __name__ == "__main__":
    command = sys.argv[1]
    if command == "merge":
        merge(pathlib.Path(sys.argv[2]), [pathlib.Path(p) for p in sys.argv[3:]])
    elif command == "missing":
        missing(pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3]))
    else:
        sys.exit(f"commande inconnue : {command}")
