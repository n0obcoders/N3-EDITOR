# ReconX upgrade report

Branch `reconx-upgrade` on top of the source in the user's git bundle. Measured numbers are in
[BENCHMARKS.md](BENCHMARKS.md); the full feature list is in the project [README](../README.md).

## What was delivered

| ReconX item | Status | Where |
| --- | --- | --- |
| Audit | done | `/mnt/project-files/notebook/audit.md` |
| Remove favorites, reminders, daily notes, labels with safe migration | done; labels folded into `#tags`, migration tested on Robolectric and as an instrumented test | `data/db/NotebookDatabase.kt` (`MIGRATION_1_2`), `MigrationRobolectricTest` |
| Navigation: Notes, Code Notes, Python Editor, Code Editor, PCAP Reader, Settings | done (Templates, Archive, Trash kept) | `ui/NotebookApp.kt` |
| Performance: lists, DB, startup, Compose | done: `NoteSummary` projection, SQL search, single-statement save, deferred startup, off-thread highlighting, virtualized lists | see benchmarks |
| Accurate save timestamps (UTC stored, local shown, updated only after a successful save) | done | `EditorViewModel.saveNow`, `SaveTimestamps` |
| Unsaved `*` with revision-based dirty tracking and debounced autosave | done, autosave delay is a setting | `EditorViewModel` |
| Editor: rename in place, Notepad-style find/replace with wrap-around | done | `EditorScreen`, `RenameDialog`, `FindBar` |
| Code Notes Save As: filename → extension picker → `ACTION_CREATE_DOCUMENT`, UTF-8 | done, 24 extensions plus custom | `SaveAsDialog`, `CodeFileTypes` |
| Python Editor | done except execution (no interpreter; the Run action says so and offers the syntax check) | `CodeWorkspaceScreen(PYTHON)` |
| VS Code-style Code Editor with shared core | done: tabs, explorer, status bar, palette | `ui/code/*` |
| PCAP/PCAPNG reader with summary, virtualized list, details, hex dump | done | `core/pcap/*`, `ui/pcap/*` |
| Material 3, accessibility | Material 3 throughout; content descriptions on controls and packet rows; large-text safe layouts not verified on a device | — |
| Android 13–16 without broad storage permission | SAF only; `targetSdk 35`. Not run on a device | manifest |
| Tests and honest benchmarks | 224 JVM tests, measurements documented, no device numbers claimed | `docs/BENCHMARKS.md` |
| Deliverables: project, migrations, tests, README, debug APK, release APK | all present; release APK is unsigned because no keystore exists in the repository | `/mnt/project-files/notebook/` |

## Round 2 (2026-10-04, after the first APK)

| Request | What changed |
| --- | --- |
| Python Editor stopped on a 50,000-line file | New `LineBuffer` in core and `LargeCodeEditor` in the app: files over 3,000 lines or 300,000 characters are edited line by line in a lazy list; only visible lines are composed, highlighted or laid out. Find, replace, go to line, undo/redo, indent, comment, duplicate/move/delete line, drafts and save all work on it. Limit raised to 32 MB. |
| "+" in Notes should open any document | New "Open document" item: the file becomes a note; files over 256 KB go to the Code Editor's line mode. |
| Code Editor open-file screen looked broken | Replaced with a welcome page: title and purpose, Start list (New file, Open file, Open folder) with hints and shortcuts, workspace status, and keyboard shortcuts. The one-letter-per-line button is gone. |
| Drawer was half screen | The drawer sheet now fills the screen. |

Still not run on a device. The line-mode editor was exercised by unit tests of its buffer and
tab model and by compiling the UI, not by typing on a phone.

## Round 3 (2026-10-04)

| Request | What changed |
| --- | --- |
| Settings does not open | Reproduced in a new JVM Compose test: a `LazyRow` of colour swatches inside a Material `ListItem` throws "Asking for intrinsic measurements of SubcomposeLayout layouts is not supported" the moment the screen composes. Replaced with a plain column; the test now passes and guards the screen. |
| Black-green hacking-style icon | New adaptive icon: neon-green `>_` prompt in bracket frame over a near-black grid (`ic_launcher_foreground.xml`, `ic_launcher_background.xml`), monochrome layer included. |
| Rename to N३ editor | `app_name` and every user-facing "Notebook" string now read N३ editor; version 1.2.0 (code 3). Package id unchanged, so it installs over the previous build. |

## Not done, and why

- **Python execution.** No interpreter is bundled (Chaquopy or a bundled CPython would add tens
  of megabytes and native code). The button exists, is clearly marked unavailable, and offers the
  offline syntax check.
- **Device testing.** No phone or emulator was available. Compile, unit tests, lint and the two
  APK builds succeeded; the instrumented tests were updated but not executed. Cold start, frame
  times and memory on a device were not measured.
- **Release signing.** The repository has no keystore (correctly), so the release APK is unsigned
  and must be signed with `apksigner` before it can be installed.
- **`material-icons-extended`** is still a dependency (49 icons used). R8 strips the unused icons
  from the release APK, so it only costs debug-build size and build time.
- **FTS search.** Search uses `LIKE` in SQLite; an FTS table would be faster on very large
  libraries and is the natural next migration.

## Note about the source

The bundle the branch is built on is older than the APK described in the uploaded `CHANGES.md`:
it has no device file browser, no encoding codec and uses AGP 8.10 rather than 9.4. Those newer
changes are not in this branch because their source was not provided. The SAF open / edit /
save-back flow in the notes editor was kept and extended (encoding and line endings are now
preserved on save).

## How to get the code

`notebook-reconx-upgrade.bundle` in the deliverables folder is a git bundle containing the whole
`reconx-upgrade` branch:

```bash
git clone notebook-reconx-upgrade.bundle notebook
cd notebook && git checkout reconx-upgrade
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :core:test :app:testDebugUnitTest
```
