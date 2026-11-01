#!/usr/bin/env bash
# tools/track.sh <verb> <slug> [arg]: the Central track (docs/ARCHITECTURE.md of osrs-harness). Env from hd: SLUG HEAD T
# TCOMMIT BASE CLONE EVIDENCE BUILD HD_ROOT. build and tree are tools/build/build.sh's (the code a build runs,
# keyed alone); gate, tests, evidence and commits tools/gate/gate.sh's (the code a gate runs). Verbs:
#   up [N]              the server's base (upstream main) with this Central built in, through the server track's build and up
#                       (SERVER_TRACK: its script): an old world against this Central, N desktops logged in
#   down                stops what up started (the server track's down)
#   log <slug> PATTERN  the change's last build or test log
# A server change that depends on a central change builds that Central in (the server track's CENTRAL); nothing here deploys.
set -euo pipefail
verb=$1; slug=${2:-${SLUG:-}}; arg=${3:-}   # a contract row passes no slug: hd exports SLUG
repo=${CLONE:-$HOME/src/OpenRune-Central-Server}
self=$(cd "$(dirname "$0")" && pwd)
root=${HD_ROOT:-$HOME/osrs-harness}
[ -f "$root/harness/changes/$slug.md" ] || { echo "no change \"$slug\": tools/track.sh <verb> <slug> [arg]" >&2; exit 2; }
# a run reads its own copy of tools/, one per content in state/tools/<hash>/tools (exported: TOOLS_ROOT, its parent, where hd
# runs a gate's rows; TOOLS_SRC, the checkout): a ship of the tools branch moves the checkout under a running script, and hd's
# repairs remove a copy once no process or container uses it, and a .<hash>.<pid> being copied once its pid is gone
case $self in "$root"/state/tools/*) ;; *)
  k=$(cd "$self" && find . -type f ! -path '*/__pycache__/*' -print0 | sort -z | xargs -0 sha1sum | sha1sum | cut -c1-12); c=$root/state/tools/$k
  [ -d "$c" ] || { d=$root/state/tools/.$k.$$; rm -rf "$d"; mkdir -p "$d/tools"; cp -a "$self/." "$d/tools/"; mv -T "$d" "$c" 2>/dev/null || rm -rf "$d"; }
  TOOLS_SRC=$self TOOLS_ROOT=$c exec bash "$c/tools/track.sh" "$@" ;;
esac
case ${1:-} in build|tree) exec "$self/build/build.sh" "$@" ;; gate|tests|evidence|commits) exec "$self/gate/gate.sh" "$@" ;; esac
state=$root/state/$slug; mkdir -p "$state"
server_track=${SERVER_TRACK:-$HOME/src/OpenRune-Server/.worktrees/harness/tools/track.sh}
up() {
  n=$arg; tc=$("$self/build/build.sh" tree "$slug" | cut -d" " -f1)   # this change's Central commit (the dev's working copy snapshotted)
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
