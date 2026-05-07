# Contributing to PHANTOM

Thanks for your interest. PHANTOM is a privacy-first end-to-end
encrypted messenger, and contributions that align with the
[Product Doctrine](docs/doctrine/Product_Doctrine.md) are always
welcome.

## Project philosophy

Three rules shape every review:

1. **Privacy by default.** If a change would allow any infrastructure
   component to read or correlate plaintext user data, it will not
   land. Period.
2. **Auditable over clever.** Readable code that a reviewer can reason
   about beats terse code that saves five lines. This is especially
   true in `shared/core/crypto/` and `services/relay/`.
3. **Transparent.** Architectural decisions are recorded as ADRs under
   [docs/adr/](docs/adr/). Significant changes reference or update an
   ADR in the same PR.

## Before you start

- Read the [Product Doctrine](docs/doctrine/Product_Doctrine.md) and
  [ARCHITECTURE.md](docs/ARCHITECTURE.md).
- Skim [ADR-001 through ADR-006](docs/adr/).
- If your change touches crypto or transport trust, also read
  [docs/threat-model/Threat_Model_v0.md](docs/threat-model/Threat_Model_v0.md).

For large features, open an **issue first** describing the problem
you want to solve. We'd rather talk about the shape of the solution
before you spend a weekend on a PR we can't merge.

## Development setup

### Android + shared core (Kotlin Multiplatform)

- JDK 21 (we pin Eclipse Temurin in `apps/android/build.gradle.kts`).
- Android Studio Narwhal (2026.1) or later, or any IDE with the
  Kotlin 2.2 plugin.
- Android SDK: compile 35, min 26, target 35.
- For instrumented crypto tests you'll need a running AVD (see
  `./gradlew :shared:core:crypto:connectedDebugAndroidTest`).

Build the debug APK:

```bash
./gradlew :apps:android:assembleDebug
```

### Relay (Rust)

- Rust 1.83+ stable. `rustup update stable`.
- For local runs without Docker: `cd services/relay && cargo run`.

### Deployment

See [deploy/README.md](deploy/README.md) for VPS setup, Caddy
config, and the Universal Links assetlinks flow.

## Workflow

1. Fork the repo on GitHub.
2. Create a descriptive branch:
   - `feat/<area>-<short-desc>` for features
   - `fix/<area>-<short-desc>` for bug fixes
   - `chore/<short-desc>` for infra / docs / tooling
3. Keep commits small and focused. We use
   [Conventional Commits](https://www.conventionalcommits.org/):
   - `feat(crypto): add Sealed Sender wire format`
   - `fix(relay): drop duplicate envelopes by message id`
   - `chore(deploy): bump Caddy to 2.9`
   - `docs(architecture): document data flow`
4. Reference an ADR or issue in the PR description when relevant.
5. Run the test suite and lint before pushing:
   ```bash
   ./gradlew :apps:android:assembleDebug :apps:android:lintDebug
   ./gradlew :shared:core:crypto:connectedDebugAndroidTest   # needs emulator
   cargo test  --manifest-path services/Cargo.toml
   cargo build --manifest-path services/Cargo.toml --release
   ```

## Code style

- **Kotlin:** follow the standard Kotlin style guide. A Spotless
  integration is on the TODO list; in the meantime use
  `Ctrl+Alt+L` in IntelliJ/Android Studio.
- **Rust:** `rustfmt` is enforced. Run `cargo fmt --all` before
  pushing.
- **Markdown:** hard-wrap at 72 columns in `docs/` and policy files.
- **Naming:** avoid abbreviations that aren't already domain-standard.
  `messageKey` beats `msgK`. `ratchetPrivateKey` beats `rpk`.

## Testing requirements

- **Crypto and security changes require a test.** Either an
  instrumented test under
  `shared/core/crypto/src/androidInstrumentedTest/` (preferred for
  libsodium-backed logic) or a `commonTest` that does not depend on
  native binding.
- **Relay changes require at least one `#[tokio::test]`** in the
  same file as the change.
- **UI changes must be exercised in a running debug build** before
  the PR is marked ready. Screenshots or a short screen recording
  in the PR description are appreciated.

## Review process

- **Docs / chore:** one reviewer, turnaround 1–2 days.
- **Feature / bug fix in app code:** one reviewer, turnaround 2–4
  days.
- **Anything in `shared/core/crypto/`, `services/relay/`, or trust
  boundaries:** two reviewers, one of whom is familiar with the
  Signal Protocol. Turnaround 3–7 days. Please be patient — we'd
  rather take an extra week than land a subtle vulnerability.

## Licensing

By contributing, you agree that your contribution will be released
under **AGPL-3.0-or-later** — the project-wide license recorded in
the top-level [`LICENSE`](LICENSE) file. AGPL-3.0 §13 (Remote Network
Interaction) is load-bearing for the relay's privacy promise: it
forces any modified network-served version to publish its source.
See [ADR-006](docs/adr/ADR-006-Crypto-Library-Decision.md) for the
full rationale. An optional commercial dual-licensing arrangement
exists for white-label or B2B deployments that cannot ship under
AGPL terms; that path does not affect open-source contributions.

## Getting in touch

For most contributor questions, please open a GitHub issue or draft
PR — public discussion is the default and helps everyone learn from
the answer. Use email when an issue would not be appropriate:

| Topic | Address |
|---|---|
| Security vulnerabilities (do **not** open a public issue) | `security@phntm.pro` |
| Privacy / GDPR / data-handling questions | `privacy@phntm.pro` |
| Legal correspondence, DMCA | `legal@phntm.pro` |
| Code of Conduct reports | `abuse@phntm.pro` |
| Anything else | `support@phntm.pro` |

See [SECURITY.md](SECURITY.md) for the full disclosure policy and
[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for community-conduct
expectations.

## Thank you

Early contributors will be named in the Kickstarter campaign and in
the credits screen of the v1.0 release (opt-in).
