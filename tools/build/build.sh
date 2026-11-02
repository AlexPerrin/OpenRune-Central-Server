#!/usr/bin/env bash
# tools/build/build.sh <verb> <slug> [arg]: the Central track's build, the code a build runs and nothing else (tools/track.sh
# dispatches build and tree here; tools/gate/gate.sh sources it). hd keys a build by T's tree and the hash of tools/build/ alone,
# so a gate edit never rebuilds. Env from hd: SLUG HEAD T (the build key) TCOMMIT BASE CLONE EVIDENCE BUILD HD_ROOT. Verbs:
#   build [TCOMMIT]     Gradle assemble of T's published modules in the build copy (a dev: the working copy snapshotted, T from `hd tree`); cached by T
#   tree                prints TCOMMIT and T (a dev: the working copy snapshotted)
set -euo pipefail
self=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)   # tools/build (also when gate.sh sources it)
verb=$1; slug=${2:-${SLUG:-}}; arg=${3:-}   # a contract row passes no slug: hd exports SLUG
repo=$(dirname "$(git -C "${CLONE:-$HOME/src/OpenRune-Central-Server}" rev-parse --path-format=absolute --git-common-dir)")   # the main checkout, also when CLONE names a worktree of it (a brief's Clone)
root=${HD_ROOT:-$HOME/osrs-harness}
[ -f "$root/harness/changes/$slug.md" ] || { echo "no change \"$slug\": tools/track.sh <verb> <slug> [arg]" >&2; exit 2; }
state=$root/state/$slug; mkdir -p "$state"
dev=$repo/.worktrees/$slug; copy=$repo/.worktrees/$slug-build
modules="central-common central-worldlink central-app"   # what dev.or2 publishes and the server includes; the admin web is not built
atomic() { cat > "$1.$$.tmp"; if [ -s "$1.$$.tmp" ]; then mv -f "$1.$$.tmp" "$1"; else rm -f "$1.$$.tmp"; return 1; fi; }   # a state record whole or not at all: hd reads it at any moment
record() { python3 -c 'import json,os,sys;print(json.dumps({"T":sys.argv[1],"ok":sys.argv[2]=="true","detail":sys.stdin.read()[-2000:],"head":os.environ.get("HEAD","")}))' "$T" "$1" | atomic "$state/$2.json"; }
tested="central-worldlink central-app"   # shortcut: central-common's tests do not compile on upstream main (Rights.MODERATOR is gone); add it back when they do
fault='cannot allocate memory|no space left on device|Cannot connect to the Docker daemon|error during connect|is already in use by container'   # the host refusing the step (memory, disk, the daemon, a container a killed run left), never the tree
ooms() { dmesg 2>/dev/null | grep -c '\] Out of memory: Killed process' || true; }   # the host OOM killer's kills so far (a container's own --memory limit logs 'Memory cgroup out of memory')
hosted() {  # hosted <log> <command...>: the command, its output appended to log; failed for the host's cause, not the tree's, it exits 75: no result, the pass runs it again
  local log=$1 n rc=0 why; shift; n=$(ooms); "$@" >> "$log" 2>&1 || rc=$?
  [ $rc != 0 ] || return 0
  why=$(grep -m1 -oiE "$fault" "$log") || { [ "$rc" = 137 ] && [ "$(ooms)" -gt "$n" ] && why="killed by the host's OOM killer"; } || return $rc
  echo "the host's fault, not the tree's ($why): no result"; exit 75
}
gradle() {  # gradle <log> <dir> <task> [modules]: that task of each module, in container build-<slug> as uid 1000 with the change's Gradle volume, once the host has its room (`hd room`), its output in log (emptied first)
  local log=$1 tasks=() m; shift; : > "$log"; for m in ${3:-$modules}; do tasks+=(":$m:$2"); done
  docker run --rm -v "gradle-$slug:/gradle" eclipse-temurin:21 chown 1000:1000 /gradle   # docker makes a new volume root's
  hosted "$log" python3 "$root/hd.py" room docker run --init --rm --name "build-$slug" --cpus=4 --memory=4g -u 1000:1000 -e HOME=/gradle/home -e GRADLE_USER_HOME=/gradle \
    -v "gradle-$slug:/gradle" -v "$repo/.git:$repo/.git:ro" -v "$1:$1" -w "$1" eclipse-temurin:21 \
    bash -c 'mkdir -p "$HOME"; bash ./gradlew --console=plain --no-daemon "$@"' _ "${tasks[@]}"
}
tree() {  # TCOMMIT and T: the pass gives them; a dev's build snapshots the working copy and asks hd
  tc=${arg:-${TCOMMIT:-}}   # the pass's TCOMMIT, as an argument or in the env (a row's)
  if [ -n "$tc" ]; then git -C "$repo" rev-parse -q --verify "$tc^{commit}" >/dev/null || { echo "$tc is not a commit: TCOMMIT is the commit of T" >&2; exit 2; }; T=${T:-$(git -C "$repo" rev-parse "$tc^{tree}")}; return; fi
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
  gradle "$log" "$copy" assemble || { record false build < "$log"; echo "build failed: $log"; return 1; }
  record true build < "$log"; echo "built $T"
}
[ "${BASH_SOURCE[0]}" = "$0" ] || return 0   # sourced by gate.sh: its functions alone
case $verb in build) build ;; tree) tree; echo "$tc $T" ;; *) echo "verbs: build tree" >&2; exit 2 ;; esac
