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

Initial vendoring: libXray @ `main` (2026-05-07), built via
`gomobile bind` with NDK r27c, Go 1.26.2 — see the workflow run linked from
the introducing commit for full reproducibility metadata.
