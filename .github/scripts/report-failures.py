#!/usr/bin/env python3
"""Remonte les erreurs de build / tests en annotations GitHub (lisibles via l'API, sans les logs bruts).

Usage : report-failures.py <gradle.log> <dossier test-results>
"""
import pathlib
import re
import sys
import xml.etree.ElementTree as ET


def annotate(title: str, message: str) -> None:
    message = message[:6000].replace("%", "%25").replace("\r", "").replace("\n", "%0A")
    print(f"::error title={title}::{message}")


def main() -> None:
    log = pathlib.Path(sys.argv[1])
    results = pathlib.Path(sys.argv[2])

    if log.exists():
        lines = log.read_text(errors="replace").splitlines()
        compile_errors = [l for l in lines if re.match(r"^(e:|error|Error:)", l.strip())]
        if compile_errors:
            annotate("Compilation", "\n".join(compile_errors[:40]))
        for i, line in enumerate(lines):
            if "What went wrong" in line:
                annotate("Gradle", "\n".join(lines[i : i + 25]))
                break
        reported = 0
        for i, line in enumerate(lines):
            if line.lstrip().startswith("Error: ") and reported < 3:
                annotate("Erreur native-image", "\n".join(lines[i : i + 40]))
                reported += 1
        # Exécution native des tests : sortie du lanceur JUnit + éventuel crash, hors dump de registres.
        start = next((i for i, l in enumerate(lines) if l.startswith("> Task :nativeTest") and "Compile" not in l), None)
        if start is not None:
            run = [l for l in lines[start:] if not re.match(r"^\s+\d+ \(\d+ bytes\):", l)]
            text = "\n".join(run[:400])
            for n, chunk in enumerate(range(0, min(len(text), 30000), 5500)):
                annotate(f"nativeTest {n + 1}", text[chunk : chunk + 5500])
        exceptions = [l for l in lines if "Exception" in l and not l.lstrip().startswith("at ")]
        if exceptions:
            annotate("Exceptions", "\n".join(list(dict.fromkeys(exceptions))[:40]))

    failures = []
    for xml_file in results.rglob("*.xml"):
        try:
            root = ET.parse(xml_file).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            for problem in list(case.findall("failure")) + list(case.findall("error")):
                detail = (problem.get("message") or "") + "\n" + (problem.text or "")
                failures.append(f"{case.get('classname')} > {case.get('name')}\n{detail[:1500]}")
    for failure in failures[:8]:
        annotate("Test", failure)


if __name__ == "__main__":
    main()
