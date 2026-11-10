# Verification record — 2026-11-10

## Observed on hardware

Hisense SmartTV4K, Android12/API31, local legacy ADB on port5555,1920×1080 output at320dpi. App Optimizer is independently packaged as `io.github.utadasp.appoptimizer`; no launcher/default-Home or network settings were changed.

- Installed the minified, signed release APK.
- User approved the app's ADB key with Always allow. A signed APK update preserved the encrypted key and reused authorization without another consent dialog.
- Read23 user apps, with13 fully compiled from the previous session and the remainder displaying their actual reported filters. Sideloaded view includes15 entries including App Optimizer itself, which is protected from operations.
- Found a TV-specific `df /data` bind-mount alias; fixed the parser using the exact observed output and an ambiguity-rejection test.
- Used the app's UI to compile the inactive original Free My TV (`io.freemytv`), read `Success` and independently confirmed `status=speed` in Android's dump.
- The full post-install `--reset` test failed with `SecurityException: Only the system can clear all profile data`. The released Reset action therefore explicitly requests verify-only instead. No automatic fallback/retry was used.
- Used the corrected app Reset action: observed `Success`, `Full → Verified only`, and approximately1MiB net space recovered.
- Recompiled from the app: observed `Success`, `Verified only → Full`, and approximately1MiB net space added. The inactive app's initial full-compilation state was restored.
- Requested stop-after-current during a two-app batch; the in-flight app finished normally and the batch reported stopped. Both apps had already begun by the time the stop was pressed; this does not constitute hardware evidence of skipping a still-pending third item.
- History recorded before/after filters, request outcomes, elapsed time and measured deltas. A debug Smart Lab request succeeded but remained verification-only, correctly avoiding a Full badge.
- D-pad focus/confirmation navigation and Back dismissal exercised with injected Android key events. Native controls and spacing visually checked at960×540dp. These are automated remote-equivalent events, not a claim that a human tested every physical remote button.
- Foreground sample after connection: about22MiB total PSS. This is a snapshot, not a maximum memory guarantee. No continuous polling, boot receiver, foreground service, overlay or accessibility service is present.

## Source and automated validation

Unit tests cover actual legacy/modern dumps, split/ABI mixed status, missing/unknown records, modern primary/secondary scope, package validation and command injection rejection, exact exit markers, and storage alias/malformed/ambiguous data. Release lint and R8/resource shrinking are required for both editions. The release is signed with a private key excluded from Git.

## Full edition compatibility limits

Android7 and14+ handling is based on primary AOSP source, command-capability checks and parser/command fixtures. **Only Android12 has been physically tested.** This does not promise operation on every firmware or prove service enrollment/permissions on another TV. Both editions require a working legacy loopback ADB endpoint; TLS wireless-debugging pairing is not implemented. Android7 status can be Unknown because its older freeform dump is deliberately not guessed. Android14+ actions/status target primary APK and split dex; secondary dynamic dex files are excluded. Unsupported modern primary-dex capability stops the operation before sending it.

Storage is a net filesystem difference that includes unrelated activity, not an exact compiled-artifact measurement. Reversal preserves learned profiles and is not a snapshot restore. Android can compile again later.
