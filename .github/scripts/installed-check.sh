#!/usr/bin/env bash
# Après installation silencieuse (Windows) : vérifie la disposition installée et que le cœur, lancé comme le
# fait la coque (bibliothèques de lib/ dans PATH), extrait bien le texte d'un PDF fictif.
set -u
inst="$(cygpath -u "$LOCALAPPDATA")/Budgetly"
work="$(cygpath -u "$RUNNER_TEMP")/installed"
mkdir -p "$work/data"
layout=$(cd "$inst" && find . -maxdepth 2 -type f | sort | tr '\n' ' ')
python "$(dirname "$0")/minimal_pdf.py" "$(cygpath -w "$work/test.pdf")"
export PATH="$inst/lib:$PATH"
( cd "$inst" && BUDGETLY_PORT=47950 BUDGETLY_DATA_DIR="$(cygpath -w "$work/data")" BUDGETLY_LAUNCH_TOKEN=jeton \
    ./budgetly-core.exe "-Djava.library.path=$(cygpath -w "$inst/lib")" > "$work/core.log" 2>&1 ) &
for _ in $(seq 1 100); do curl -sf -c "$work/c" -L "http://127.0.0.1:47950/?launch=jeton" -o /dev/null && break; sleep 0.2; done
code=$(curl -s -m 60 -o "$work/out.json" -w '%{http_code}' -b "$work/c" -F "file=@$(cygpath -w "$work/test.pdf");type=application/pdf" http://127.0.0.1:47950/api/dev/pdf-text)
taskkill //F //IM budgetly-core.exe >/dev/null 2>&1
if [ "$code" = "200" ] && grep -q fictif "$work/out.json"; then result=ok; else result="KO($code)"; fi
echo "pdf-text=$result · installé : $layout" | tee installed.txt
[ "$result" = ok ] || { tail -20 "$work/core.log"; exit 1; }
