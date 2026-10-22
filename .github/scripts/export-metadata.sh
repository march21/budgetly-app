#!/usr/bin/env bash
# Publie les métadonnées natives tracées en commentaires de la PR (archive tar.gz en base64, découpée),
# pour pouvoir les versionner : les artefacts de CI ne sont pas lisibles depuis l'environnement de développement.
# Usage : export-metadata.sh <dossier> <étiquette> ; requiert GH_TOKEN, PR_NUMBER, GITHUB_REPOSITORY.
set -euo pipefail
dir="$1"; label="$2"
[ -n "${PR_NUMBER:-}" ] || { echo "Pas de PR : export ignoré"; exit 0; }
# Sous Windows, « D:\… » serait lu par tar comme un hôte distant : chemins Unix.
base="${RUNNER_TEMP:-/tmp}"; command -v cygpath >/dev/null && base="$(cygpath -u "$base")"
tmp="$base/meta-export"; rm -rf "$tmp"; mkdir -p "$tmp"
tar --force-local -czf "$tmp/meta.tgz" -C "$dir" .
base64 -w0 "$tmp/meta.tgz" > "$tmp/meta.b64"
sum=$(sha256sum "$tmp/meta.tgz" | cut -c1-16)
split -b 60000 -d "$tmp/meta.b64" "$tmp/part-"
parts=$(ls "$tmp"/part-* | wc -l)
i=0
for part in "$tmp"/part-*; do
  i=$((i + 1))
  { echo "<!-- native-metadata $label $sum $i/$parts -->"; echo '```'; cat "$part"; echo; echo '```'; } > "$tmp/body.md"
  gh pr comment "$PR_NUMBER" -R "$GITHUB_REPOSITORY" --body-file "$tmp/body.md" > /dev/null
done
echo "::notice title=Métadonnées exportées ($label)::$(stat -c %s "$tmp/meta.tgz") octets, $parts commentaire(s), empreinte $sum"
