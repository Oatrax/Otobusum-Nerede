# Otobüsüm

Unofficial Android app for live Istanbul bus (İETT) arrival times. Not affiliated with or endorsed by İETT or İBB.

A small rewrite of İETT's "Otobüsüm Nerede" for modern Android (8.0 → 15+, targetSdk 35).

Features: search stops/lines, live arrival estimates per stop (auto-refresh every 30 s, pull to refresh),
lines through a stop, stop list per line in both directions, favorites, open stop in maps,
and a live map per line (bus positions every 10 s, route shape, stops; tap an arrival on a stop screen
to follow that line centred on your stop). Map tiles: OpenStreetMap via osmdroid, no API key.

Data: the same public endpoints used by https://iett.istanbul (StationDetail / RouteDetail pages)
plus the open İBB SOAP services (api.ibb.gov.tr) for stop coordinates and live vehicle positions. No API key needed. If İETT changes its website,
`app/src/main/java/com/onur/otobusum/data/IettApi.kt` is the only file to fix;
`./gradlew testDebugUnitTest` runs a live check against the endpoints.

Tech: Kotlin, Jetpack Compose (Material 3), Navigation Compose, osmdroid.

Build: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/`

Signing: release builds read the keystore and passwords from `~/.android-keys/otobusum-release.properties` (kept outside the repo). Without it, use `./gradlew assembleDebug`.

Security hardening: HTTPS only (network security config, system CAs only, no https→http redirects),
response size cap, HTML-escaped map popups, backups disabled, not debuggable, R8-minified,
only exported component is the launcher activity.
