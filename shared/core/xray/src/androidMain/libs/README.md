# libXray vendored artefacts

`libXray.jar` here, plus `libgojni.so` under `../jniLibs/<abi>/`, are the
unpacked contents of the `libXray.aar` produced by
`.github/workflows/build-libxray.yml`. They are vendored (committed) rather
than fetched at build time so a clean clone of PHANTOM compiles without an
out-of-band download.

## Why split, not a single .aar

AGP refuses to bundle a local `.aar` inside another AAR (`hasLocalAarDeps`
check), and every `:shared:core:*` module is itself an AAR. Splitting
libXray into its constituents (a thin Java wrapper jar plus the four-ABI
JNI library) sidesteps that restriction with no semantic change.

## How to refresh

When XTLS/libXray ships a release we want to pick up:

1. Trigger **Build libXray.aar** in GitHub Actions
   (`.github/workflows/build-libxray.yml`) — pass the desired tag/SHA via
   the `libxray_ref` input. Wait ~15 minutes for the run to succeed.
2. Download the `libXray-<ref>-<run>` artefact from the run page; it
   contains a single `libXray.aar`.
3. Unpack it into this repo:

   ```bash
   AAR=/path/to/libXray.aar
   unzip -o -q "$AAR" -d /tmp/libxray-unpack
   cp /tmp/libxray-unpack/classes.jar shared/core/xray/src/androidMain/libs/libXray.jar
   for abi in arm64-v8a armeabi-v7a x86 x86_64; do
       cp /tmp/libxray-unpack/jni/$abi/libgojni.so \
          shared/core/xray/src/androidMain/jniLibs/$abi/
   done
   ```

4. `./gradlew :shared:core:xray:assemble` to verify, then commit the
   updated artefacts in a single commit referencing the source SHA.

## Provenance

- **Current vendoring (2026-06-06 — Arm G repair):** libXray @
  `9a86646da8d8` (2026-05-12 "Merge pull request #120 from
  Jolymmiles/feat/go-modules-semver-mirror"), built via `gomobile
  bind` with **NDK r26d** (override via the `ndk_version` workflow
  input added in the same session), Go 1.26.x (pinned by
  libxray-src/go.mod via go-version-file). Workflow run:
  <https://github.com/LiudvigVladislav/Phantom/actions/runs/27051293410>.
  Bundles Xray-core `v1.260327.0` (= 26.3.27 stable release)
  matching the server image deployed at `:8443`.

  **Why this specific ref + NDK combination.** RC-DIRECT-STABILITY1
  §14 Arm G field test 2026-06-06 found that the previous attempt
  to vendor libXray HEAD `f6ce61228b56` (2026-05-23 main HEAD) shipped
  Xray-core 26.5.9, which our server (running 26.3.27) silently
  rejected at Reality handshake AND which broke even matched
  26.5.9 ↔ 26.5.9 Docker A/B test (timing out at 15 s). The repair
  required matching the Android client DOWN to Xray-core 26.3.27,
  not dragging server up to 26.5.9. Three libXray commits in the
  2026-05-04 → 2026-05-12 window have clean `xray-core v1.260327.0`
  pin AND modernized build script: `452c02e3caef`, `4202109c026a`,
  `9a86646da8d8` — picked the latest (`9a86646da8d8`) for most
  fixes. NDK r26d (not r27c) chosen because the libXray build
  scripts in this window still invoke `<abi>-android21-clang`
  wrappers that shell out to the standalone `clang` symlink that
  NDK r27c removed. r26d ships the symlink. The resulting artefact
  is NOT 16-KB-page-aligned for Play Store preflight against
  Android 15+; this is acceptable because the artefact is a repair
  vendoring for §14 Arm G WS-realtime experiment, NOT a release
  build. A future XRAY-VERSION-LOCK1-enabled refresh will close
  the toolchain side of this question along with the version side.

  **API drift restored to 3-arg.** This libXray ref's
  `RunXrayFromJSONRequest` DTO has `getDatDir` + `getMphCachePath`
  + `getConfigJSON` (same as the original 2026-05-07 vendoring's
  shape). `XrayServiceFactory.android.kt` carries that contract.
  The brief 2-arg variant on 2026-05-23 HEAD is no longer shipped.

  Follow-up infrastructure track: XRAY-VERSION-LOCK1 (memory pointer
  `project_next_track_xray_version_lock1_2026_06_06.md`) — pins both
  `XRAY_CORE_REF` and `LIBXRAY_REF` in `deps/xray.version`, adds CI
  drift check and scheduled-update PR so this manual refresh becomes
  the emergency path rather than the normal path. NDK toolchain
  compatibility (r26d vs r27c) is part of the matrix the lock should
  pin.

- **Refuted vendoring (2026-06-06, branch state only — never merged):**
  libXray @ `f6ce61228b5630f7bcf3c3c9a19d7e1db50b88d1` (2026-05-23
  main HEAD), built via NDK r27c, bundling Xray-core 26.5.9. Workflow
  run: <https://github.com/LiudvigVladislav/Phantom/actions/runs/27033765713>.
  Was the first refresh attempt; refuted by smoke test which reproduced
  the exact same Reality handshake rejection as the original 2026-05-07
  vendoring (with `xrayVersion()` runtime API confirming Xray-core
  26.5.9 vs server 26.3.27). Replaced by the repair vendoring above.

- **Initial vendoring (2026-05-07):** libXray @ `main` HEAD at that
  date, built via `gomobile bind` with NDK r27c, Go 1.26.2. Stage
  5E.B.1 first vendoring; production-validated Stage 5E.B.5
  2026-05-07 for HTTP Reality path through Caddy + relay. Reality
  WS path (Arm G test surface) NOT validated at that time. Bundled
  Xray-core whatever main HEAD's go.mod resolved to that day.
