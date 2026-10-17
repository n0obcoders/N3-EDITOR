# Measurements for the ReconX upgrade

Everything below was measured on 2026-10-04 in the build environment (Linux x86_64, JDK 17,
Robolectric 4.14 for the Android tests). **Nothing was run on a phone or an emulator**, so there
are no cold-start, frame-time or battery numbers here. Where a "before" exists it is the source
from the user's git bundle (the pre-upgrade `main` branch) built with the same toolchain, or the
APKs the user uploaded. Where no "before" exists (the new engines) only the "after" is given.

## APK size and method count

Measured with `apkanalyzer` from the Android SDK command-line tools.

| APK | File size | Download size | Dex method refs | Dex files |
| --- | ---: | ---: | ---: | ---: |
| Debug, before (bundle `main`, built here) | 23,172,589 B | 21,743,524 B | 188,903 | 25 |
| Debug, after (`reconx-upgrade`) | 24,245,887 B | 22,101,828 B | 191,818 | 25 |
| Release unsigned, before (user's upload) | 3,862,733 B | not measured* | 28,995 | 1 |
| Release unsigned, after (`reconx-upgrade`) | 3,876,529 B | 2,975,562 B | 29,269 | 1 |

\* the uploaded release APK was built from a newer source tree than the bundle, so only its size
and method count are comparable.

The shipped (release, R8-shrunk) APK grows by 13.8 KB and 274 method references while adding
the code editors, the highlighter for 30 languages, the syntax checker and the PCAP reader, and
losing reminders, labels, favorites and daily notes. Debug builds are not shrunk, so their size
tracks the amount of source, not what users install.

Permissions: `POST_NOTIFICATIONS` is gone (reminders removed). `RECEIVE_BOOT_COMPLETED` is still
merged in by WorkManager's own manifest so that scheduled maintenance survives a reboot; the app
declares no storage permission and uses the Storage Access Framework for every file.

## Home list: summary projection versus full notes

`app/src/test/java/com/nitinshukla/notebook/HomeListBenchmarkTest.kt`, in-memory Room on
Robolectric's SQLite, 2,000 notes of ~2.5 KB each, a quarter of them with six checklist items.
Median of five runs after a warm-up. The "before" path is `observeAll()` (what the old home
screen collected: every note with its content, checklist items and attachments). The "after"
path is `observeSummaries()` (the `NoteSummary` projection the lists use now).

| Query | Median time | Text carried into memory |
| --- | ---: | ---: |
| Full notes with relations (before) | 113.1 ms | 9,955,560 B |
| Summary projection (after) | 27.8 ms | 1,633,780 B |

JVM SQLite is faster than a phone's, so the absolute times will be higher on a device; the
4× time and 6× memory ratios are what the change is about.

## Search: SQL `LIKE` versus load-everything-and-filter

Same test and data, query `needle1999` (one hit at the end of the 2,000 notes).

| Strategy | Median time |
| --- | ---: |
| Load all notes, filter in memory (before) | 133.0 ms |
| `LIKE` per term in SQLite, then show matching summaries (after) | 88.4 ms |

The SQL path also avoids materialising 10 MB of note text on every keystroke. A full-text
index (FTS) would be faster still and is the obvious next step; it was left out to keep the
migration to schema version 2 a single, easily reviewed table rebuild.

## Syntax highlighter and checker (new, no "before")

`core/src/test/kotlin/com/nitinshukla/notebook/core/CoreBenchmarkTest.kt`, warmed JVM.

| Input | Result |
| --- | --- |
| 476,670 chars of Python (6,000 functions) | 70,583 tokens in 21.4 ms (`tokenize`) |
| Same text, offline syntax check | 163.3 ms, 0 issues |

The editor highlights on `Dispatchers.Default` with a 40 ms debounce and caps highlighting at
400,000 characters, so typing never waits on the tokenizer.

## PCAP indexing and decoding (new, no "before")

Same test file. A synthetic 19.2 MB classic pcap with 50,000 packets (half DNS queries over
UDP, half 600-byte TCP segments), read through the in-memory `ByteSource`.

| Operation | Result |
| --- | --- |
| Index whole capture (summary + packet rows) | 367.9 ms, 52.1 MB/s |
| Decode one packet for the detail view | 31.8 µs |

On a device the parser reads the capture from a `RandomAccessFile` in 1 MiB chunks, so the
disk, not the parser, will set the pace.

## Large files: line buffer (new in 1.1.1)

`core/src/test/kotlin/com/nitinshukla/notebook/core/LineBufferTest.kt`. A 50,000-line buffer
takes 1,000 single-line edits plus 100 split/join pairs in **15.4 ms** on the JVM (median not
taken; single run after JIT warm-up inside the test). Before this change the same file was one
`BasicTextField` whose layout re-ran over all 50,000 lines on every keystroke, which is what
the user reported as the editor "stopping". That old path was not timed on a device; it is
replaced, not tuned.

## Startup work

Not timed (no device). What changed is structural: `NotebookApplication.onCreate` no longer
blocks on template seeding or WorkManager scheduling; both run on `Dispatchers.IO`, and the
maintenance worker is scheduled three seconds after launch. Reminder rescheduling and the
boot receiver are gone.

## Test suite

| Suite | Tests | Result |
| --- | ---: | --- |
| `:core:test` (pure Kotlin) | 207 (incl. 2 benchmarks, 8 line-buffer tests) | all pass |
| `:app:testDebugUnitTest` (Robolectric: Room, migration 1→2, repositories, backup, editor tabs) | 28 (incl. 1 benchmark) | all pass |
| `:app:lintDebug` | 0 errors, 44 warnings, 2 hints | pass |
| `connectedDebugAndroidTest` (migration, Keystore, Compose UI) | not run: no device or emulator here | — |

Before the upgrade the same commands on the bundle's `main` gave 57 core tests and 23 app
tests passing.
