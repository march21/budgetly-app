#!/usr/bin/env bash
# Vérifie où le binaire natif trouve ses bibliothèques JDK (libawt…, chargées par PDFBox) :
# à côté du binaire, ou dans un sous-dossier indiqué par -Djava.library.path / variable d'environnement.
# Usage : native-libs-check.sh <dossier nativeCompile> ; écrit le résultat dans native-libs.txt.
set -u
native() { if command -v cygpath >/dev/null; then cygpath -w "$1"; else echo "$1"; fi; }
unix() { if command -v cygpath >/dev/null; then cygpath -u "$1"; else echo "$1"; fi; }
src="$(unix "$1")"
work="$(unix "${RUNNER_TEMP:-/tmp}")/native-libs"
pdf="$work/test.pdf"
rm -rf "$work"; mkdir -p "$work"
PY=$(command -v python3 || command -v python)
"$PY" "$(dirname "$0")/minimal_pdf.py" "$(native "$pdf")"

exe=budgetly-core; libglob='*.so'; envvar=LD_LIBRARY_PATH
if [ -f "$src/budgetly-core.exe" ]; then exe=budgetly-core.exe; libglob='*.dll'; envvar=PATH; fi
if ls "$src"/*.dylib >/dev/null 2>&1; then libglob='*.dylib'; envvar=DYLD_LIBRARY_PATH; fi

port=47900
run_variant() { # nom, dossier, args...
  local name="$1" dir="$2"; shift 2
  port=$((port + 1))
  local data="$work/data-$name"; mkdir -p "$data"
  ( cd "$dir" && BUDGETLY_PORT=$port BUDGETLY_DATA_DIR="$(native "$data")" BUDGETLY_LAUNCH_TOKEN=jeton "./$exe" "$@" > "$work/$name.log" 2>&1 ) &
  local pid=$!
  local jar="$work/$name.cookies"
  for _ in $(seq 1 100); do
    curl -sf -c "$jar" -L "http://127.0.0.1:$port/?launch=jeton" -o /dev/null && break
    sleep 0.2
  done
  local code
  code=$(curl -s -m 60 -o "$work/$name.json" -w '%{http_code}' -b "$jar" -F "file=@$(native "$pdf");type=application/pdf" "http://127.0.0.1:$port/api/dev/pdf-text" || echo "curl")
  if [ "$code" = "200" ] && grep -q "fictif" "$work/$name.json"; then result="ok"; else result="KO($code)"; fi
  kill $pid 2>/dev/null; wait $pid 2>/dev/null
  # Arrêt forcé sous Windows si le processus natif survit au shell.
  command -v taskkill >/dev/null && taskkill //F //IM "$exe" >/dev/null 2>&1
  echo "$name=$result"
}

# 1. Bibliothèques à côté du binaire (référence).
r1=$(run_variant a-cote "$src")
# 2-4. Binaire seul, bibliothèques dans lib/.
app="$work/app"; mkdir -p "$app/lib"
cp "$src/$exe" "$app/"
cp $src/$libglob "$app/lib/" 2>/dev/null
port=48000
r2=$(run_variant java-library-path "$app" "-Djava.library.path=$(native "$app/lib")")
port=48100
r3=$(export "$envvar=$app/lib:${!envvar:-}"; run_variant variable-env "$app")
port=48200
r4=$(run_variant sans-indication "$app")
libs=$(cd "$src" && ls $libglob 2>/dev/null | tr '\n' ' ')
echo "$r1 · $r2 · $r3 · $r4 · libs : ${libs:-aucune}" | tee native-libs.txt
for f in "$work"/*.log; do echo "--- $f"; tail -5 "$f"; done
