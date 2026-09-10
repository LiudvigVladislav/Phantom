# Android Host Tests

Run the complete Android JVM suite, without a phone, emulator or APK:

```sh
bash scripts/test-android-host.sh --offline
```

Use the project's JDK 21 and Android SDK setup. Omit `--offline` when the
dependencies have not yet been downloaded. The runner forces task execution
without the build cache, attempts both engines even if one fails, and returns
nonzero if either invocation fails. It does not record or verify goldens.

## Engine Boundaries

- `phantomHostTestEngine=regular` excludes the JUnit category
  `phantom.android.testing.PaparazziTestEngine`. All other tests share one JVM
  (`forkEvery=0`, `maxParallelForks=1`), retaining cross-test leak detection.
- `phantomHostTestEngine=paparazzi` includes that category and uses one JVM per
  class, preserving the existing LayoutLib determinism policy.
- Every Paparazzi test class must carry that category, including parameterized
  classes. It is not selected by filename suffix or an allowlist of test names.
- The two category selections are complementary. Both halves are required for
  a full-suite result. Reports are kept separately under
  `apps/android/build/test-results/testDebugUnitTest-{regular,paparazzi}`.
- Omitting the property intentionally retains the historical mixed invocation
  for reproduction, not a supported full-suite acceptance command. On the
  pinned dependencies it can mix Robolectric native registration and LayoutLib.
  Existing `recordPaparazzi*` / `verifyPaparazzi*` commands are unchanged; they
  cannot be combined with the `regular` engine.

The runner accepts only `--offline`, `--info` and `--max-workers=N`. It rejects
test filters, so a filtered invocation cannot accidentally be called a full run.
For focused work use the Gradle task directly with an explicit engine.

## Model State Isolation

`ModelSnapshotRule` is used only by same-thread controller tests which mutate
Compose state without a Compose test rule. Each test enters a mutable snapshot,
observes its own transitions and discards the snapshot in `finally`. Assertions
and failures are unchanged. It must not wrap UI/recomposition tests or model
writes dispatched onto another thread: those require a different lifecycle.

Regression: the following existing tests must run together in one ordinary
test JVM, without per-class forking or debugger interventions:

```sh
./gradlew :apps:android:testDebugUnitTest -PphantomHostTestEngine=regular \
  --tests phantom.android.screens.onboarding.v2.FinaleIdentityCreatedTest.renders_identity_created_title_and_on_device_explanation_and_continue_only \
  --tests phantom.android.screens.onboarding.v2.OnboardingV2FinalizeContractTest.first_success_transitions_idle_working_persisted_complete \
  --tests phantom.android.screens.onboarding.v2.OnboardingV2RepairRequiredScreenTest.screen_renders_error_heading_body_and_exit_button \
  --rerun-tasks --no-build-cache --no-daemon
```

`ModelSnapshotRuleTest` additionally checks visible in-test writes, no published
write after success/failure, propagation of the original assertion error, and
snapshot disposal. Only its disposal counter opts into an internal Compose API;
the rule itself uses public APIs.

## Scope

This changes host test organization, not application behavior or design.
No timeouts, assertions, ignored tests, dependencies or golden images are
changed to obtain a passing result. Host qualification does not replace device
validation of service shutdown, real sockets, network changes or privacy modes.
