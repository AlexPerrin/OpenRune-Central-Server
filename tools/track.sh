#!/usr/bin/env bash
# tools/track.sh <verb> <slug> [arg]: the Central track (docs/ARCHITECTURE.md of osrs-harness). Env from hd: SLUG HEAD T
# TCOMMIT BASE CLONE EVIDENCE BUILD HD_ROOT. build, gate, tests, evidence, commits and tree are tools/gate/gate.sh's (the code a
# build or gate runs, its own files). Verbs:
#   up [N]              the server's base (upstream main) with this Central built in, through the server track's build and up
#                       (SERVER_TRACK: its script): an old world against this Central, N desktops logged in
#   down                stops what up started (the server track's down)
#   log <slug> PATTERN  the change's last build or test log
# A server change that depends on a central change builds that Central in (the server track's CENTRAL); nothing here deploys.
case ${1:-} in build|gate|tests|evidence|commits|tree) exec "$(dirname "$0")/gate/gate.sh" "$@" ;; esac
set -euo pipefail
verb=$1; slug=${2:-${SLUG:-}}; arg=${3:-}   # a contract row passes no slug: hd exports SLUG
repo=${CLONE:-$HOME/src/OpenRune-Central-Server}
self=$(cd "$(dirname "$0")" && pwd)
root=${HD_ROOT:-$HOME/osrs-harness}
[ -f "$root/harness/changes/$slug.md" ] || { echo "no change \"$slug\": tools/track.sh <verb> <slug> [arg]" >&2; exit 2; }
state=$root/state/$slug; mkdir -p "$state"
server_track=${SERVER_TRACK:-$HOME/src/OpenRune-Server/.worktrees/harness/tools/track.sh}
up() {
  n=$arg; tc=$("$self/gate/gate.sh" tree "$slug" | cut -d" " -f1)   # this change's Central commit (the dev's working copy snapshotted)
  local srv s key rc=0; srv=$(dirname "$server_track"); s=$(git -C "$srv" rev-parse "origin/HEAD^{commit}"); key=$(git -C "$srv" rev-parse "$s^{tree}")-c${tc:0:12}
  server() { env -u CLONE -u BUILD -u EVIDENCE -u HEAD -u BASE -u TCOMMIT HD_ROOT="$root" CENTRAL="$repo:$tc" T="$key" "$server_track" "$@"; }
  cp "$state/build.json" "$state/central-build.json" 2>/dev/null || true   # the server build records into the same state dir
  server build "$slug" "$s" || rc=$?
  mv -f "$state/central-build.json" "$state/build.json" 2>/dev/null || true
  [ "$rc" = 0 ] && server up "$slug" $n
}
down() { env -u CLONE HD_ROOT="$root" "$server_track" down "$slug"; }
log() { grep -hE "${arg:-.}" "$state"/build-*.log "$state/test.log" 2>/dev/null | tail -200 || true; }

case $verb in up|down|log) "$verb" ;; *) echo "verbs: build up down gate tests evidence commits tree log" >&2; exit 2 ;; esac
