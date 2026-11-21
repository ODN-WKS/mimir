#!/usr/bin/env bash
# Privacy scan: no owner, old-project, place, machine or secret traces in the
# tree (tracked plus untracked-not-ignored files). Exits 1 on any hit.
#   tools/privacy-scan.sh
# Patterns are built from fragments so this file does not contain them.
set -uo pipefail
cd "$(dirname "$0")/.."
j() { local IFS=''; echo "$*"; }
pats=(
  "$(j rav enor)" "$(j gm ail)" "$(j cla ude '[ _-]*' sta tion)"
  "$(j tur bine)" "$(j home port)" "$(j hug inn)" "$(j mun inn)"
  "$(j handheld '[-_ ]?' forge)" "$(j ally '[-_ ]?' hub)" "$(j ch ico)" "$(j but te ' ' '(county|ca)')"
  "$(j /ho me /de ck)" "$(j de ck@)" "$(j ms '-' 7e07)" "$(j 139 00k)" "$(j '192\.' 168 '\.')"
  "$(j 988e 9732)" "$(j R3C Y30P)" "$(j HVA 5WTT)" "$(j 3802 60560D)" "$(j SM '-S' 938)" "$(j SM '-G' 950)"
  "$(j '-----BEG' 'IN [A-Z ]*PRIV')" "$(j ghp '_[A-Za-z0-9]{20}')" "$(j github '_pat_[A-Za-z0-9_]{20}')" "$(j AI za 'Sy[A-Za-z0-9_-]{20}')"
  "$(j google '-services\.json')" "$(j '\bwi' 'fe\b')"
  "$(j steam deck)" "$(j 'Pro' 'jects/R' andom)"
)
re=$(IFS='|'; echo "${pats[*]}")
# Allowed: the fixed commit identity.
files=$(git ls-files -co --exclude-standard | grep -v '^tools/privacy-scan\.sh$' | grep -vE '^(android/app/src/main/assets/voices/|assets/fonts/|Cargo\.lock$|crates/link/src/words\.txt$)')
hits=0
while IFS= read -r f; do
  [ -f "$f" ] || continue
  if out=$(grep -aInEi -e "$re" -- "$f" 2>/dev/null); then
    echo "$out" | sed "s|^|$f:|" | head -20; hits=1
  fi
done <<<"$files"
# Emails other than the fixed one.
if grep -aIrnE '[A-Za-z0-9._-]+@[A-Za-z0-9.-]+\.[a-z]{2,}' $(echo "$files") 2>/dev/null \
   | grep -v 'noreply@odnwks.invalid' | grep -vE '@(example\.|[a-z]+\.example|types|Composable|Test|Before|After)' | grep -E '@[a-z0-9-]+\.(com|org|net|io|me)\b'; then hits=1; fi
# Forbidden files.
if git ls-files -co --exclude-standard | grep -E '(^|/)(progress\.log|CLAUDE\.md|\.claude/|.*\.jks|.*\.pass|google-services\.json)$|^docs/designs/'; then hits=1; fi
# Commit identities.
bad=$(git log --all --format='%an <%ae>|%cn <%ce>' | tr '|' '\n' | sort -u | grep -v 'ODN-WKS <noreply@odnwks.invalid>' | grep -v 'GitHub' || true)
[ -z "$bad" ] || { echo "commit identities: $bad"; hits=1; }
[ "$hits" = 0 ] && echo "privacy scan clean" || { echo "privacy scan: hits above"; exit 1; }
