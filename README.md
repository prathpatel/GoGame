# Go — Flutter

A calm, cross-platform board for the game of Go. Pure-Dart rules engine,
hand-painted board, beginner AI, SGF round-trip, and a built-in tutorial and
rules reference. Built with Flutter for Android and iOS, with desktop targets
planned.

> **Status:** v0.2.0 — local and AI play plus a connected online-play path for
> guest authentication, casual matchmaking, live WebSocket games, scoring,
> reconnects, and secure token refresh.

The Go backend under `backend/` can be run locally with PostgreSQL and Redis.
The mobile app connects to it from the **Play Online** card. See
[PRODUCTION_READINESS.md](PRODUCTION_READINESS.md) for the verified scope and
the features that still need production work.

> **History:** This repository started as a native Kotlin / Jetpack Compose
> Android app. It was migrated to Flutter on 2026-05-07 to enable iOS and
> desktop from a single codebase. The original Kotlin source is archived at
> [`prthmptl/GoGame-kotlin-legacy`](https://github.com/prthmptl/GoGame-kotlin-legacy)
> (private).

---

## Features

**Gameplay**

- 9×9, 13×13, and 19×19 boards
- Local two-player (pass-and-play) and human-vs-AI
- Online guest play through server-authoritative casual matchmaking
- Main-time clock with configurable time control
- Undo / pass / resign
- Optional move numbers and last-move highlight

**Rules engine** (`lib/src/domain/`, no Flutter imports — fully unit-testable)

- Capture, suicide prevention, simple ko
- Positional superko via Zobrist hashing
- Chinese area scoring with interactive dead-stone marking
- Group / liberty calculation with flood fill

**AI** (`lib/src/domain/ai/beginner_ai.dart`)

- Heuristic beginner: prioritises captures, saves stones in atari, prefers good
  shape, applies a 1-ply lookahead. Designed to feel like a forgiving opponent
  rather than a strong engine.

**SGF**

- Export the current game to SGF (`FF[4]`)
- Import the main line of an SGF file via the system file picker
- Share games through the OS share sheet (`share_plus`)

**Persistence**

- Saved games via `sqflite` with auto-resume on launch
- Settings via `shared_preferences` (theme, coordinates, hints, move numbers)
- Online access and refresh tokens in platform secure storage

**Online play**

- Configurable backend URL and readiness check
- Guest session creation with automatic access-token refresh
- 9×9, 13×13, and 19×19 casual matchmaking
- Server-authoritative WebSocket moves, clocks, scoring, and reconnect snapshots
- A one-device **Quick Network Test** opponent that uses the real backend path

**Learning**

- Step-by-step tutorial covering captures, life-and-death, and basic shape
- Full Chinese-rules reference screen

---

## Project layout

```
lib/
  main.dart                       # entrypoint, opens repo + settings, runs app
  src/
    app.dart                      # GoApp: GoRouter shell, bottom-nav chrome
    domain/                       # pure Dart — rules, board, AI
      board.dart                  # bitboard + intersection helpers
      game_state.dart             # current position, history, ko hash
      groups.dart                 # connected-stone / liberty queries
      rules.dart                  # legal-move check, capture, superko
      scoring.dart                # Chinese area scoring with dead marking
      models.dart                 # Color, Move, GameConfig, etc.
      ai/beginner_ai.dart         # heuristic opponent
    sgf/
      sgf.dart                    # serializer (FF[4])
      sgf_import.dart             # main-line parser
    data/
      saved_game.dart             # row model
      saved_game_repo.dart        # sqflite-backed repo
      settings_store.dart         # SharedPreferences wrapper
    online/
      api_client.dart             # auth, refresh, matchmaking + game REST client
    ui/
      theme.dart                  # zen-leaning Material 3 theme
      board/
        board_canvas.dart         # CustomPainter rendering board + stones
        mini_stone.dart           # small reusable stone widget
      components/
        zen_components.dart       # shared buttons, cards, dividers
      screens/
        home_screen.dart          # recent games, "new game"
        setup_screen.dart         # board size, players, time control
        game_screen.dart          # the play screen
        game_view_model.dart      # ChangeNotifier wrapping GameState
        online_lobby_screen.dart  # backend URL + matchmaking controls
        online_game_controller.dart # server-authoritative WebSocket game state
        review_screen.dart        # post-game / saved-game playback
        tutorial_screen.dart      # lessons list + detail
        rules_screen.dart         # rules reference
        settings_screen.dart      # preferences
test/
  domain/                         # rules + scoring tests
  sgf/                            # SGF round-trip + import tests
tool/
  gen_icon.py                     # regenerate adaptive launcher icons
```

The rules and SGF code are pure Dart. Persistence uses Flutter plugins and
needs platform integration checks in addition to serializer tests.

---

## Tech stack

- **Flutter** ≥ 3.27, **Dart** ≥ 3.6
- [`go_router`](https://pub.dev/packages/go_router) — declarative routing with a
  shell route for the bottom-nav chrome
- [`sqflite`](https://pub.dev/packages/sqflite) — saved games
- [`shared_preferences`](https://pub.dev/packages/shared_preferences) —
  user settings
- [`share_plus`](https://pub.dev/packages/share_plus) — share SGF
- [`file_picker`](https://pub.dev/packages/file_picker) — import SGF
- [`http`](https://pub.dev/packages/http) — backend REST API
- [`web_socket_channel`](https://pub.dev/packages/web_socket_channel) — live games
- [`flutter_secure_storage`](https://pub.dev/packages/flutter_secure_storage) —
  online session tokens
- [`flutter_launcher_icons`](https://pub.dev/packages/flutter_launcher_icons) —
  generates adaptive Android + iOS icons from `assets/icon/`

---

## Getting started

Install Flutter (≥ 3.27) and fetch packages:

```sh
flutter pub get
```

For local/AI-only play, run on a connected device or emulator:

```sh
flutter run
```

### Run the full local stack

Start PostgreSQL and Redis, then start the API in one terminal:

```sh
cd backend
make dev-up
make run-api
```

`make run-api` defaults to the credentials in
`backend/deploy/docker-compose.yml` and applies migrations automatically.

In a second terminal, run Android:

```sh
flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8080
```

Then open **Play Online** and choose **Quick Network Test**. It creates two
guest users, puts both through matchmaking, and plays the second seat through
the same REST, PostgreSQL, Redis, and WebSocket path. To test two physical
devices, use the development machine's LAN address instead of `10.0.2.2` and
tap **Find Another Player** on both.

Plain HTTP is enabled only in the Android debug manifest; other targets should
use a reachable HTTPS/WSS backend unless their local network policy is
configured separately.

Run the test suite (rules engine, scoring, SGF round-trip):

```sh
flutter test
```

With the local API running, the opt-in full-stack integration test is:

```sh
flutter test test/online/live_backend_test.dart \
  --dart-define=LIVE_BACKEND_TESTS=true
```

---

## Building releases

### Android (Google Play AAB)

```sh
flutter build appbundle --release
# Output: build/app/outputs/bundle/release/app-release.aab
```

The Android `applicationId` is `app.libertygo.play`. For Play uploads, copy the example
keystore config and fill in your details:

```sh
cp android/key.properties.example android/key.properties
```

`android/key.properties` and `*.jks` files are gitignored. Release packaging
requires a complete signing configuration and the upload keystore. Use
`flutter build apk --debug` for a smoke build without release credentials.

### iOS

```sh
cd ios && pod install
flutter build ipa --release
```

Open `ios/Runner.xcworkspace` in Xcode to configure signing. Bundle id is
`com.gogame`.

### Desktop (planned)

Desktop platforms are not yet scaffolded. To enable them:

```sh
flutter create --platforms=linux,windows,macos .
flutter run -d linux        # or windows / macos
```

The Dart code under `lib/` should run as-is; only platform folders need to be
generated.

---

## Regenerating launcher icons

Source artwork lives in `assets/icon/`. After editing, regenerate platform
icons with:

```sh
dart run flutter_launcher_icons
```

`tool/gen_icon.py` is a helper for producing the foreground / background PNGs
from a vector source.

---

## Versioning

Version is tracked in `pubspec.yaml` as `<semver>+<buildNumber>` (e.g.
`0.2.0+3`). Bump the build number on every Play / TestFlight upload.

---

## Contributing

This is a personal project, but issues and PRs are welcome. When changing the
rules engine or scoring code, please add or update tests in `test/domain/`.
