# Bootstrap

This is now a root-level Flutter project. After cloning, install Flutter
(>= 3.27) and run:

```sh
flutter pub get
```

The standard commands are:

```sh
flutter test                              # runs the rules / scoring / SGF tests
flutter run                               # debug build on a connected device
flutter build appbundle --release         # Google Play AAB
flutter build ipa --release               # iOS .ipa (signed via Xcode)
```

For full-stack local testing, start the dependencies and API, then run the
Android app in another terminal:

```sh
cd backend
make dev-up
make run-api

# From the repository root in another terminal:
flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8080
```

Open **Play Online** and use **Quick Network Test** to create and play a real
two-user match through the API, PostgreSQL, Redis, and WebSockets.

## Android signing

Copy `android/key.properties.example` to `android/key.properties` and fill in
your release keystore details. `android/key.properties` and keystore files are
ignored by Git. If the file is present, Gradle signs release builds with that
upload key. Release builds fail explicitly when the signing configuration is
missing; use `flutter build apk --debug` for an unsigned local smoke build.

The Android `applicationId` and namespace are `app.libertygo.play`; the iOS
bundle identifier is `com.gogame`.
