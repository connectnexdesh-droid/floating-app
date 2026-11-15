# FloatClip

FloatClip is an Android floating clipboard utility with two sequential Quick Text queues, a four-column clipboard sheet, and a local TOTP authenticator.

## Features

- Floating overlay with TXT1, TXT2, A, B, C, 2FA, and Close actions (A/B/C can be shown or hidden in settings).
- Quick Text has two independent queues: TXT1 and TXT2. Each supports its own saved rows and sequential copy position.
- Tap TXT1 or TXT2 once to copy the next row. Tap the same button twice within one second to repeat the last copied row.
- The Quick Text sheet marks the last copied row with a star. Hold a row number and slide up or down to select multiple rows; slide across a selected row to toggle it off.
- Clipboard Sheet keeps four columns (A–D). The floating A/B/C buttons show the next target row (for example A1, B2, C3) and advance as entries are saved.
- Quick Text and Clipboard Sheet support multi-row delete, Undo, and Redo. Cell edits, paste operations, and purge operations are also undoable.
- Room database persists text locally.
- RFC 6238 TOTP generation uses the secret currently on the clipboard; the secret is not saved by FloatClip.
- No backend, analytics, or network permission is used by the app.

## Open and build

1. Install Android Studio with Android SDK Platform 35 and Java 17.
2. Clone this repository and open the project folder in Android Studio.
3. Let Gradle sync, then run the app on an Android device or emulator.
4. Tap **Start Floating Bubble** and grant the **Display over other apps** permission.
5. Return to FloatClip and tap **Start Floating Bubble** again.

The included Gradle launcher scripts bootstrap Gradle 8.10.2 if Gradle is not already installed. They download the Gradle distribution from the official Gradle distribution host on the first run. The scripts are intentionally lightweight and do not require the binary Gradle wrapper JAR.

## Using the bubble

- **TXT1 / TXT2:** Each button copies the next item from its own queue and loops to the first item after the last one. A second tap on the same button within one second repeats the row that was just copied.
- **A / B / C:** Saves the current clipboard text to the first empty cell in that column. The button label shows the next target row, such as A1 or C3.
- **2FA:** Reads a Base32 TOTP secret from the clipboard, generates a six-digit code, and copies it.
- **Close:** Stops the floating service. Start it again from the main app.

Quick Text order is based on a stable database ID and position. TXT1 and TXT2 keep separate last-copied IDs, so their sequential queues do not interfere with each other.

## Security notes

- TOTP secrets are processed locally and are not persisted by the app.
- Treat copied 2FA secrets and generated codes as sensitive. Android may expose clipboard contents to the user and other apps.
- The overlay runs as a user-started foreground service on supported Android versions.
- Android versions and device manufacturers can vary in overlay and clipboard behavior; test on the target device before relying on it for authentication.

## CI

GitHub Actions builds the debug APK with Java 17, Android SDK Platform 35, and Gradle 8.10.2.
