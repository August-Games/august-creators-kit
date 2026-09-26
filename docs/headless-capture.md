# Headless capture mode

The kit can run headless: load a scene file, resolve it against the live
cache, play it back with paused seeks, and capture one frame per completed
draw. Everything is driven by `ck.capture.*` system properties, so a script
or launcher can operate it with no GUI interaction.

## Modes

- `batch` (default): capture a time range, write a completion sentinel,
  exit the JVM.
- `stills`: capture an explicit list of scene times, then exit.
- `daemon`: watch a request directory, process each scene request,
  hot-reload between scenes, no JVM restart.

The plugin stays idle when no capture is configured, so interactive use is
unaffected.

## Properties

| Property | Default | Meaning |
|---|---|---|
| `ck.capture.mode` | `batch` | `batch`, `stills`, or `daemon` |
| `ck.capture.scene` | — | Scene file (SetupSave JSON). Required in batch/stills |
| `ck.capture.out` | — | Output directory. Required in batch/stills |
| `ck.capture.fps` | `30` | Frames per second for batch ranges |
| `ck.capture.start` | `0` | Range start, scene seconds |
| `ck.capture.end` | scene max | Range end (exclusive), scene seconds |
| `ck.capture.times` | — | Comma-separated scene seconds. Required in stills |
| `ck.capture.requestDir` | — | Request directory. Required in daemon |
| `ck.capture.cropViewport` | `false` | Crop frames to the 3D viewport |
| `ck.capture.stageOnPlayer` | `true` | Re-base spawn tiles around the local player (waits for its tile to settle first) |
| `ck.capture.stageOffset` | `2,0` | Extra tile offset applied when staging |
| `ck.capture.aimCamera` | `true` | Face the staged actors from the player before each frame |
| `ck.capture.pitch` | `335` | Camera pitch applied by the aim override |
| `ck.capture.zoom` | unset | Camera zoom applied by the aim override (higher = closer); unset leaves it alone |
| `ck.capture.settleMs` | `500` | Settle delay after each seek, ms |
| `ck.capture.drawTimeoutSec` | `30` | Per-frame completed-draw timeout, s |

Capture also parks the mouse off the canvas (no hover text in frames)
and stops the GPU and beginner-tooltip plugins for a clean scene.

Scene seconds convert to kit ticks at 0.6 s per tick. A 5 s range at 30 fps
yields 150 frames named `frame_%05d.png`.

## Launcher

`com.creatorskit.capture.CaptureLauncher` starts the kit plus this plugin
on the host runtime classpath (headless hosts usually need `-ea` and their
own developer flags). Pass the `ck.capture.*` properties to the JVM; the
host owns login, window/canvas setup, and rendering backend.

## Resolve requests

A scene comp may carry a resolve request instead of baked geometry:

- player comps: `needs_resolve: true` with `equipment_slots`
  (`{ "<slot>": <item_id> }`, e.g. `{"head": 1163, "main_hand": 1333}`)
  plus `kitRecolours`
- NPC comps: `needs_resolve: true` with `npc_id`, and `modelId` 0

Slot names are the plain words `head`, `cape`, `neck`, `main_hand`,
`chest`, `off_hand`, `legs`, `hands`, `feet` (plus `helm`, `weapon`,
`torso`, `shield`, `boots`, `gloves`, `amulet` aliases). `ring` and `ammo`
have no visual entry and are skipped.

At load time the plugin resolves every request against the live cache
(player gear via the kit's player-model lookup on a clone of the local
player composition; NPCs via the NPC lookup) and then clears the request.
**A scene with any still-unresolved request is refused**: nothing plays,
an `ERROR` sentinel plus `capture.json` with the reason is written, and
batch/stills mode exits non-zero.

The catalog is parsed once at plugin enable, which on a fast headless boot
can land before the cache index syncs (lists come back empty while still
reporting loaded). The plugin therefore re-parses the catalogs once the
login screen is up, before resolving.

## Outputs

Each job writes into the output directory:

- `frame_%05d.png` — one image per captured frame, each from a completed
  draw (no gaps; a missing draw fails the job instead of writing a stale
  frame)
- `capture.json` — per frame: `scene_time`, `tick`, canvas and viewport
  dimensions, game state, file name; plus mode, fps, status
- `resolved_scene.json` — the scene as played, with requests resolved
- `DONE` (or `<request>.done`) on success, `ERROR` (or `<request>.error`)
  with the reason on failure

## Daemon requests

Drop a `<name>.request.json` file into the request directory:

```json
{
  "scene": "/path/to/scene.json",
  "out": "/path/to/out",
  "fps": 30,
  "start": 0.0,
  "end": 5.0,
  "cropViewport": true
}
```

Use `"times": [0.0, 2.5]` instead of `start`/`end` for stills. Processed
requests move to `handled/`. Each request resolves, reloads, and captures
in the same JVM. Drop a file named `stop` in the directory to end the loop.

Per-request framing keys (`stageOffset`, `aimCamera`, `pitch`, `zoom`)
override the matching `ck.capture.*` properties for that job only, so one
daemon can shoot different framings without a restart.

## Known limitations

- Instance staging is not supported: scenes must play in the main world
  with the bot on a plain tile. Inside an instance, template-based spawn
  tiles resolve in-scene but their models never draw, and literal instance
  tiles are rejected by the kit's placement (`inScene=false`). The run
  logs per-actor `object/model/active/inScene` health plus the camera
  pose to make this visible.
