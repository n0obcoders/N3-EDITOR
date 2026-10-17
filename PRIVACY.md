# Notebook privacy policy

_This describes what the app in this repository actually does. If you fork Notebook
and change its behavior, update this document._

## Data storage

- Notes, checklists, labels, attachments, reminders, version history, templates and
  settings are stored only on the device, in the app's private storage.
- Notebook has no account system, no server and no cloud synchronization.
- The app's database and attachments are excluded from Android's automatic cloud
  backup. Use the in-app backup to move data between devices.

## Network

- Notebook declares the `INTERNET` permission for one feature: link previews. A
  preview is fetched only when link previews are enabled in Settings **and** you tap
  "Preview" on a link. Only the page's HTML is requested, with no cookies, and only the
  first 256 KB is read.
- Nothing else is transmitted. No analytics, crash reporting or advertising SDKs
  are included.

## Permissions

| Permission | Why |
| --- | --- |
| `POST_NOTIFICATIONS` | Show reminder notifications (requested on Android 13+). |
| `RECORD_AUDIO` | Record voice notes; requested only when you tap Record. |
| `RECEIVE_BOOT_COMPLETED` | Re-register reminders after a reboot. |
| `USE_BIOMETRIC` | App lock and per-note lock. |
| `INTERNET` | Link previews (see above). |

Files are read and written only through locations you pick with Android's file picker.
Notebook does not request broad storage access.

## Security

- **App lock** gates the user interface with your fingerprint, face or device PIN. It
  does not change how data is stored.
- **Locking a note** additionally encrypts its text with AES-256-GCM using a key kept in
  the Android Keystore. The key never leaves the device, so locked notes cannot be read
  from a copy of the database. Titles are not encrypted so locked notes can be listed.
- Backups created by Notebook contain locked notes in plain text, so you can restore
  them on another device. Store backups somewhere only you can reach.
- Notifications show only the note title unless you enable note text in Settings.
- The "Hide in recent apps" setting blanks the app in the task switcher and blocks
  screenshots.

## Contact

Open an issue in the GitHub repository for questions about this policy.
