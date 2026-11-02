#!/usr/bin/env bash
# tools/gate/gate.sh <verb> <slug> [arg]: the Central track's gate and every verb a contract row runs (tools/track.sh dispatches
# them here); the build is tools/build/build.sh's, sourced. hd keys a gate by the build key and the hash of tools/gate/ and the
# files the contract's rows name. Env from hd: SLUG HEAD T (the build key) G (the gate key) TCOMMIT BASE CLONE EVIDENCE BUILD HD_ROOT. Verbs:
#   gate TCOMMIT        the build, then every check: and gate: row of the contract from the build copy (`hd rows`), recorded under G
#   tests               the unit tests of the tested modules in BUILD (a gate row)
#   evidence            pr-title.txt, pr-body.md, pr-comment.md present and non-empty (a check row)
#   commits             subjects after BASE start with feat: or fix:; no Claude-Session trailer (a check row)
. "$(dirname "$0")/../build/build.sh"   # verb slug arg self(tools/build) repo root state dev copy modules tested atomic record gradle tree build
gate() {
  tc=$arg; build; git -C "$copy" reset -q --hard "$tc" && git -C "$copy" clean -fdq
  out=$(HD_ROOT=$root python3 "$root/hd.py" rows "$slug" "$tc" "$copy" 2>&1) && rc=0 || rc=$?
  [ $rc != 75 ] || { echo "$out"; exit 75; }   # a row held (its source down): no result, hd starts the gate again
  ok=$([ $rc = 0 ] && echo true || echo false)
  T=${G:-$T}; record "$ok" gate <<< "$out"
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

case $verb in gate|tests|evidence|commits) "$verb" ;; *) echo "verbs: gate tests evidence commits" >&2; exit 2 ;; esac
