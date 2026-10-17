# Contributing to Notebook

Thanks for helping. Notebook is a privacy-first, offline notes app; changes that add
network access, analytics or accounts will not be merged.

## Setup

1. Install Android Studio (Ladybug or newer) with the Android 15 (API 35) SDK.
2. Clone the repository and open it in Android Studio, or run `./gradlew assembleDebug`.
3. Run `./gradlew :core:test :app:testDebugUnitTest :app:lintDebug` before opening a pull request.

## Layout

| Module | What lives there |
| --- | --- |
| `core/` | Pure Kotlin: models, search, text tools, checklist operations, backup format, revision and reminder rules. No Android dependency, fast JVM tests. |
| `app/` | Android: Room, DataStore, WorkManager, Compose UI, widgets, security. |

Inside `app/` the packages follow the layers: `data` (db, repository, prefs, files, backup),
`ui` (one package per screen with its ViewModel), `reminders`, `work`, `security`, `widget`, `di`.

## Guidelines

- Put logic that does not need Android in `core/` and test it there.
- ViewModels expose immutable state; Composables do not touch repositories directly.
- Any change to a Room entity needs a schema version bump, a `Migration` in
  `Migrations.ALL`, and a step in `MigrationTest`. Never use destructive migrations.
- New strings go in `res/values/strings.xml`; keep them translatable.
- Add a line to `CHANGELOG.md` under *Unreleased*.
- Keep pull requests focused. Describe what a user sees before and after the change.

## Reporting issues

Use the issue templates. Please strip personal note content from logs and screenshots.
