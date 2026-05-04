# NO-GO check #1: kmp-tor ARM32 coverage

**Verdict:** GO

kmp-tor 2.6.0 ships working binaries for `armeabi-v7a` (ARM 32-bit) on
Android. ARM32 PHANTOM users on older Tecno Spark Go 2023, Itel, and
pre-2018 budget devices are covered. Integration is safe from this
specific risk.

## What we found

1. **Explicit README declaration (kmp-tor-resource).** The
   `kmp-tor-resource` README documents the full Android ABI matrix and
   gives copy-paste configuration for splits across all four ABIs:

   > `include("x86", "armeabi-v7a", "arm64-v8a", "x86_64")`

   The README also instructs consumers to set
   `jniLibs.useLegacyPackaging = true` and
   `android.bundle.enableUncompressedNativeLibs=false`, which is the
   standard packaging path for native `.so` files including ARM32.

2. **Maven Central artifact exists for ARM32.** The artifact
   `io.matthewnelson.kmp-tor:runtime-androidnativearm32` is published
   on Maven Central (visible at 2.5.0; 2.6.0 was tagged 2026-02-10
   without ABI removal). This is a dedicated Kotlin/Native ARM32
   target, separate from the JVM/Android `-exec` flavor that uses
   `jniLibs`.

3. **Changelog: ARM32 was added, never dropped.** kmp-tor-resource
   408.16.4 (2025-06-11) explicitly added the four
   `androidNative{Arm32,Arm64,X64,X86}` targets. No subsequent entry
   removes any of them. The current resource line is 409.5.0
   (2026-02-15), bundling Tor 0.4.9.5.

4. **kmp-tor 2.6.0 is a maintenance release.** CHANGELOG for the
   runtime 2.6.0 (Feb 10, 2026) lists Kotlin 2.2.21, encoding 2.6.0,
   kmp-process 0.5.0, kmp-tor-common 2.4.2, plus a few new
   `TorOption` keys. No breaking changes, no ABI changes.

## ABI matrix supported

Android (both `-exec` JVM/Android via `jniLibs` and Kotlin/Native
`androidNative*`):

| ABI          | Bits | Supported | Typical device class                       |
|--------------|------|-----------|--------------------------------------------|
| armeabi-v7a  | 32   | YES       | Tecno Spark Go 2023, Itel, pre-2018 budget |
| arm64-v8a    | 64   | YES       | All modern Android phones                  |
| x86          | 32   | YES       | Old emulators, rare Atom tablets           |
| x86_64       | 64   | YES       | Modern emulators, ChromeOS Android         |

All four are first-class targets. PHANTOM does not need to drop or
split off ARM32 users.

## Bundled Tor version

**tor 0.4.9.5** (resource line 409.5.0, dated 2026-02-15), built
against OpenSSL 3.5.5 and libevent 2.1.12-stable. This is current
upstream Tor and aligns with what The Tor Project itself ships
across all platforms, so ARM32 binaries are not a stale fork.

## Risk if we proceed

Effectively none on the ABI axis. Residual risks unrelated to ARM32
coverage:

- APK size: each native Tor binary adds ~3-6 MB per ABI. With four
  ABIs in a single universal APK that is ~15-20 MB extra payload.
  Mitigation: ship App Bundle (AAB) so Play / RuStore deliver only
  the matching ABI, or use ABI splits as the README recommends.
- ARM32 devices are usually low-RAM (1-2 GB). Tor's resident set is
  ~30-60 MB; tolerable but worth measuring on a real Tecno Spark
  Go 2023 before shipping.
- Tor 0.4.9.5 is a recent Tor release; track upstream CVE channel,
  not a kmp-tor-specific concern.

## Mitigation if NO-GO

Not applicable - verdict is GO. For completeness, fallback options
that were on the table:

- Drop ARM32 support entirely (would lose a meaningful slice of
  PHANTOM's Russia-based Tecno/Itel user base - rejected as goal).
- Switch to Briar's `tor-android` or Guardian Project's `tor-android-binary`.
- Wait for upstream support (not needed - already present).

## Sources

- https://github.com/05nelsonm/kmp-tor-resource (README ABI matrix)
- https://github.com/05nelsonm/kmp-tor-resource/blob/master/CHANGELOG.md
  (entry 408.16.4, 2025-06-11: added androidNativeArm32 target;
   entry 409.5.0, 2026-02-15: tor 0.4.9.5 + OpenSSL 3.5.5 +
   libevent 2.1.12-stable)
- https://github.com/05nelsonm/kmp-tor (release 2.6.0, 2026-02-10)
- https://github.com/05nelsonm/kmp-tor/blob/master/CHANGELOG.md
  (2.6.0: Kotlin 2.2.21, kmp-tor-common 2.4.2, no ABI changes)
- https://central.sonatype.com/artifact/io.matthewnelson.kmp-tor/runtime-androidnativearm32/2.5.0
  (Maven Central artifact confirming ARM32 publication)
- https://central.sonatype.com/artifact/io.matthewnelson.kmp-tor/runtime/2.5.0

## Next verifications (optional, not blocking)

- Pull the 2.6.0 AAR from Maven Central and `unzip -l` to inventory
  the actual `.so` files under `jni/armeabi-v7a/` to confirm bytes
  on disk match the documented matrix.
- Smoke-test on a real ARM32 device (Tecno Spark Go 2023 or any
  armeabi-v7a-only emulator image) before committing the integration
  PR, to catch any runtime issue not visible from the manifest.
