# Central track

OpenRune-Central-Server (dev.or2 central-common, central-worldlink, central-app): the fork
AlexPerrin/OpenRune-Central-Server of OpenRune/OpenRune-Central-Server, clone `~/src/OpenRune-Central-Server` (remote
`origin` = upstream, `fork` = the fork). The server depends on a published Central version (`openruneCentral` in its
`gradle/libs.versions.toml`); a server change that needs a Central change lists it in `deps`, and its build includes
that Central's source (`-Popenrune.central.includeBuild`, the server track's `CENTRAL`). A central change is live once
its gate passes at its head; it goes upstream as a PR after the user's yes. `hd` is `python3 ~/osrs-harness/hd.py`;
the track script is `tools/track.sh <verb> <slug>`; the evidence dir is `evidence/<slug>/`.

## Branches on the fork
`main` mirrors upstream. `dev/<slug>` is the dev's ref, the only one a dev pushes; the change's head is its tip.
`content/<slug>` and `fix/<slug>` are the public PR branches, moved only by the harness after the user's yes.
`local/harness` holds these tools.

## Rules for upstream (re-read AGENTS.md, CONTRIBUTING.md, the PR template and the README on upstream `main` every
run; the contract records the blob shas of those that exist)
- One change per PR, off current upstream `main`, never stacked. Check upstream first (gh pr list on
  OpenRune/OpenRune-Central-Server with --search, and the issues). Theirs wins.
- The world link is a wire protocol both servers speak: a new op or field keeps every existing op's encoding, and an
  old world keeps working against the new Central.
- A PR is ready only when every reported defect is fixed in it; nothing is promised for a later PR.
- Updates are new commits on the PR branch (merge `main` in, never force-push); the description is never replaced.
- Subjects `feat:`/`fix:`. No new KDoc or explanatory comments; nothing public for a test.
- Upstream texts never link a fork PR; never open a PR, issue or comment on the fork.

## The dev
Your clone `~/src/OpenRune-Central-Server/.worktrees/<slug>` on `dev/<slug>`. Commit; `tools/track.sh build <slug>`
builds the published modules of T in the build copy; `tools/track.sh log <slug> <pattern>` reads its log;
`tools/track.sh up <slug> [N]` runs the server's base (upstream `main`) with this Central built in, N desktops logged in
(an old world against this Central), `down` stops it. A row proves the change through its own build and its
dependencies only; a proof that needs a server change depending on it (one that uses a new op) is that change's row.
Write `pr-title.txt`, `pr-body.md`, `pr-comment.md` (names the head). Push `dev/<slug>` to `fork`, then `hd handover
<slug>`. `hd ask <slug> "..."` when a decision is not yours; `hd show <slug>` first, always.

## The reviewer
Never writes the code it checks. Judges every contract row from the same build (`up`, then `down` before the hand-back); a text-only failure is fixed in the
evidence dir and the row marked pass.

## Never
Push any ref but yours; open or edit PRs; connect to Jagex; stop a process you did not start.

## Seed rows
Rows the contract starts from (a row tagged `upstream` judges the upstream PR and seeds only a change that opens one; `check:` rows run with SLUG, HEAD, T, TCOMMIT, BASE, CLONE, BUILD, EVIDENCE in the env).
- claims | every PR claim, file, test name and ticked box is true of the exact head | judge: pr-text
- no-deferral | no Not-done list, no unticked item, no open ask; every reported defect fixed in this PR | judge: pr-text | upstream
- net-change | on an existing PR, pr-comment.md covers only the commits since the PR's live head | judge: pr-text | upstream
- one-change | one feature, nothing unrelated, no open upstream PR for the same change, Depends stated | judge: pr-text | upstream
- wire | existing world-link ops encode as before; an old world still logs in against this Central | judge: qa-server
- style | no added comments or KDoc, no unjustified test, nothing public for a test | judge: qa-server
- evidence | pr-title.txt, pr-body.md, pr-comment.md present and non-empty | check: tools/track.sh evidence
- subject | commit subjects after the base start with feat: or fix:; no Claude-Session trailer | check: tools/track.sh commits | upstream
- tests | the published modules' unit tests pass at T | gate: tools/track.sh tests
