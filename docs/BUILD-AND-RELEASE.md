# Build and release

Use JDK17, Gradle wrapper8.9 (checksum pinned), SDK34, target33 and AGP8.5.2. There are no runtime Maven dependencies. Unit tests use JUnit4.13.2. This is an MIT-licensed, sideloaded AndroidTV utility, not a Play Store submission. QUERY_ALL_PACKAGES is intentional: inventorying and inspecting every installed user app, including apps without a launch intent, is its core purpose.

1. Make changes on `developer`.
2. Run `gradlew testDebugUnitTest lintRelease assembleRelease` and inspect the native TV interface with a D-pad.
3. For signed builds, supply `APP_OPTIMIZER_KEYSTORE` and `APP_OPTIMIZER_KEY_PASSWORD` in your build environment. Never print them. Key alias is `appoptimizer`.
4. Preserve a backup of the release keystore securely. The v1 key is outside Git in the development user's `.app-optimizer/release.jks`. Its password file is DPAPI-protected on Windows; copy a recoverable password to a personal password manager before moving machines.
5. Install the **minified signed release**, check authorization reuse, inventory, status, compile, reset, storage, history, D-pad and Home/Back behavior on actual hardware.
6. Merge tested source to `main`, tag a version, attach the signed APK, SHA256 and notes to a private GitHub release. Retain the R8 mapping locally with the release build.

The CI workflow performs unsigned build validation without access to the signing key. It does not deploy to a TV or publish releases.

## Installation rollback

Older release APKs remain attached to version tags. Android normally rejects lower version codes; uninstallation/reinstallation may be necessary and deletes App Optimizer's history/ADB identity. Keep this consequence separate from the Reset command: Reset changes a selected app's ART compilation, while installing an older App Optimizer APK does not change those compilation artifacts.


## Editions

Default `-PfullEdition=false` builds TV (minSdk26, Android8–13 operation guard). `-PfullEdition=true` builds Full (minSdk24, Android7 interpretation mode and Android14+ guarded ART primary-dex support). Both target33 and use the same applicationID, versionCode1 and signing certificate; the Full versionName is1.0.0-full. Build one at a time and copy its release APK before building the other. The CI matrix tests/lints/builds both configurations.

Both are CPU-independent complete APKs with no native libraries or extra runtime downloads. Full broadens compatibility, but only Android12 has been physically validated. This does not add TLS wireless ADB pairing.
