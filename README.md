# Troubadour

A lightweight, ad-free Pandora client for Android, in the spirit of
[Pithos](https://pithos.github.io/) on Linux.

Troubadour signs into your existing Pandora account and plays your stations
without the official app's ads, trackers or clutter.

## Features

- Sign in with your Pandora account (password encrypted on-device with the Android Keystore)
- Station list with art, including QuickMix/Shuffle
- Ad entries in playlists are skipped, the way Pithos does it
- Thumbs up, thumbs down (skips immediately), and "tired of this song"
- Create stations from an artist or song search; long-press to delete one
- Audio quality setting: High (AAC), MP3 128 kbps, Medium, Low
- Background playback with notification, lock screen and headset controls
- Android Auto: browse and play your stations from the car's dash
- No Google Play Services required; no analytics

## Building

The easiest route is GitHub Actions: every push to `main` runs the unit tests
and builds a debug APK, downloadable from the run's **Artifacts** section.

Locally, with Android Studio or the Android SDK installed:

```sh
./gradlew testDebugUnitTest assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+. Min Android version: 8.0 (API 26).

## How it works

Troubadour talks to Pandora's unofficial JSON "tuner" API (v5), the same one
Pithos and pianobar use:

- `pandora/` — Blowfish request encryption, partner/user login, stations,
  playlists, feedback, search. Plain `HttpURLConnection` + `org.json`, no extra libraries.
- `data/` — the shared session (auto re-login when the token expires) and the
  Keystore-backed credential store.
- `playback/PlaybackService.kt` — a Media3 `MediaLibraryService`. Starting a
  station means setting a `station:<token>` media item; the service swaps it for
  real tracks and keeps the queue topped up. Expired stream links are skipped.
- `ui/` — Jetpack Compose screens.

## Notes

- This uses an unofficial API. Pandora can change it at any time, and it isn't
  endorsed by Pandora Media. Use your own account.
- Skip limits and other account rules are enforced by Pandora's servers, not
  the app. A free account still has Pandora's skip limits; a paid tier lifts them.
- Pandora is only available in the US.

Not affiliated with Pandora Media, LLC.
