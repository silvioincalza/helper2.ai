#!/usr/bin/env bash
set -euo pipefail

REPO=${REPO:-silvioincalza/helper2.ai}
SITE=${SITE:-https://helper2.ai}
PUBLISHER=${PUBLISHER:-ca-pub-4688630163076563}
UNIT_RESOURCE=${UNIT_RESOURCE-9345575694}
UNIT_SEARCH=${UNIT_SEARCH-1139782613}
UNIT_GUIDE=${UNIT_GUIDE-4261217083}
BRANCH=${BRANCH:-chore/adsense-prod-ad-units}
STORY=${STORY:-872}
VALUES=deploy/helm/helper2/values-prod.yaml
DEFAULTS=deploy/helm/helper2/values.yaml
DRY_RUN=false
ASSUME_YES=false

usage() {
  cat <<'USAGE'
Sets the three AdSense ad units in the prod values and opens a pull request.
It never merges, never deploys and never turns the ads on: the back office does. See RUNBOOK-adsense.md.

  deploy/k8s/adsense-go-live.sh [--dry-run] [--yes]

  --dry-run   run the checks and print the change; write nothing, push nothing
  --yes       take the three questions about the AdSense account as answered "yes"

An ad unit set to an empty value (UNIT_GUIDE= ...) leaves that place without an ad.
USAGE
}

for arg in "$@"; do
  case $arg in
    --dry-run) DRY_RUN=true ;;
    --yes) ASSUME_YES=true ;;
    -h | --help) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done

stop() {
  echo "stop: $*" >&2
  exit 1
}

need() {
  command -v "$1" >/dev/null || stop "$1 is not installed"
}

ad_unit() {
  [[ -z $2 || $2 =~ ^[0-9]{8,12}$ ]] || stop "$1 is not an ad unit (8 to 12 digits): $2"
}

confirm() {
  $ASSUME_YES && return 0
  local answer
  read -r -p "$1 [y/N] " answer
  [[ $answer == y || $answer == Y ]] || stop "answer \"y\" only when it is true: $1"
}

need git
need gh
need yq
need curl
[[ $PUBLISHER =~ ^ca-pub-[0-9]{16}$ ]] || stop "PUBLISHER is not ca-pub- and 16 digits: $PUBLISHER"
ad_unit UNIT_RESOURCE "$UNIT_RESOURCE"
ad_unit UNIT_SEARCH "$UNIT_SEARCH"
ad_unit UNIT_GUIDE "$UNIT_GUIDE"
[[ -n $UNIT_RESOURCE$UNIT_SEARCH$UNIT_GUIDE ]] || stop "no ad unit to set"

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
git fetch --quiet origin main

echo "1. main has the three settings"
for key in adsenseSlot adsenseSlotSearch adsenseSlotGuide; do
  git show "origin/main:$DEFAULTS" | yq -e ".app.monetisation | has(\"$key\")" >/dev/null 2>&1 ||
    stop "origin/main has no app.monetisation.$key: merge the pull requests that add it first"
done

echo "2. $SITE/ads.txt names the publisher"
seller="google.com, ${PUBLISHER#ca-}, DIRECT, f08c47fec0942fa0"
curl --fail --silent --show-error --max-time 20 "$SITE/ads.txt" | grep -qxF "$seller" ||
  stop "$SITE/ads.txt does not answer: $seller"

echo "3. $SITE has the site check tag"
curl --fail --silent --show-error --max-time 20 "$SITE/" |
  grep -qF "<meta name=\"google-adsense-account\" content=\"$PUBLISHER\">" ||
  stop "$SITE/ has no google-adsense-account tag for $PUBLISHER"

echo "4. the AdSense account (only you can see it)"
confirm "AdSense > Sites: helper2.ai shows \"Ready\"?"
confirm "AdSense > Ads: Auto ads are OFF for helper2.ai (no anchor, vignette or side rail)?"
confirm "AdSense > Privacy and messaging: the European regulations message is PUBLISHED for helper2.ai?"

with_ad_units() {
  awk -v resource="$UNIT_RESOURCE" -v search="$UNIT_SEARCH" -v guide="$UNIT_GUIDE" '
    /^    adsenseSlot(Search|Guide)?:/ { next }
    { print }
    /^    adsenseClient:/ {
      if (resource != "") print "    adsenseSlot: \"" resource "\""
      if (search != "") print "    adsenseSlotSearch: \"" search "\""
      if (guide != "") print "    adsenseSlotGuide: \"" guide "\""
      done = 1
    }
    END { if (!done) exit 3 }
  '
}

git show "origin/main:$VALUES" | grep -qxF "    adsenseClient: $PUBLISHER" ||
  stop "$VALUES on origin/main does not set app.monetisation.adsenseClient to $PUBLISHER"

if $DRY_RUN; then
  echo "5. the change (dry run, nothing is written)"
  diff <(git show "origin/main:$VALUES") <(git show "origin/main:$VALUES" | with_ad_units) || true
  exit 0
fi

WORKTREE="$ROOT/.claude/worktrees/${BRANCH//\//-}"
[[ ! -e $WORKTREE ]] || stop "$WORKTREE is there already: remove it, or set BRANCH"
echo "5. branch $BRANCH from origin/main, in $WORKTREE"
git worktree add --quiet "$WORKTREE" -b "$BRANCH" origin/main
cd "$WORKTREE"
with_ad_units <"$VALUES" >"$VALUES.new"
mv "$VALUES.new" "$VALUES"
yq -e '.app.monetisation.adsenseClient' "$VALUES" >/dev/null || stop "$VALUES is not valid YAML after the change"
git diff --quiet -- "$VALUES" && stop "the prod values have these ad units already"
git diff -- "$VALUES"
git commit --quiet -m "chore(deploy): the AdSense ad units on prod" -- "$VALUES"
git push --quiet --set-upstream origin "$BRANCH"

echo "6. the pull request"
gh pr create --repo "$REPO" --base main --head "$BRANCH" \
  --title "chore(deploy): the AdSense ad units on prod" \
  --body "Closes #$STORY

Sets the AdSense ad units in \`$VALUES\` (story S8.6.3, ADR 0042).

Checked by \`deploy/k8s/adsense-go-live.sh\` before this pull request: \`/ads.txt\` and the site check tag on $SITE; the
owner confirmed that the site is \"Ready\" in AdSense, that Auto ads are off and that the European regulations
message is published.

No ad shows with the release that carries this commit: the ads start when \"Google ads\" is set to 1 in the back
office (\`deploy/k8s/RUNBOOK-adsense.md\`, section 3). After it, follow section 4."
echo "Next: review and merge it, release, then turn the ads on in the back office (RUNBOOK-adsense.md, section 3)."
