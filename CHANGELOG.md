# Changelog

All notable changes to Notebook are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [1.4.0] - 2026-10-17

### Changed
- The app is now called **N3-Core**, with a new `<N>` logo: mint chevrons and N on a deep teal field, with a monochrome layer for themed icons.
- Settings links to the repository at github.com/n0obcoders/N3-editor and to the developer page.

### Added
- A welcome page on first launch: who built N3-Core (Nitin Shukla), what it does, and a link to the repository.
- An "About the developer" page after Settings in the menu, with GitHub, the repository and the portfolio.

### Fixed
- Restoring a JSON or ZIP backup that held a note bigger than 2 MB failed with "Row too big to fit into CursorWindow". The database now reads rows through a 32 MB cursor window, so backup, restore and opening such notes all work. Backups from N३ editor restore unchanged.

## [1.3.0] - 2026-10-17

### Fixed
- The search screen dropped typed letters.
- Find bars lost the keyboard focus to the document after the first keystroke. Matches are now highlighted instead.
- The keyboard covered the editors.
- Editors lagged:
  - link detection and word counts ran on every keystroke;
  - line numbers were thousands of composables;
  - syntax colours re-laid out the file twice per key;
  - note mapping and decryption ran on the main thread.

### Added
- PCAP reader:
  - a combined filter: IP (exact, prefix or CIDR) + TCP/UDP + port or range, plus typed expressions;
  - a Payload tab showing packet contents as text;
  - Follow stream;
  - Export packets as text.

## [1.2.0] - 2026-10-04

### Fixed
- Settings crashed on open: the "Default note color" row put a lazy swatch row inside a
  Material `ListItem`, which asks its slots for an intrinsic height that lazy layouts cannot
  give. The row is a plain column now. A Robolectric Compose test (`SettingsScreenTest`)
  composes the whole Settings screen so this cannot come back silently.

### Changed
- The app is now called **N३ editor** (launcher, lock screen, onboarding, backup texts).
- New launcher icon: a neon-green terminal prompt on a near-black grid, adaptive with a
  monochrome layer for themed icons.
- Version code 3.

## [1.1.1] - 2026-10-04

### Fixed
- The Python Editor and Code Editor froze on very large files (a 50,000-line file was one
  text field). Files over 3,000 lines or 300,000 characters now use a line buffer and a
  lazy list of per-line fields, so only visible lines are laid out and highlighted.
- The "Open folder" button on the empty Code Editor rendered one letter per line.
- The navigation drawer covered only part of the screen; it is now full screen.

### Added
- "Open document" under the + button in Notes: any text or code file becomes a note, and
  files over 256 KB open in the Code Editor instead.
- A welcome page for the editors with Start actions, workspace status and shortcuts.
- The editors accept files up to 32 MB (was 20 MB).

## [1.1.0] - 2026-10-04 (ReconX upgrade)

### Added
- Rename a note from inside the editor (title tap, menu, Ctrl+R).
- Notepad-style find: jump through every occurrence with wrap-around, "No matches" and
  "Wrapped around" feedback, replace one or all.
- Unsaved-changes marker (`*`) based on edit revisions; "Created / Last saved" line that
  only changes after a successful save; the save status bar shows the persisted time.
- Code Notes section, "new code note" button and launcher shortcut, code font size setting.
- Save as with an extension picker (24 built-in extensions plus custom) through the
  system create-document dialog, written as UTF-8.
- Python Editor and Code Editor sharing one editor core: tabs, file explorer over a picked
  folder, status bar, syntax highlighting for 30 languages, offline syntax check,
  auto-indent, auto-close, comment toggling, line operations, go to line, command palette,
  snippets, session and draft recovery, encoding and line-ending preservation.
- PCAP/PCAPNG reader: on-device parser, capture summary, virtualized packet list with
  filter, layered packet details and a hex dump with offset, hex and ASCII columns.
- Autosave delay setting.
- Benchmarks (`CoreBenchmarkTest`, `HomeListBenchmarkTest`) and documented measurements.

### Changed
- Lists (home, search, widgets) load a `NoteSummary` projection (400-character preview,
  checklist counts, attachment count) instead of every note with all its relations.
- Search runs as an SQL `LIKE` query per term inside SQLite instead of filtering fully
  loaded notes in memory.
- Saving writes only the editable columns in one statement and returns the persisted
  timestamp.
- Startup: default templates and maintenance scheduling run on a background thread after
  the first frame; maintenance scheduling is delayed by three seconds.
- Database schema version 2 with a tested migration; backup format 2 (format 1 restores).
- Navigation: Notes, Code Notes, Python Editor, Code Editor, PCAP Reader, Templates,
  Archive, Trash, Settings.

### Removed
- Favorites, reminders (worker, boot receiver, notification channel and the
  POST_NOTIFICATIONS and RECEIVE_BOOT_COMPLETED permissions), daily notes (column,
  widget, shortcut) and labels (tables and screens). Label names are folded into the
  notes as `#tags` by the migration so no data is silently lost.

### Added
- Notepad-style editor: autosave, manual save, undo/redo, cut/copy/paste, select all,
  find with next/previous, replace and replace all (match case, whole word, regex),
  word wrap, line numbers, status bar with line/column, word, character, line counts
  and reading time, font size and family, monospaced and plain-text modes, cursor
  position restored on reopen, keyboard shortcuts (Ctrl+S/Z/Y/F/H/B/I/U).
- Text files through the Storage Access Framework: open, save, save as, import
  several files, export a note, share as text or as a file.
- Note types: plain, rich text (bold/italic/underline), checklist, image, voice,
  drawing, link and code.
- Checklists with reorder, complete, move-completed-to-bottom, clear completed,
  progress and conversion to and from plain text.
- Eleven note colors with readable contrast in light and dark themes.
- Labels with colors, multi-label notes, filtering and search by label.
- Pin, favorite, archive, trash with restore and permanent delete, automatic
  trash cleanup after a configurable retention period.
- Search across titles, content, checklist items, labels, file names and
  attachment captions, with suggestions, recent searches, filters, date ranges,
  sorting and match highlighting.
- Reminders with presets, date/time picker, daily/weekly/monthly/yearly repeats,
  notifications with Done and Snooze, reboot rescheduling, upcoming list.
- Images from the photo picker with captions, voice recordings with pause and
  playback, a drawing canvas with pen, eraser, size, colors and undo/redo.
- Link detection, clickable links and optional on-demand link previews.
- Favorites, daily notes, editable templates (eight built in).
- Version history with configurable interval, count and storage limits, diff
  view and restore.
- App lock (biometric or device credential) with auto-lock, per-note lock with
  AES-256-GCM encryption via the Android Keystore, secure-screen option, private
  notification previews by default.
- Four home-screen widgets: quick note, pinned notes, checklist, daily note.
- JSON and ZIP backups to a user-chosen location with validation, duplicate
  handling and restore.
- Settings for everything above, persisted with DataStore.
- Unit tests (core logic, Room, repositories, backup), instrumented tests
  (migrations, Keystore encryption, Compose UI) and a GitHub Actions workflow.
