#!/usr/bin/env bash
# tools/track.sh <verb> <slug> [arg]: the Central track (docs/ARCHITECTURE.md of osrs-harness). Env from hd: SLUG HEAD T
# TCOMMIT BASE CLONE EVIDENCE BUILD HD_ROOT. Verbs:
#   build [TCOMMIT]     Gradle assemble of T's published modules in the build copy (a dev: the working copy snapshotted, T from `hd tree`); cached by T
#   up [N]              the server's base (upstream main) with this Central built in, through the server track's build and up
#                       (SERVER_TRACK: its script): an old world against this Central, N desktops logged in
#   down                stops what up started (the server track's down)
#   gate TCOMMIT        every check: and gate: row of the contract from the build copy (`hd rows`)
#   tests               the unit tests of the tested modules in BUILD (a gate row)
#   evidence            pr-title.txt, pr-body.md, pr-comment.md present and non-empty (a check row)
#   commits             subjects after BASE start with feat: or fix:; no Claude-Session trailer (a check row)
#   log <slug> PATTERN  the change's last build or test log
# A server change that depends on a central change builds that Central in (the server track's CENTRAL); nothing here deploys.
set -euo pipefail
verb=$1; slug=${2:-${SLUG:-}}; arg=${3:-}   # a contract row passes no slug: hd exports SLUG
repo=${CLONE:-$HOME/src/OpenRune-Central-Server}
root=${HD_ROOT:-$HOME/osrs-harness}
[ -f "$root/harness/changes/$slug.md" ] || { echo "no change \"$slug\": tools/track.sh <verb> <slug> [arg]" >&2; exit 2; }
state=$root/state/$slug; mkdir -p "$state"
dev=$repo/.worktrees/$slug; copy=$repo/.worktrees/$slug-build
server_track=${SERVER_TRACK:-$HOME/src/OpenRune-Server/.worktrees/harness/tools/track.sh}
modules="central-common central-worldlink central-app"   # what dev.or2 publishes and the server includes; the admin web is not built
record() { python3 -c 'import json,sys;print(json.dumps({"T":sys.argv[1],"ok":sys.argv[2]=="true","detail":sys.stdin.read()[-2000:]}))' "$T" "$1" > "$state/$2.json"; }
tested="central-worldlink central-app"   # shortcut: central-common's tests do not compile on upstream main (Rights.MODERATOR is gone); add it back when they do
gradle() {  # gradle <dir> <task> [modules]: that task of each module, in container build-<slug> as uid 1000 with the change's Gradle volume
  local tasks=() m; for m in ${3:-$modules}; do tasks+=(":$m:$2"); done
  docker run --rm -v "gradle-$slug:/gradle" eclipse-temurin:21 chown 1000:1000 /gradle   # docker makes a new volume root's
  docker run --rm --name "build-$slug" --cpus=4 --memory=4g -u 1000:1000 -e HOME=/gradle/home -e GRADLE_USER_HOME=/gradle \
    -v "gradle-$slug:/gradle" -v "$repo/.git:$repo/.git:ro" -v "$1:$1" -w "$1" eclipse-temurin:21 \
    bash -c 'mkdir -p "$HOME"; bash ./gradlew --console=plain --no-daemon "$@"' _ "${tasks[@]}"
}
tree() {  # TCOMMIT and T: the pass gives them; a dev's build snapshots the working copy and asks hd
  if [ -n "$arg" ]; then tc=$arg; T=${T:-$(git -C "$repo" rev-parse "$tc^{tree}")}; return; fi
  [ -d "$dev" ] || git -C "$repo" worktree add -q "$dev" -B "dev/$slug" "$(git -C "$repo" rev-parse -q --verify "fork/dev/$slug" || echo origin/main)"
  idx=$(git -C "$dev" rev-parse --git-path snapshot-index); rm -f "$idx"
  GIT_INDEX_FILE=$idx git -C "$dev" read-tree HEAD && GIT_INDEX_FILE=$idx git -C "$dev" add -A . && snap=$(git -C "$dev" commit-tree "$(GIT_INDEX_FILE=$idx git -C "$dev" write-tree)" -p HEAD -m snapshot)
  read -r tc T _ < <(HD_ROOT=$root python3 "$root/hd.py" tree "$slug" "$snap")
}
build() {
  tree
  if python3 -c 'import json,sys;b=json.load(open(sys.argv[1]));sys.exit(not (b["ok"] and b["T"]==sys.argv[2]))' "$state/build.json" "$T" 2>/dev/null; then echo "cached $T"; return; fi
  if [ -d "$copy" ]; then git -C "$copy" reset -q --hard "$tc" && git -C "$copy" clean -fdq; else git -C "$repo" worktree add -q --detach "$copy" "$tc"; fi
  log=$state/build-$T.log
  gradle "$copy" assemble > "$log" 2>&1 || { record false build < "$log"; echo "build failed: $log"; return 1; }
  record true build < "$log"; echo "built $T"
}
up() {
  n=$arg; arg=; tree   # this change's Central commit (the dev's working copy snapshotted)
  local srv s key rc=0; srv=$(dirname "$server_track"); s=$(git -C "$srv" rev-parse "origin/HEAD^{commit}"); key=$(git -C "$srv" rev-parse "$s^{tree}")-c${tc:0:12}
  server() { env -u CLONE -u BUILD -u EVIDENCE -u HEAD -u BASE -u TCOMMIT HD_ROOT="$root" CENTRAL="$repo:$tc" T="$key" "$server_track" "$@"; }
  cp "$state/build.json" "$state/central-build.json" 2>/dev/null || true   # the server build records into the same state dir
  server build "$slug" "$s" || rc=$?
  mv -f "$state/central-build.json" "$state/build.json" 2>/dev/null || true
  [ "$rc" = 0 ] && server up "$slug" $n
}
down() { env -u CLONE HD_ROOT="$root" "$server_track" down "$slug"; }
gate() {
  tc=$arg; build; git -C "$copy" reset -q --hard "$tc" && git -C "$copy" clean -fdq
  if out=$(HD_ROOT=$root python3 "$root/hd.py" rows "$slug" "$tc" "$copy" 2>&1); then record true gate <<< "$out"; else record false gate <<< "$out"; fi
}
tests() {
  log=$state/test.log
  gradle "${BUILD:?the build copy}" test "$tested" > "$log" 2>&1 || { grep -E 'FAILED|Test.*>' "$log" | tail -20; echo "tests fail: $log"; return 1; }
  echo "tests pass"
}
evidence() {
  for f in pr-title.txt pr-body.md pr-comment.md; do [ -s "${EVIDENCE:?}/$f" ] || { echo "missing or empty: $EVIDENCE/$f"; return 1; }; done
}
commits() {
  bad=$(git -C "$repo" log --format='%H%x09%s' "${BASE:?}..${HEAD:?}" | grep -vP '\t(feat|fix)(\([^)]*\))?: ' || true)
  [ -z "$bad" ] || { echo "subjects not feat:/fix: $bad"; return 1; }
  bad=$(git -C "$repo" log --format='%H%x09%(trailers:key=Claude-Session,valueonly)' "$BASE..$HEAD" | grep -P '\t\S' || true)
  [ -z "$bad" ] || { echo "Claude-Session trailer: $bad"; return 1; }
}
log() { grep -hE "${arg:-.}" "$state"/build-*.log "$state/test.log" 2>/dev/null | tail -200 || true; }

case $verb in build|up|down|gate|tests|evidence|commits|log) "$verb" ;; *) echo "verbs: build up down gate tests evidence commits log" >&2; exit 2 ;; esac
