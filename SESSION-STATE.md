# Session state — fm/pv1-capture — 2026-09-26T11:12Z
## Goal
Headless capture mode in august-creators-kit (batch/stills/daemon, resolve
requests, paused-seek capture); prove vs local dev server, open PR vs
fm/pv1-ck-rebase. Public surface only: generic kit wording, no internals.
## Now
Staging/placement bug hunt. Fix landed: stage-then-write resolved scene +
settle-on-player-tile + per-actor tile logs (8d62ba7). Current symptom:
chars spawn with models but active=false in the staging instance —
suspect isInScene=false via toLocalInstance mapping. Next: read inScene +
tile from new spawn-health log (jar rebuilt 11:12, daemon restarting).
## Done
- Branch fm/pv1-capture from origin/fm/pv1-ck-rebase; WIP commits 0dc70c9,
  8d62ba7 pushed.
- Kit: capture pkg (options/resolver/plugin/launcher), resolve fields on
  CustomModelComp, docs/headless-capture.md, 19 unit tests green.
- Proof env: server copy+dist lessons, own server :43831 (gradle-hosted;
  shared --stop kills daemon-hosted servers!), client via treehouse cp +
  throwaway login driver, teaser compiled (3 comps, needs_resolve).
- Proven: batch 150/150+DONE+exit0, negative refusal (ERROR+exit1),
  daemon hot-reload r1..r5, login-screen-wait fix, catalog reload.
- All pre-11:05 pixel/determinism numbers INVALID (wrote resolved file
  before staging; actors at authored tiles, camera elsewhere).
## Decisions
- Kit never logs in; login/host/canvas are host-owned (generic surface).
- Refuse-and-exit-nonzero on any unresolved comp (fail closed).
- Own scratch under /home/dylan/scratch/pv1-capture; own PGIDs; private
  gradle home for kit builds; never --stop shared home.
- inbox 001 (presentable viewport MP4s) + 002 (checkpoint push) + 003
  (empty-frame staging diagnosis) read; 001/002 acked, 003 pending verify.
## Next
1. Daemon stills (capbot9/new): confirm inScene/tile, actors in frame.
2. Redo measurement: landscape x2 (fresh bots), portrait (canvas flag) x2,
   stills, RMSE viewport + actor boxes, ffmpeg MP4s to preview dir.
3. Commit, push, open PR vs fm/pv1-ck-rebase (gh-axi, non-draft), post
   review tag, done line with URL + MP4 names.
## Pitfalls
- File WatchService none; daemon polls req/ every 2s; stop file ends loop.
- Fresh bots auto-stage into instances at engine-placed tiles (vary/login).
- Returning bots spawn home (live world) — use fresh bots for quiet runs.
- installDist assembles a stale api jar; use :game:app:run (monitor port).
- compile() end exclusive: 5s@30fps=150 frames; explicit end=5.
## Waits
- None. Server :43831 up; client restarts per run; kill by recorded PGID.
