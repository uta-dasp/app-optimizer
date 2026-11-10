# Android behavior and limits

App Optimizer issues Android's package compilation commands locally. It does not change APK contents, clear application data, change account sign-ins, or tune video decoders or network connections.

## Compilation and status

Full compilation requests `pm compile -m speed -f PACKAGE`. The `speed` filter verifies DEX code and compiles its methods ahead of time. Profile guided requests `speed-profile`, which focuses on methods in an available profile. A missing or limited profile can leave little code compiled. Either mode can increase storage use; neither guarantees a perceptible improvement in every app. See [Configure ART](https://source.android.com/docs/core/runtime/configure).

The app reads `dumpsys package dexopt` rather than treating a successful command as proof that all code was compiled. Status reflects the filters Android reports at the most recent refresh:

| Display | Meaning |
| --- | --- |
| Full | All reported filters for the package are `speed` or the equivalent full filter. |
| Profile guided | The reported filter is profile based. This is already an optimized state, rather than an unoptimized app. |
| Verified only | Android reports `verify`, which validates DEX without ahead-of-time compilation. |
| Mixed | Different code paths or architectures report different filters. |
| Not compiled | Android reports execution from the APK or an equivalent noncompiled state. |
| Unknown | No recognized, readable compilation record was exposed. This does not prove absence of optimization. |

The record can change after app updates or Android's background optimization. Debuggable apps can remain Verified only after a successful request: Android 12's package optimizer deliberately uses its safe-mode filter for debug builds, including shell requests. See the [Android 12 implementation](https://android.googlesource.com/platform/frameworks/base/+/android12-release/services/core/java/com/android/server/pm/PackageDexOptimizer.java).

## Removing compiled code and measurements

App Optimizer's reversal action requests `pm compile -m verify -f PACKAGE`. This replaces ahead-of-time compiled methods with verification-only artifacts while retaining learned profile data. It preserves app data and sign-ins. It is **not** a snapshot restore or an exact return to the former profile/compilation state. Android can compile the app again during normal use or background maintenance. Verification artifacts can still consume some storage, so an exact return of all measured space is not promised.

Google also documents `pm compile --reset PACKAGE`, which clears profile data as part of returning to an install state. A hardware test on this Hisense Android 12 firmware rejected that operation with `SecurityException: Only the system can clear all profile data`. App Optimizer therefore uses the explicit verification-only action instead; it does not silently attempt to clear profiles. See [ART compilation and reset commands](https://source.android.com/docs/core/runtime/jit-compiler). Android 12's [forced optimizer implementation](https://android.googlesource.com/platform/frameworks/base/+/android12-release/services/core/java/com/android/server/pm/PackageDexOptimizer.java) forces a compiler-filter update when `-f` is requested, rather than retaining the previous speed artifact merely because one already exists. The app rereads the observed filter afterward.

Space changes are the difference between `/data` free-space readings before and after an operation. A positive increase means less free space; a decrease means free space was recovered. This is a **net filesystem observation**, not a direct measurement of compiled artifacts: app caches, logs, downloads, garbage collection and other activity can affect it. No exact per-app artifact size is promised without filesystem access Android normally restricts.

Commands execute one at a time. Stop after current finishes the in-flight command before preventing the next one. A broken connection, timeout or process interruption can leave an uncertain outcome; the app does not automatically retry or replay it. Refresh the observed compilation state before deciding what to do next.

## Local connection

This version connects only to this TV's legacy ADB endpoint at `127.0.0.1:5555`. It does not expose a shell interface, connect to other devices, or implement Android's TLS wireless-pairing protocol. A TV must support this local legacy debugging connection; enabling Wireless debugging alone is not equivalent.

Initial Connect may trigger Android's own authorization dialog. Selecting Always allow preserves the app's authorization until it expires or is revoked. Subsequent commands reconnect using the stored key without presenting a consent flow. If authorization is unavailable, the app shows setup guidance instead. Debugging must remain enabled. See [Android Debug Bridge](https://developer.android.com/tools/adb).

The private ADB key is encrypted using an Android Keystore key and stored in backup-excluded private app storage. Clearing App Optimizer's storage or uninstalling it removes its local credential; it does not revoke an already trusted public key on the TV. Android's Developer options can revoke debugging authorizations, which can also affect other authorized clients. No accessibility service, overlay, root access, cloud account or background polling is required.


## Full edition version branches

Full additionally supports Android7 commands, using `interpret-only` for reversal because that release has no `verify` filter. The status is Interpreted when recognizable; JNI stubs can remain. The legacy freeform status format may show Unknown. Source: [Android7 compiler filters](https://android.googlesource.com/platform/art/+/android-7.0.0_r1/runtime/compiler_filter.cc).

For Android14+, Full reads `pm art dump` and requires advertised `--primary-dex` support before sending compile/verify commands. This avoids ART's default expansion into app dependencies and scopes badges to primary APK and split code, excluding secondary dynamic dex. See [ART shell command implementation](https://android.googlesource.com/platform/art/+/ace60f2847b3511c466125d53dc54f27d44456ec/libartservice/service/java/com/android/server/art/ArtShellCommand.java) and [dump format](https://android.googlesource.com/platform/art/+/4b88637ae7a8fa459bcd3a22452770ec4858772f/libartservice/service/java/com/android/server/art/DumpHelper.java). This branch is covered by fixtures and source review, not physical Android14+ validation.
