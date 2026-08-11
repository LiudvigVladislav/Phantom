# Direct WSS Diagnosis — Yota-First — Contract Sheet

**Branch:** `android/direct-wss-diagnostics-2026-08-11` (client, off Phantom-android-ui HEAD `86c2de99`).
**Relay tree examined:** `D:/VL Stories Studio/Phantom` HEAD `d63366b6` on branch `fix/relay-queue-durability-pr2` — the PR #397 (M6-3 queue-durability) code. Per architect 2026-08-11 answer to §8-Q1, PR #397 IS merged into upstream `master` (this local `master@fadc5c9c` is stale). VPS-side deployment is still unconfirmed.
**Gate:** WSS-0 review required before any runtime change, APK build, VPS action, push, or long test.

**§0 — status.** WSS-0 doc-only rounds landed as `bc7051ed` (initial), `4e841b5b` (REDLINE-1), `bf748db3` (REDLINE-2 — architect FINAL GREEN). WSS-1 implementation round is now in progress under architect implementation locks (see §11).

## §11 — Implementation locks (architect FINAL GREEN on REDLINE-2)

The following four locks close the choice-points left open in earlier rounds. They are BINDING for WSS-1 implementation — no re-litigation without a new architect direction.

1. **Command component = BroadcastReceiver only.** The debug command surface is ONE `BroadcastReceiver` declared solely in the debug `AndroidManifest.xml` overlay. `Activity` is NOT used. The receiver runs on explicit component invocation (`am broadcast -n <APP_ID>/.diagnostic.DiagnosticCommandReceiver …`) and is physically absent from the release APK's merged manifest.

2. **Outer Direct enforcement = Method (b) fail-closed check.** `TransportManager` is NOT modified. If the actually-selected outer arm at send time is not `direct`, `HybridRelayTransport.send` refuses to dispatch, emits `sender_transport_decision outer_transport=<actual> inner_route=<pinned> dispatched=false`, and the verifier stamps the matrix cell `BLOCKED`. Every send under a WSS or REST pin MUST log `outer_transport=direct` in the winning `sender_transport_decision` event.

3. **Conversation selection = automatic, not operator-supplied.** The `contact_alias` extra is REMOVED from the receiver whitelist. After a clean bootstrap, each device has exactly ONE paired conversation whose peer is the other device in the matrix. The receiver's `send` subcommand queries the local conversation store, requires exactly one matching paired conversation, and fails-red otherwise. The operator cannot influence which peer receives the send.

4. **Additional focused tests (locked, on top of §5):**
   - `wss_diag_unknown_extras_and_subcommands_are_rejected` — any extra outside the strict whitelist, or any `subcommand` outside the enum, exits the receiver red without touching `sendMessage`.
   - `wss_diag_send_calls_production_api_exactly_once_with_no_external_text` — the `send` subcommand invokes `MessagingService.sendMessage` exactly once with text derived internally as `YOTA-WSS-${cell_id}-${sequence}`; no text extra is accepted or consumed.
   - `wss_diag_pin_write_read_round_trip_through_app_code` — writes via `DiagnosticCommandReceiver` (in-app EncryptedSharedPreferences) → reads via the same store → observes the value in `DiagnosticTransportGuard`. No ADB file write is possible or supported.
   - `wss_diag_receiver_present_in_debug_manifest_and_absent_from_release_manifest` — introspects the merged manifest for both variants (via `manifest-merger` output files under `build/intermediates/merged_manifests/`), asserts the receiver appears in debug and NOT in release.

## §12 — WSS-1 Mac-audit repair block (RED → LOGICAL GREEN)

Architect Mac audit on `c910dee6` returned **RED / NO ADB INSTALL / NO YOTA MATRIX**. This block closes every finding in ONE consolidated repair. During repair the loop is compile + focused tests only — NO full suite, Paparazzi, APK, ADB, device install, or repeated handoff pack. After LOGICAL GREEN: one `assembleDebug --rerun-tasks`, one APK, one checksum, one final operator package + Mac dry-run against synthetic fixtures. Only then may the destructive bootstrap + Yota pass begin.

Findings resolved:

- **P0-1 (verifier false GREEN).** `verify-evidence.py` rewritten as closed-schema. Required files, expected 8-cell matrix (with BLOCKED marker for REST when preflight said `disabled`), exactly 5 unique correlation IDs per non-blocked cell, `run_id` match, expected `emitter_id` per device role, no duplicate correlation IDs, pin coverage over each envelope's wall-clock window, actually-observed `outer_transport=direct`, `inner_route` matching the cell's pin, presence of `diagnostic_session_started` on both devices. Exit codes: `0` = evidence GREEN + product OK; `2` = evidence GREEN + product RED (successful diagnostic capture); `1` = integrity/tooling failure. Fixture-driven Python tests cover every case P0-1 through P0-5.

- **P0-2 (pin can disappear silently).** Debug-only `DiagnosticTransportPinStore` persists `pin + run_id + cell_id + emitter_id` via plain `SharedPreferences` (`diagnostic_transport_pin` file, `MODE_PRIVATE`, debug source set only). `DiagnosticBootInitProvider` restores the state before messaging init and emits `WSS_DIAG event=diagnostic_session_started restored=true|false pin=… …`. Every `send` subcommand validates that the caller's `cell_id` matches the persisted `cell_id` and fails-red otherwise. Matrix runner + verifier reject a session restart without a matching `diagnostic_session_started` covering the envelope's wall-clock window. `diag-cmd.sh clear` explicitly clears the persisted state at end-of-run.

- **P0-3 (outer_transport asserted, not observed).** `DiagnosticTransportGuard.outerArmReader` is a nullable `() -> String` populated by debug boot init with a lambda reading `PhantomApplication.container.transportPreferences.privacyMode` and mapping `Standard → direct`, `Private → tor`, `Ghost → reality`. `HRT.send` calls the reader and emits the observed value on `sender_transport_decision`. Under `Pin.WSS` or `Pin.REST`, if the observed value is not `direct`, the send is refused: `dispatched=false`, `outcome_flag=send_error`. Production chain order in `TransportManager` is not modified.

- **P0-4 (bootstrap ordering impossible).** Split into three entry points that do not require each other's completion:
  1. `run-yota-wss-diagnostic.sh bootstrap --fresh` runs standalone — detects devices, creates a `bootstrap` scratch dir, uninstalls, SHA-256-verifies the APK, installs, sets `emitter_id` on each via `diag-cmd.sh set_emitter_id`, prints manual onboarding + QR-pairing instructions, exits.
  2. Operator manually onboards + pairs.
  3. `run-yota-wss-diagnostic.sh preflight` (measurement preflight) creates a fresh evidence directory, verifies the debug variant, exactly one paired conversation per device, dual-SIM Yota confirmation (typed), radio checklist confirmation (typed), REST capability probe (Method B), clock skew. Fails-red on any mismatch.

- **P0-5 (120-s contract not implemented).** `run-matrix.sh` polls per-envelope: after firing each envelope's `send` subcommand it reads the joined logs, waits until the four delivery signals are present OR the wall-clock difference between `sender_enqueue.wall_utc_ms` and `now` exceeds 120 000 ms, whichever comes first. The verifier classifies missing signals as `PENDING` when the newest event is under 120 s from `sender_enqueue`; only after 120 s is the classification `Unresolved`. Cell arithmetic stays exactly 5 envelopes.

- **P0-6 (emitter + Yota not enforced).** Preflight (bootstrap phase) calls `diag-cmd.sh set_emitter_id --emitter-id phone` on the phone serial and `--emitter-id emulator` on the emulator serial, then verifies via `diag-cmd.sh health` that the values stick. Measurement preflight requires the operator to type the literal word `YOTA` at the dual-SIM confirmation prompt to proceed. The radio checklist (Wi-Fi OFF / VPN OFF / private DNS OFF / auto-switch OFF / other-SIM data OFF) is enumerated interactively; each item requires typed confirmation and lands in `preflight.json`. Verifier rejects any envelope whose `emitter_id` does not match the device role expected by the cell direction.

- **P0-7 (Recovered evidence absent).** `Recovered` classification is REMOVED from the WSS-1 verifier. First-pass distinguishes only `Delivered once` / `Unresolved` / `PENDING` / `BLOCKED`. `attempt` + `session_epoch` + `sender_ack_watchdog_requeued` remain undocumented emit sites in the WSS-1 code and are NOT expected in the WSS-1 evidence. A follow-up block may introduce genuine breadcrumb instrumentation via a shared/core-transport bridge extension — not in scope here.

### §12.2 — Round-2 audit repair (2026-08-12)

Third Mac audit REDLINE closed. Compile + focused tests only.

- **P0-1 receiver silently rejects `checkpoint` and `paired_count_report`.** The extras-whitelist map was missing entries for both subcommands added in the Round-1 repair. Preflight physically could not fire them. Fixed by adding empty-extras entries and pinning the invariant with two new contract tests: (a) every ALLOWED_SUBCOMMAND has an ALLOWED_EXTRAS_BY_SUBCOMMAND entry; (b) no stale entries for removed subcommands.
- **P0-2 verifier false GREEN in three cases**.
  - `recipient_message_persisted role=matrix` — added strict role check: recipient-event names (`recipient_deliver_received`, `recipient_message_persisted`, `recipient_ack_deliver_sent`) MUST carry `role=recipient`, and sender-event names MUST carry `role=sender`. Any mismatch → integrity RED.
  - Recipient events for a wrong `cell_id` — added cell_id scoping to the per-envelope `corr_events` filter. Sender+recipient events now filtered by `correlation_id AND run_id AND cell_id` before classification.
  - Missing `sender_wss_send_returned` / `sender_rest_post_completed` — added mandatory send-completion event matching cell pin. REST pin also requires `relay_acceptance ∈ {accepted, duplicate}`.
- **P0-3 non-canonical matrix accepted.** Introduced `CANONICAL_MATRIX_TRIPLES` (frozen 8-tuple set); missing or extra triples → integrity RED. Cell IDs must match `pin.direction.scenario` shape.
- **P1 numeric-field crash.** Introduced `_safe_int` + `parse_events` returns a `(events, parse_errors)` tuple; malformed `wall_utc_ms` / `monotonic_ms` / `sequence` land in `parse_errors` → integrity RED. Never raises.
- **P1 CID lookup by sequence alone.** `run-matrix.sh:wait_for_send_cid` now matches on `cell_id AND sequence` — a rejected send in a prior cell no longer contaminates the next.
- **P1 host↔device skew.** Preflight now records `host_to_phone_skew_ms` + `host_to_emulator_skew_ms` (Mac host_ms − device_ms, N=5 samples each). Verifier reads both and translates each envelope's device wall to host time via the sender's per-device offset before comparing to `host_now_ms`. Legacy `clock_skew_ms` key removed from manifest schema; missing new keys → integrity RED.
- **P1 exec bits.** All `.sh` and `.py` under `operator-package/` set to `100755` in the git index via `git update-index --chmod=+x`. Mac clone gets executable bits from git without needing a manual `chmod`.

### §12.1 — Round-1 audit repair (2026-08-12)

Second Mac audit REDLINE closed in one consolidated block. No APK / ADB / device install during repair.

- **P0-1 runner unable to complete**: `lib/portable.sh` centralises `now_ms` (Python `time.time_ns()`), `sha256_file` (macOS `shasum` OR Linux `sha256sum`), `count_matches` (single-integer sum across N files), `extract_field`. `run-matrix.sh` uses these. Correlation IDs are now read from a new structured `diagnostic_send_dispatched sequence=<N> correlation_id=<uuid>` event emitted by the receiver on the SOLE `WSS_DIAG` tag — `WSS_DIAG_CMD` is no longer required to be in capture. Pin-active wait uses field-agnostic key/value matching AND aborts the cell if both devices don't ack within a 30-s timeout. `capture-logs.sh` no longer clears the log buffer; preflight fires a new `checkpoint` subcommand AFTER capture starts so `diagnostic_session_started` is guaranteed to land in-stream.
- **P0-2 verifier false GREEN**: v3 rewrite. Every event filtered by `matrix.run_id`. Correlation IDs globally unique per run (not per cell). Pin coverage is a per-sender-device timeline (matching `run_id + cell_id`); prior WSS pin cannot cover a later WSS cell. Contract violations (pin/role/emitter/outer/inner) drop the cell to `Unresolved` — never "Delivered once with warnings". BLOCKED cells require `preflight.rest_capability=disabled` AND cell pin `rest`; illegal BLOCKED → integrity RED. Preflight booleans checked for `True` value (not key presence). 19 Python fixture tests cover every P0-1/P0-5 case listed in the audit.
- **P0-3 120-s boundary matures with host clock**: verifier's `now_wall` is a real host clock (`time.time_ns() // 1_000_000`) with an optional `--host-now-ms` override for fixtures. PENDING before 120 s from `sender_enqueue`, Unresolved after — no dependency on a synthetic later event.
- **P0-4 actual outer transport observed**: `DiagnosticBootInit` installs an `outerArmReader` lambda that reads `container.transportManager.state.value` and returns `direct` | `reality` | `tor` | `probing` | `failed` | `idle` per the actually-selected `ManagerState.Connected(TransportKind)`. Policy preference is not evidence. Prior Private↔Ghost reversal corrected — the diagnostic no longer names arms at all; only the connected `TransportKind` is emitted.
- **P0-5 process/pin evidence internally consistent**: `DiagnosticBootInit` emits ONLY the structured `diagnostic_session_started` event — the earlier raw `Log.i(WSS_DIAG, "diagnostic_boot_init …")` line is gone. New `restored: Boolean` field on the schema replaces the overloaded `dispatched`. `DiagnosticTransportPinStore` now returns Boolean from `writePin` / `writeEmitter` / `clear`; receiver checks the result and refuses to update in-memory state on a failed commit. `handleSend` fail-closed match now requires `persisted.pin != NONE`, `persisted.runId == caller`, `persisted.cellId == caller`, AND `in-memory == persisted` — a process restart that drifted the guard surfaces here.
- **P0-6 caller boundary via manifest permission**: `android:permission="android.permission.DUMP"` on the debug receiver — AMS-boundary enforcement. Only shell (2000) holds DUMP on stock Android; third-party apps cannot deliver broadcasts even with the explicit component name. Prior `Binder.getCallingUid()` gate is removed (unreliable inside `onReceive`).
- **P1 batch**:
  - `paired_count_report` subcommand + preflight enforces exactly 1 paired conversation on each device.
  - `dual_sim_report` is validated (non-empty `operator_numeric` of ≥ 5 digits) BEFORE the YOTA prompt; malformed output aborts preflight.
  - `sha256_file` portable helper replaces direct `sha256sum` calls in `preflight.sh` + `bootstrap.sh --verify` + `install-apk.sh`.
  - Release-manifest opt-out marker approach REPLACED by `DiagnosticSourceSetBoundaryTest` — verifies (a) all debug-only Kotlin files live under `src/debug/`, (b) no `androidMain` file references `DiagnosticCommandReceiver`, (c) debug manifest actually declares `android:permission="android.permission.DUMP"`. Independent of `assembleRelease`.

- **P1 (batched):**
  - `sender_wss_frame_written` renamed to `sender_wss_send_returned` with an explicit doc line stating `dispatched=true` = "wsTransport.send() returned true, i.e. queued to the OkHttp write path"; not proof of frame egress.
  - `diag-cmd.sh` replaces `eval` with a Bash argument array (`args=(...); adb "${args[@]}"`).
  - `DiagnosticBootInitProvider` emits its bootstrap event via `WssDiag.emit(event="diagnostic_session_started", …)` — no raw `Log.i` on other tags.
  - Debug `DiagnosticCommandReceiver` gates every incoming broadcast on `Binder.getCallingUid() == Process.SHELL_UID || Binder.getCallingUid() == Process.ROOT_UID` — third-party apps cannot deliver a broadcast to this receiver even though `exported="true"`. Manifest comment updated.
  - REST capability probe log is captured (via `capture-logs.sh` started at preflight, before the probe fires) so the probe's evidence is preserved in the evidence dir.
  - Verifier exit codes align with runbook: complete evidence + product failure = successful diagnostic (exit 2 + clear message); integrity RED or PENDING = tooling failure (exit 1).
  - `DiagnosticReceiverManifestPresenceTest` no longer skips when the release manifest is absent — it fails with an explicit message asking for `assembleRelease`; a `.release-manifest-not-required.marker` sentinel opts a CI stage out explicitly if needed.
  - Handoff repack removes any `.DS_Store`, restores executable bits on `.sh`/`.py`, and the `assembleDebug.log` reflects `--rerun-tasks` output (not `UP-TO-DATE`).

- `recipient_message_persisted` proves the chat-store write completed for the message row; it does NOT literally prove the message is visible on the recipient's screen at that instant (a fully-composed row that's off-screen or hidden behind chrome still qualifies). Verifier documentation states this scope explicitly.
- The Method-B "controlled fail-closed REST capability envelope" (§8-Q6, §9.6) is a preflight probe. It is **NOT counted in the `8 × 5 = 40` matrix envelopes** and it does NOT consume a `cell_id`. It carries its own `cell_id = "preflight.rest_capability"` and its outcome only stamps `preflight.json.rest_capability = disabled|enabled|unknown`.

The `docs/tracks/direct-wss/` directory is new; the contract sheet, the operator-package spec (§9), and the future observability diff (§7) will live under it.

---

## §1 — Production path map

Every `file:line` reference below is verified against the HEAD listed above. Package paths are project-relative.

### Client (Phantom-android-ui)

| # | Stage | Site | Notes |
|---|---|---|---|
| 1 | User send trigger (text) | `apps/android/src/androidMain/kotlin/phantom/android/screens/chat/ChatScreen.kt:1063-1084` (`InputBar` `onSend` → `container.messagingService?.sendMessage(OutgoingMessage(...))`) | Voice: `finalizeAndSendVoice()` `:363` → `sendAudio(...)` `:427-432` — OUT OF SCOPE per §8-Q2 |
| 2 | Local envelope creation (app model) | UI constructs `phantom.core.messaging.OutgoingMessage` at `ChatScreen.kt:1073-1078`; type at `shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/OutgoingMessage.kt:6-11` | Wire envelope `RelayMessage.Send` built at `shared/core/messaging/.../DefaultMessagingService.kt:1639-1647` |
| 3 | Envelope ID generation | `id = uuid4().toString()` at `ChatScreen.kt:1074`; persisted BEFORE send at `DefaultMessagingService.kt:1574` (insertMessage inside `afterEncrypt`); retry keeps same id at `retryWaitingMessages` `:4318-4344` (`OutgoingMessage(id = m.id, …)` `:4338`) | `EnvelopeId.random()` helper exists at `shared/core/transport/.../EnvelopeId.kt:74` but is UNUSED on text send (TODO at `DefaultMessagingService.kt:1793-1799`) |
| 4a | Local outgoing queue (RAM) | `KtorRelayTransport.pendingOutbox: ArrayDeque<OutboxEntry>` `shared/core/transport/.../KtorRelayTransport.kt:549`; appended `:2079-2081, 2107-2117`; drained by `flushPendingOutbox(mySession)` `:2239-2409` after handshake `:1538` | IN-MEMORY only |
| 4b | Local outgoing queue (persistent) | `SqlDelightMessageRepository` row with `MessageStatus.QUEUED` inserted at `DefaultMessagingService.kt:1581`; row survives process death; `retryWaitingMessages` `:4318` re-issues after reconnect/ticker | Persistent survival substrate |
| 5 | Transport selection (outer chain) | `TransportManager` `shared/core/transport/.../TransportManager.kt:35`, chain walk `:200` (`reorderChain`), per-attempt loop `:100-160` | Chosen ONCE per connect; `PrivacyMode` → strategy at `TransportStrategy.kt:44-49` |
| 5b | Transport selection (WSS ↔ REST) | `HybridRelayTransport.send` re-reads `stateMachine.current` on EVERY send at `apps/android/.../transport/HybridRelayTransport.kt:1055-1081`; enum `RestMode` at `shared/core/transport/.../RestStateMachine.kt:17-46` | Per-send re-select — the "runtime rewalk" (§11) knob for WSS/REST |
| 6 | Direct WSS send API | `KtorRelayTransport.send(message: RelayMessage.Send): Boolean` `:2067-2166`. `true` = `session.send(Frame.Text(...))` (`sendRaw` `:2149`) did not throw — **queued to Ktor/OkHttp write path, NOT relay-acked** | Relay ack arrives separately on the `acks` Flow with `ACK_DEADLINE_MS = 10 000` (`RelayTransportConfig.kt:91`) + `ACK_TIMEOUT_MS = 60 000` (`:30`). Expiry only REQUEUES + reconnects — see §2 for the correct terminal semantics |
| 7 | Direct REST send API | `HybridRelayTransport.sendViaRest(message, mode)` `:1083-1135` → `orchestrator.sendEnvelope(...)` `:1093-1099`; impl `shared/core/transport/.../RestFallbackOrchestrator.kt:1048-1264`; `SendOutcome` sealed class `:4139-4153` | `Accepted`(201) + `Duplicate`(200 replay) → `true` at `HybridRelayTransport.kt:1101-1102`. **`DisabledByCapability` today falls back to WS at `:1103-1113` — this must be OVERRIDDEN in "rest" pin mode (see §6)** |
| 8 | WSS connection state | Ktor `webSocket { }` DSL — NO raw OkHttp `WebSocketListener`. Handler at `runReconnectLoop` `KtorRelayTransport.kt:1479-1556` (onOpen equiv `:1496`, clean close `:1547-1556`, failure `catch` `:1573-1597`, `finally` summary `:1598-1671`) | `WsSessionLifecycleEvent.Ended` carries `closeOrigin` (local/remote/error/unknown/synthetic `:1605-1611`), `closeError` `:1627`, `okhttpPingTimeoutDetected` `:1629-1630`, `pendingAcksAtClose`, `durationMs`, `inboundFrames`, `sessionEpoch`. `state: StateFlow<TransportState>` in `RelayTransport.kt:11` |
| 9 | WSS timeout / ping | NO `withTimeout` wraps `send()` itself. Per-envelope ack watchdog `startAckWatchdog(...)` `:1523`; expiry `:1862-1878` **requeues + forces reconnect only** (does NOT flip any state to a terminal failure). OkHttp `pingInterval(15 000 ms)` `shared/core/transport/src/androidMain/kotlin/phantom/core/transport/RelayTransportFactory.kt:91`; Ktor `pingIntervalMillis = 0L` at `:203` (intentional — OkHttp emits pings) | |
| 10 | REST fallback trigger | Driven by `RestStateMachine.onWsSessionEnded(...)` (`RestStateMachine.kt:595-796`) consuming `WsSessionLifecycleEvent.Ended` from `KtorRelayTransport.kt:1636-1648`. Bridge `HybridRelayTransport.startWsCollectors` via `toRestStateMachineEvent()` `:149-156`. Per-send capability-off fallback → WS at `:1103-1113` | Envelope-level fallback happens only via state-machine mode; single failed `send()` does NOT itself fallback |
| 11 | Runtime transport rewalk | Three layers: (a) WSS↔REST per-send read of `stateMachine.current` at `HybridRelayTransport.kt:1062`; (b) outer-transport chain rewalk on network change by `TransportRewalkCoordinator` at `apps/android/.../transport/TransportRewalkCoordinator.kt:83-467` (entry `performRewalk` `:186`); (c) WSS reconnect loop with backoff `KtorRelayTransport.kt:1479-1597` + public `forceReconnect()` API (`RelayTransport.kt:228`) | |
| 12 | Recipient dedup (client-side) | 3 layers on `DefaultMessagingService.handleDeliver`: (a) `processedEnvelopeRepository?.exists(deliver.messageId)` `:2580`, ack-and-drop `:2585`; (b) legacy `messageRepository.getMessageById(deliver.messageId) != null` `:2595-2599`; (c) REST inbound `RestInboundDeduplicator.resolve(env.id)` `HybridRelayTransport.kt:1176-1230` (`Emit`/`SkipNoAck`/`ReAck`) plus persistent-ledger pre-check `:1143-1170` | `markProcessed(...)` sites `:1336, 2723, 2869, 3207, 3435, 3497, 3552`. **NOTE: `markProcessed` is not the same as "message persisted into the recipient app's chat store". Persistence to the visible chat conversation happens SEPARATELY inside the same `handleDeliver` branch — see §4 requirement for `recipient_message_persisted`.** |
| 13 | Sender delivery-state tracking | Enum `MessageStatus { QUEUED, WAITING_FOR_RECIPIENT_BUNDLE, SENT, RELAYED, DELIVERED, READ, FAILED }` `shared/core/storage/.../MessageRepository.kt:49-74`. Init `QUEUED` `:1581`; post-`transport.send` `newStatus = if (sent) SENT else QUEUED` `:1653-1654`; sender-relay-ack promotion in `startReceiving` `:2312-2320` (`"delivered" → DELIVERED`, else `RELAYED`); read receipts `:4055, 4277` | **`MessageStatus.DELIVERED` in the current code reflects the SENDER-RELAY ACK (relay pushed to recipient mpsc — see §R7), NOT recipient-app-side persistence. It MUST NOT be used alone as proof of end-to-end delivery. See §2 for the corrected `Delivered once` definition.** |
| 14 | Debug toggles (existing) | See §6 — **no single "force WSS-only" / "force REST-only" pin exists in the current tree**; closest are `DEBUG_FORCE_MODE_2_DETECTION` (synthetic RestActive nudge, apps/android/build.gradle.kts:491 + `DebugForceMode2Activity.kt`), `DEBUG_K8_CONNECTION_CLOSE` `:714`, direct-arm probes `DEBUG_RC_DIRECT_ARM*` `:195-325` | |
| 15 | Existing client logs | See §3 (existing observability table) | |

### Relay (Phantom `services/relay`)

| # | Stage | Site | Notes |
|---|---|---|---|
| R1 | WSS ingress | Route `.route("/ws", get(ws_handler))` `services/relay/src/routes.rs:250`; auth `authorise_ws` `:291-300`; per-conn loop `handle_socket` `:401`; frame dispatch `handle_message` `:986`; `"send"` arm `:992` | |
| R2 | Envelope schema (wire) | Parsed fields at `routes.rs:993-1001` (`to`, `sealedSender`, `payload`, `messageId`); internal `SendCandidate { id, sealed_sender, payload, sequence_ts, expires_at }` `services/relay/src/rest_workers.rs:143-156`; stored `Envelope { id, to, from, sealed_sender, payload, expires_at }` `services/relay/src/envelope.rs:12-29` | |
| R3 | Envelope-ID canonical shape | `is_valid_envelope_id(&msg_id)` at `routes.rs:1036` — accepts 1..=128 bytes of `[a-zA-Z0-9._-]`; recipient hex check `is_valid_recipient_identity_hex(&to)` `:1021` (64 lowercase hex, X25519 pub key) | Client `uuid4().toString()` (36 chars, dashes only) satisfies the canonical shape — verified |
| R4 | Ingress dedup | 4-way pre-write gate in `do_send`: reads `rest_store` + `store` + `active_index` + `tombstone_dedup` at `rest_workers.rs:949-962`; `check_pre_write_consistency` → `SendDisposition::{QueuedReplay, TombstoneReplay}` `:981-1017`; body-hash mismatch → `EnvelopeIdReusedWithDivergentBody` `:1003-1006` | |
| R5 | Persistent queue + TTL | RAM `store: Arc<RwLock<HashMap<String, Vec<Envelope>>>>` `services/relay/src/state.rs:81`; `rest_store` `:150`. Disk persist `persistence::write_record_bytes` (atomic) `rest_workers.rs:1091`. Two-store push `:1154-1164` under write lock; capacity reserve `:1083-1086`; commit `:1207`. TTL fields `Envelope.expires_at` `envelope.rs:32-51`; check `:54-60`. Sweep `services/relay/src/sweep_scheduler.rs:156` | |
| R6 | Recipient delivery | Deliver frame built `routes.rs:1123-1130` (`{"type":"deliver","from":"","sealedSender","payload","messageId"}`); push via recipient mpsc `:1271-1278`; reconnect flush scan `:575-587`, emit `:591-602` | |
| R7 | Sender-relay ack emit (**NOT app-ack**) | Single ack path per Send, gated by recipient mpsc `send` result `routes.rs:1296-1304` — `Ok` → `WsAckStatus::Delivered`, else `Relayed`. Tombstone replay: `Relayed` immediately, no re-deliver `:1245-1250`. **`WsAckStatus::Delivered` here means "envelope pushed onto recipient's WS mpsc channel". It DOES NOT mean the recipient app decrypted, persisted, or displayed the message.** Recipient-app confirmation flows via a separate `"ack-deliver"` frame `:1325-1437` (see R8). | The current sender-side status name (`MessageStatus.DELIVERED` in client code, `WsAckStatus::Delivered` in relay code) is misleading. See §4 for the observability rename plan. |
| R8 | Recipient-app ack path | `ack-deliver` frame handled at `routes.rs:1325-1437`; events `ack_deliver_received` `:1334-1339`, `ack_deliver_dispatched` `:1379-1383`, warn `ack_deliver_runtime_error` `:1394-1399`, `ack_deliver_reply_dropped` `:1413-1417`, `ack_deliver_reply_timeout` `:1431-1435`. Recipient client is expected to emit this frame AFTER it has decrypted + persisted + surfaced the message. | Whether the client actually emits `ack-deliver` at the correct moment is an open code question for §4 (see `recipient_message_persisted` event site TBD). |
| R9 | PR #397 deployment status | HEAD `d63366b6` sits on `fix/relay-queue-durability-pr2`. Local `master` HEAD is `fadc5c9c` (PR #396 state-dir recovery). Per architect §8-Q1 answer: PR #397 IS merged into upstream `master` on 2026-07-31 — this local `master` is stale. **VPS deployment of PR #397 is still unconfirmed** and requires a separate read-only attestation of the running relay (see §8-Q1 for the attestation protocol). No VPS changes or `docker compose up` are in scope for this diagnostic. | |
| R10 | Runbook / recalibration docs | `docs/tracks/rc-relay-queue-ram-recalibration.md`; `docs/adr/ADR-027-relay-queue-durability-and-ram-budget.md`; `docs/operations/relay-env-reference.md`; `docs/tracks/rc-relay-state-dir-repair.md` | |
| R11 | Metrics | **NOT FOUND** — no `metrics::counter!`, no prometheus, no metrics crate in `services/relay/Cargo.toml`. Envelope-level observability = `tracing::info!` structured logs only. No accepted / delivered / dedup / expired counters as first-class metrics | |

### End-to-end path summary

```
[SENDER CLIENT]
user tap Send  (ChatScreen.kt:1063)
 → OutgoingMessage.id = uuid4()  (ChatScreen.kt:1074)
   → SqlDelight row inserted, MessageStatus.QUEUED  (DMS:1581, id persisted BEFORE any send)
     → DefaultMessagingService.sendMessage builds RelayMessage.Send  (DMS:1639)
       → HybridRelayTransport.send reads RestStateMachine.current  (HRT:1055)
         ├─ WsActive/WsCandidate → KtorRelayTransport.send  (KRT:2067)
         │   → session.send(Frame.Text)  (KRT:2149)
         │   → armAckDeadlineLocked, deadline 10 s / timeout 60 s  (KRT:2145-2148)
         │   → return true = "OkHttp write path did not throw"  ← NOT recipient delivery
         └─ RestActive → RestFallbackOrchestrator.sendEnvelope  (RFO:1048)
             → HTTP POST /rest/send
             → Accepted 201 | Duplicate 200 | DisabledByCapability | Failed
   → SqlDelight status flipped: SENT if send()==true, else QUEUED  (DMS:1653)
     → transport.acks Flow emits SENDER-RELAY ACK when relay pushes deliver frame  (DMS:2312)
       → messageRepository.updateStatus(id, DELIVERED|RELAYED)  (DMS:2318)  ← RELAY-ACK, NOT APP-ACK

[relay side — INBOUND]
WSS /ws → ws_handler → handle_socket → handle_message → "send"  (routes.rs:250, 401, 986, 992)
 → validate `to` (64 hex) + `messageId` ([a-zA-Z0-9._-] 1..128)  (:1021, :1036)
   → do_send  (rest_workers.rs:949)
     → pre-write dedup gate (rest_store + store + active_index + tombstone)  (rw:960)
       → QueuedReplay | TombstoneReplay | fresh
     → persist to disk (atomic write)  (rw:1091)
     → push into two stores under write lock  (rw:1154-1164)
   → deliver frame built  (routes.rs:1123)
     → recipient mpsc.send(deliver)  (routes.rs:1271)
       → live push OK → WsAckStatus::Delivered ack to sender  (routes.rs:1296-1304)
                        ← this is push-to-mpsc, NOT app-ack
       → live push closed → WsAckStatus::Relayed, envelope retained
   → separate "ack-deliver" flow when RECIPIENT CLIENT confirms  (routes.rs:1325-1437)
                        ← this is closer to app-ack but still not proof of message persistence

[recipient client — INBOUND]
handleDeliver
 → processedEnvelopeRepository.exists(messageId) → ack-and-drop if seen  (DMS:2580)
 → messageRepository.getMessageById(id) != null → skip  (DMS:2595)
 → RestInboundDeduplicator.resolve(env.id) → Emit / SkipNoAck / ReAck  (HRT:1176)
 → on Emit: markProcessed  (DMS:1336, 2723, ...)
              ← markProcessed is a DEDUP write, NOT proof of chat-visible persistence
 → decrypt + save into chat conversation store  (site TBD in §4 diff draft)
              ← this is the actual "message persisted into visible chat"
 → recipient client emits ack-deliver frame back to relay  (see R8)
```

---

## §2 — Delivery outcomes contract (REDLINE-1)

**Terminology cleanup.** The current codebase reuses "Delivered" ambiguously:

- `KtorRelayTransport.acks` emits `"delivered"` when the relay's push-to-recipient-mpsc succeeded (this is a **sender-relay ack**, NOT recipient-app confirmation).
- Client `MessageStatus.DELIVERED` is set on that sender-relay ack (DMS:2312-2320).
- Relay `WsAckStatus::Delivered` = same meaning as above (push-to-mpsc succeeded).
- The only signal that comes closer to recipient-app confirmation is the separate `ack-deliver` frame flowing back through the relay (R8) — but even that fires from the recipient client based on wherever the client code emits it, which may or may not be strictly after the message is persisted into the chat store. Verified in §4 diff draft.

Under WSS-1, this ambiguity is resolved in TWO ways: (a) rename the sender-relay-ack observability event so it CANNOT be read as end-to-end delivery, (b) introduce a NEW event `recipient_message_persisted` that fires after the recipient client has actually saved the message into the visible chat store.

**Corrected outcomes with strict priority order.** An envelope MUST resolve into exactly ONE of three states within a bounded budget. The verifier evaluates each correlation ID in the following ORDER — the first matching outcome wins:

### Priority 1 — `Recovered through fallback without duplicate`

Matches when ALL FOUR delivery signals hold **AND** at least one client-observable fallback breadcrumb is present in the sender's log stream for the same `correlation_id`:

Delivery signals (all four, same as Priority 2 below):
- `recipient_deliver_received dedup_gate=fresh` (recipient client)
- `recipient_message_persisted` (recipient client — new event, site TBD in §4)
- `recipient_ack_deliver_sent` (recipient client)
- NO second `recipient_deliver_received dedup_gate=fresh` for the same correlation ID

Fallback breadcrumbs (client-only for the first pass — relay-side proofs are deferred, see below):
- `sender_transport_decision` emitted more than once for the same `correlation_id` with the `inner_route` field changing between values (e.g. `wss → rest` or `rest → wss` across `attempt` numbers) — the outbound path moved between routes and eventually delivered.
- `sender_ack_watchdog_requeued` (new event, §4) fired at least once and delivery signals eventually held on a later attempt.
- `attempt >= 2` on the `sender_wss_frame_written` or `sender_rest_post_completed` event that immediately precedes the final `sender_relay_ack_received` — a second write of the same envelope preceded convergence.

### Priority 2 — `Delivered once`

Matches when ALL FOUR delivery signals above hold AND there is NO fallback breadcrumb (i.e. `attempt=1` on the winning send event and no route change and no watchdog requeue). The message reached the recipient on the first attempt via the pinned transport.

`MessageStatus.DELIVERED` on the sender (DMS:2312-2320) and relay `WsAckStatus::Delivered` (R7) are RECORDED in the evidence but are NEVER sufficient to declare Priority 2 by themselves — they are sender-relay ack signals, not recipient-app confirmation.

### Priority 3 — `Unresolved`

Matches when Priority 1 and Priority 2 do NOT match within 120 s of the sender's `sender_enqueue` event for that `correlation_id`. The verifier stamps `product_outcome=Unresolved` for that cell. This decision is made ENTIRELY VERIFIER-SIDE from wall-clock reconciliation; **no client event named `UNRESOLVED_AFTER_120S` exists in the `WSS_DIAG` schema** (§4). The 120-s ceiling is not encoded in Compose/Kotlin production state, and this diagnostic MUST NOT introduce a new `MessageStatus.FAILED` for text (that belongs to a separate later product block; voice FAILED at DMS:2042, 2087 is unaffected).

The 120-s window comfortably exceeds `ACK_TIMEOUT_MS = 60 000` (KRT watchdog) + one WS reconnect + a REST fallback. If a message is still `Unresolved` at 120 s, the delivery pipeline has demonstrably failed to converge.

### Deferred enrichment (relay-side breadcrumbs)

Relay `event="ws_send_queued_replay"` / `ws_send_tombstone_replay` (routes.rs:1236, 1255) would qualify as additional Priority-1 breadcrumbs — but the first-pass evidence is client-only black-box (§3, §8-Q1). Relay-side breadcrumbs are NOT collected in the first bundle and MUST NOT be used to promote a Priority-2 result to Priority 1 in the first-pass verifier report. A later architect-gated read-only relay attestation may reintroduce them.

### Forbidden states

- `Pending forever` — every correlation ID reaches one of the three priorities within 120 s (Priority 3 is the ceiling).
- Silent duplicate emit on the recipient's visible chat surface — a second `recipient_deliver_received dedup_gate=fresh` for the same ID = Priority-1/2 failure (both fall to Priority 3).
- Confusion between sender-relay ack and recipient-app ack in the verifier's report (§9.4).

---

## §3 — Existing vs missing observability

### Existing (client)

| Layer | Tag / event | Site | Envelope-ID field? |
|---|---|---|---|
| UI | `PhantomUI` — `ChatScreen subscribed …` | `ChatScreen.kt:462` | No |
| Messaging (send) | `MessagingLog` — `SEND_TRACE send_start` | `DefaultMessagingService.kt:1544` | Yes (`id`) |
| Messaging (send) | `SEND_TRACE relay_send_call` / `_return ok=$sent` | `DMS.kt:1636, 1648` | Yes |
| Hybrid transport | `PhantomHybrid` — `REST_TRACE route_send` / `_fallback_ws` / `send_oversize` / `send_failed` | `HybridRelayTransport.kt:1088, 1107, 1115, 1127` | Partial (message ID in some, not all) |
| WSS transport | `PhantomRelay` — `Sending envelope` / `Envelope send returned false` | `KtorRelayTransport.kt:2124-2127, 2151` | Yes |
| WSS transport | `PhantomRelay` — `Queued until reconnect` / `Deferred to outbox` | `KRT.kt:2082-2086, 2118-2121` | Yes |
| WSS lifecycle | `PhantomRelay` — `WebSocket connected successfully`, `closed by remote (clean)`, `connect FAILED`, `session_summary`, `readLoop exited` | `KRT.kt:1497, 1552, 1576, 1612, 2063` | No (session-level) |
| Rewalk | `PhantomHybrid` — `NETWORK_TRACE rewalk_start/_done/_aborted/_route_change/…` | `TransportRewalkCoordinator.kt:223, 463, 249, 262, 254, 320, 342, 370, 394, 447, 295, 502` | No |
| Receive | `MessagingLog` — `RECV_DIAG …` | `DMS.kt:2288-2321` | Yes |
| Hybrid inbound | `PhantomHybrid` — `inbound_deliver` / `skip_pending` / `reack_after_ack` / `skip_already_processed` | `HRT.kt:1178, 1196, 1205, 1147` | Yes |
| State flows | `state: StateFlow<TransportState>`; `pendingAckCount: Int` `RelayTransport.kt:11, 216`; `wsSessionLifecycle: Flow<WsSessionLifecycleEvent>` `KRT.kt:274`; `WsDegradationDetector` | | |

### Existing (relay)

Recorded here for completeness — but per §8-Q1, WSS-1 first-pass evidence is **client-only black-box**; relay events below are NOT collected in the first bundle. See §3 "Relay events in first-pass" below.

| Event | Site | Envelope-ID field? |
|---|---|---|
| `event="connect"` | `routes.rs:500-506` | No (conn-level) |
| `"flushing queued envelopes …"` | `:589` | Yes (`id`) |
| `event="message"` | `:1050-1058` | Yes (`msg_id`) |
| `event="ws_send_tombstone_replay"` | `:1236-1241` | Yes |
| `event="ws_send_queued_replay"` | `:1255-1261` | Yes |
| `"live delivery dispatched …"` | `:1280-1284` | Yes |
| `"recipient offline — queued …"` | `:1287-1291` | Yes |
| `ack_deliver_received` / `_dispatched` / `_runtime_error` / `_reply_dropped` / `_reply_timeout` | `:1334, 1379, 1394, 1413, 1431` | Yes |
| `event="disconnect"` / `event="session_summary"` | `:940, 963-982` | No / partial |
| `do_send` inside `rest_workers.rs` | — | **NONE — 0 `tracing::` calls in `do_send` (verified)** |

### Missing (both sides)

1. Correlation across sender ↔ (relay) ↔ recipient with a stable emitter ID + role field + explicit run/cell IDs. Today: envelope ID is present in many logs, but there is no `role=sender|relay|recipient` field, no `run_id`/`cell_id`, no `emitter_id`, no `session_epoch`. Grep-based joins are ad-hoc.
2. **Client-side transport-decision breadcrumb per send** — did this send go WSS or REST? Which mode? Which attempt? `REST_TRACE route_send` fires but does not include the envelope ID in a machine-parseable field.
3. **Recipient app-side persistence event `recipient_message_persisted`** — required by §2 outcome (1). Site does not exist today.
4. **Relay `do_send` is silent** — not consumed in first-pass evidence (§9 client-only black-box) but flagged as a permanent observability gap.
5. **No 120-second unresolved-window marker** — the verifier fills this in by wall-clock reconciliation, but a client-side heartbeat event helps distinguish "no ack yet at t=60s" from "app process died".
6. **No debug-only runtime transport pin** — see §6.
7. **No metrics** on the relay (§R11) — Yota diagnostic has to rely on log parsing.

### Relay events in first-pass

Per §8-Q1 architect answer, WSS-1 first-pass evidence is **client-only black-box**. Relay-side events (§3 relay table above) are NOT collected in the first bundle. The verifier (§9.4) MUST refuse to make conclusions about relay ingress, dedup, or persistence in the first-pass report. A separate architect-gated read-only attestation of the running relay (§8-Q1) may bring relay events into a later evidence pass.

---

## §4 — Minimal instrumentation diff plan (for architect review — NOT YET WRITTEN)

Only additive log calls with the schema below. Zero behaviour change; zero PII, secret, or key-material leakage.

### Common envelope-scoped log schema

Every `WSS_DIAG` event emits ONLY the following structured fields (no `extra`, no opaque diagnostic tokens):

| Field | Source | Notes |
|---|---|---|
| `event` | one of the events below | Machine-parseable enum |
| `correlation_id` | envelope ID (UUID string) | Ties sender + recipient records |
| `run_id` | UUID assigned once per `run-yota-wss-diagnostic.sh` invocation | Set on both devices before matrix start via the debug command component (§9.3) |
| `cell_id` | matrix cell identifier (e.g. `wss.phone-to-emu.after-idle.envelope-3`) | Set per cell via the debug command component |
| `emitter_id` | **device-stable identity — `phone` or `emulator`** | Written once by the debug command component post-install; NEVER carries a sender/recipient qualifier. The phone is `phone` in every cell whether it sends or receives; the emulator is `emulator` in every cell |
| `role` | **derived per event** — `sender` on outbound-side events (`sender_enqueue`, `sender_transport_decision`, `sender_wss_frame_written`, `sender_rest_post_completed`, `sender_relay_ack_received`, `sender_ack_watchdog_requeued`) / `recipient` on inbound-side events (`recipient_deliver_received`, `recipient_message_persisted`, `recipient_ack_deliver_sent`) / `relay` reserved for a later relay-side pass | Derived from `event` name, hard-coded per event site. NEVER read from cell direction — a bug that mislabels an emitter's role must fail-red on the focused test (§5-1) |
| `session_epoch` | `KtorRelayTransport` session-epoch counter (existing at `KRT.kt:57`) | Distinguishes events from different WS sessions of the same emitter |
| `wall_utc_ms` | `System.currentTimeMillis()` snapshot | Only wall time is comparable ACROSS devices; verifier records the measured phone↔emulator clock skew separately (§9.6) |
| `monotonic_ms` | `SystemClock.elapsedRealtime()` on Android | Comparable **only within one `emitter_id`+`session_epoch`** — verifier uses this for per-process ordering, never for cross-device time comparison |
| `outer_transport` | `direct` \| `reality` \| `tor` \| `unknown` | Reflects the OUTER transport arm actually selected by `TransportManager`. Set on `sender_transport_decision`; must be `direct` for every pinned cell (§6) |
| `inner_route` | `wss` \| `rest` \| `unknown` | Inner send path within the outer arm. Set on `sender_transport_decision`, `sender_wss_frame_written`, `sender_rest_post_completed`, `sender_relay_ack_received`. NOTE: replaces the earlier single `transport` field which conflated the two axes |
| `attempt` | integer `1..N` | Incremented per envelope resend within the same `correlation_id` |
| `dedup_gate` | `fresh` \| `duplicate` \| `reack` \| `unknown` | Only on `recipient_deliver_received` |
| `outcome_flag` | `sender_relay_ack_delivered` \| `sender_relay_ack_relayed` \| `queued_for_reconnect` \| `dropped_by_capability` \| `send_error` | Only on the terminal event within its scope. **`unresolved_120s_marker` is NOT a client field — the verifier synthesises the `Unresolved` outcome per §2 Priority 3 at report time.** |
| `relay_acceptance` | `accepted` \| `duplicate` \| `failed` \| `disabled_by_capability` \| `unknown` | Only on `sender_rest_post_completed`. REST response semantics (`Accepted` = HTTP 201, `Duplicate` = HTTP 200 replay) are DISTINCT from sender-relay-ack semantics; they belong on the REST post event, not on `sender_relay_ack_received`. |

No text content, no username, no auth token, no SNI / UUID / REALITY param, no QR payload, no contact data. Explicit banned fields: any hex payload, any raw bytes, any `sealedSender` blob.

### Client events to add (all in a new single tag `WSS_DIAG`)

| Event | Where | Trigger | `role` |
|---|---|---|---|
| `diagnostic_pin_active` | On app start AND on every pin change | Fires from the debug command component (§9.3) once the pin store settles; carries `run_id`, `cell_id`, `outer_transport`, `inner_route`, `emitter_id` — proves which transport actually ran the cell | (no envelope role — matrix-scoped) |
| `diagnostic_canary` | Fires from the debug command component on `preflight --canary` | Proves the `WSS_DIAG` tag emits on the device WITHOUT enqueueing any envelope into `sendMessage` or touching the chat store | (no envelope role — matrix-scoped) |
| `sender_enqueue` | `DefaultMessagingService.kt:1581` (after `insertMessage`) | Row lands in SqlDelight with QUEUED | sender |
| `sender_transport_decision` | `HybridRelayTransport.kt:1063-1080` (inside `send`) | Right before branching WSS or REST | sender; `outer_transport` + `inner_route` fields REQUIRED |
| `sender_wss_frame_written` | `KtorRelayTransport.kt:2151` (after `sendRaw` return) | Boolean return captured | sender; `outcome_flag=send_error` if false |
| `sender_rest_post_completed` | `HybridRelayTransport.kt:1093-1102` | After `sendEnvelope` returns | sender; `relay_acceptance` field reflects `SendOutcome` variant. **Does NOT emit `outcome_flag=sender_relay_ack_delivered` — REST acceptance ≠ sender-relay ack** |
| `sender_relay_ack_received` | `DMS.kt:2312-2320` (inside `startReceiving` ack collector) | `transport.acks` Flow emit — this is the RELAY-side ack, not the recipient-app ack | sender; `outcome_flag=sender_relay_ack_delivered` or `sender_relay_ack_relayed` |
| `sender_ack_watchdog_requeued` | `KtorRelayTransport.kt:1862-1878` (watchdog expiry) | Ack watchdog fires — this is REQUEUE, not terminal failure | sender |
| `recipient_deliver_received` | `HRT.kt:1178` + `DMS.kt:1336` | Inbound `Deliver` frame after all dedup layers evaluated | recipient; `dedup_gate` REQUIRED |
| `recipient_message_persisted` | `DefaultMessagingService.handleDeliver` — site TBD in diff draft, must be AFTER decrypt + chat-store insert (candidate: right before or immediately after `markProcessed` at `DMS.kt:2723` / `:3207` / analogous, but confirm the insert site) | Recipient app has actually saved the plaintext into the visible chat conversation | recipient — **NEW EVENT** |
| `recipient_ack_deliver_sent` | site where recipient client emits the outbound `ack-deliver` frame; TBD in diff draft | Recipient-app-side ack round-trip closer | recipient |

### Relay events (NOT collected in first-pass client-only black-box)

If a later architect-gated pass adds relay evidence (§8-Q1 attestation), the relay diff would add: `event="do_send_ingress"` / `_dedup` / `_persisted` in `rest_workers.rs`; `deliver_push_ok/_failed` at `routes.rs:1271`. All would carry `msg_id`, `role=relay`, `wall_utc_ms`. NOT in scope for WSS-1 first pass.

### Instrumentation guardrails

- No new business logic. Every added line is `Log.i("WSS_DIAG", …)` on Android.
- No text, no username, no hex key, no auth token, no SNI / UUID / REALITY param, no QR payload, no contact data. Verifier enforces the guardrail against the collected bundle (§9.4).
- Every event uses structured fields per the schema above; no `%s` string formatting that dumps envelope contents.
- Single tag `WSS_DIAG` — the capture script narrows to `WSS_DIAG:V *:S`; no broad `PhantomHybrid` / `PhantomRelay` / `MessagingLog` / `PhantomUI` capture (per P0-4).

---

## §5 — Focused tests (to accompany the observability diff — NOT YET WRITTEN)

All tests pin the correlation contract; none touch transport internals; none require ADB or a device. Every test fails-red if the correlation join a Yota matrix cell will perform is not resolvable.

### Client (pure JVM, `apps/android` `androidUnitTest`)

1. `wss_diag_sender_wss_flow_emits_ordered_events_with_role_derived_from_event` — fake `KtorRelayTransport` returns success + fires ack; assert `WSS_DIAG` emits exactly `sender_enqueue` → `sender_transport_decision outer_transport=direct inner_route=wss` → `sender_wss_frame_written` → `sender_relay_ack_received outcome_flag=sender_relay_ack_delivered` with the SAME `correlation_id`, `run_id`, `cell_id`; **assert `emitter_id` is a stable device identity (e.g. `phone`) and `role=sender` on every event — NEVER `sender.phone`**. A regression that couples direction with device identity fails-red here.
2. `wss_diag_sender_rest_flow_emits_relay_acceptance_not_relay_ack` — `RestStateMachine` starts in RestActive; assert log sequence emits `sender_transport_decision outer_transport=direct inner_route=rest` then `sender_rest_post_completed relay_acceptance=accepted` (mapped from HTTP 201). **Assert the event does NOT carry `outcome_flag=sender_relay_ack_delivered`** — REST acceptance is a distinct signal.
3. `wss_diag_recipient_dedup_marks_second_delivery_as_duplicate` — inject same envelope ID twice into `handleDeliver`; assert first emits `recipient_deliver_received dedup_gate=fresh role=recipient` + `recipient_message_persisted role=recipient`; second emits `recipient_deliver_received dedup_gate=duplicate role=recipient` and NO second `recipient_message_persisted`.
4. `wss_diag_no_pii_or_key_material_in_events` — assertion sweep across a captured event bundle for banned tokens (username fixtures, plaintext, sealed-sender base64, auth token, any 64-char lowercase-hex substring — the key-material shape). UUID correlation IDs (36 chars with dashes) are ALLOWED.
5. `wss_diag_ack_watchdog_requeue_emits_requeue_event_not_terminal_failure` — advance `mainClock` past `ACK_TIMEOUT_MS`; assert `sender_ack_watchdog_requeued` fires; assert `MessageStatus` did NOT transition to `FAILED` (proves §2 Priority-3 stays verifier-side).
6. `wss_diag_recipient_message_persisted_fires_after_chat_store_insert` — inject a Deliver frame; assert `recipient_message_persisted` fires strictly AFTER the chat-store insert (verified by an in-memory chat-store fake that records the insert timestamp).
7. `wss_diag_no_unresolved_120s_marker_event_type_exists` — grep-style guard: no source file under `phantom.android.diagnostic` (or `phantom.android` in general) emits a `WSS_DIAG` event with `event=unresolved_120s_marker` or `outcome_flag=unresolved_120s_marker`. `Unresolved` is a verifier-side classification only.
8. `wss_diag_wss_pin_fails_closed_when_outer_arm_is_not_direct` — under `Pin.WSS` with the outer transport arm forced to a non-Direct value, assert `sender_transport_decision outer_transport=reality` (or `tor`) emits AND the send returns without dispatching to WSS. The debug pin MUST NOT silently succeed under a non-Direct outer arm.
9. `wss_diag_rest_pin_fails_closed_on_disabled_by_capability` — under `Pin.REST` with `RestFallbackOrchestrator.sendEnvelope` returning `DisabledByCapability`, assert `sender_rest_post_completed relay_acceptance=disabled_by_capability` fires AND the send returns `false` without silently falling back to WS.
10. `wss_diag_diagnostic_canary_does_not_enqueue_message` — trigger `diagnostic_canary`; assert `WSS_DIAG event=diagnostic_canary` fires AND no `sender_enqueue` is emitted AND `MessageRepository.insertMessage` is NOT called.

### Relay — deferred to a later pass (client-only black-box first — §3 last row).

---

## §6 — Force WSS / Force REST pin (debug-build only)

**No production toggle exists** in the current tree for pinning transport. Under WSS-1, a new **debug-build-only runtime store** is added, mutated by a debug-only in-app command component.

### Store shape

- **In-memory only.** The pin is a `@Volatile` field on a top-level `DiagnosticTransportGuard` object living in `src/main/`. It is not persisted to disk. This deliberately eliminates any file-write attack surface (`run-as`, ADB `echo`, symlink games) — the only path that mutates the field is the debug-only receiver code below.
- `data class PinState(pin: Pin, runId: String, cellId: String)` where `enum class Pin { NONE, WSS, REST }`, default `PinState(NONE, "", "")`.
- **Reader lives in `src/main/`** (`DiagnosticTransportGuard.current(): PinState`). Present in every APK variant, always returns `PinState.NONE` unless a writer has explicitly set it.
- **Writer lives ONLY in `src/debug/`** (`DiagnosticCommandReceiver`, §9.3). Physically absent from the release APK. Reads a broadcast Intent, validates every input against the whitelist, writes `DiagnosticTransportGuard.set(newState)`.
- Process death (crash, background kill) resets the pin to `PinState.NONE`. This is a FEATURE: the matrix runner writes the pin at the head of every cell and waits for a matching `diagnostic_pin_active` event before firing the first envelope; a pin loss surfaces as a missing / mismatched event in the next cell and `evidence_integrity` fails-red at the verifier. The operator reruns the matrix; no silent corruption.
- The `emitter_id` (`phone` or `emulator`) is baked at build time as `BuildConfig` string flavour and read from a companion field on `DiagnosticTransportGuard`. It does NOT change per cell or per pin write — pinning affects `pin`/`runId`/`cellId` only. In this diagnostic APK both emitter_ids are shipped in the same APK (see build variants below) and selected at install time via a one-shot `am broadcast … --es subcommand set_emitter_id --es emitter_id phone|emulator` — the receiver validates the value against `{"phone", "emulator"}` and writes to another `@Volatile` field. Preflight verifies via `diag-cmd.sh health`.

### Semantics

The pin has two effects — an OUTER arm constraint AND an INNER route constraint. Both must hold; violations are fail-closed.

**`Pin.WSS` semantics**

- Outer arm — TWO acceptable implementations, WSS-1 diff draft picks ONE:
  - (a) **Explicit outer override**: debug-only path forces the `TransportManager` chain to `DIRECT_FIRST` and rejects any transition to a non-Direct arm for the lifetime of the pin. The pin acts as a hard filter, not a preference. Requires touching `TransportManager` — the diff has to add ONE branch that checks the pin state before returning from `reorderChain(...)`.
  - (b) **Fail-closed check on actually selected arm**: leave `TransportManager` untouched; observe the actually selected outer arm via existing state (which `TransportStrategy` variant ran); if not `direct`, refuse to dispatch the envelope, emit `sender_transport_decision outer_transport=<actual> inner_route=<pinned> dispatched=false`, and mark the row as `dropped_by_capability`. The matrix runner + verifier read this and stamp the cell `BLOCKED` (§9.4).
- Inner route: `HybridRelayTransport.send` (`:1055-1081`) reads the pin FIRST and overrides `stateMachine.current` to force the WSS branch. If the WS session is not connected at send time, the envelope defers to `pendingOutbox` (existing `KRT` behaviour). **Never silently falls through to REST.**

**`Pin.REST` semantics**

- Outer arm: same two options as above — MUST be `direct`; non-Direct → `BLOCKED`.
- Inner route: `HRT.send` overrides `stateMachine.current` to force REST. **Fail-closed on `DisabledByCapability`**: today's `HRT.kt:1103-1113` falls back to WS when REST orchestrator returns `DisabledByCapability`; **under `Pin.REST` this fallback is disabled** — the send returns `false` and `sender_rest_post_completed relay_acceptance=disabled_by_capability` fires. The verifier stamps the cell `BLOCKED`.

**Observability requirement**

`WSS_DIAG event=diagnostic_pin_active` fires on app start AND on every pin change, carrying `pin`, `run_id`, `cell_id`, `outer_transport` (the ACTUAL selected outer arm at the moment the event fires), `inner_route` (the pinned inner value), and `emitter_id`. This is the ground truth the verifier joins against per matrix cell — the pin's INTENDED value vs the ACTUAL outer transport picked by `TransportManager`. Any mismatch is a fail-red for that cell.

### Why not a `BuildConfig` string

- `BuildConfig` values are baked at build time — changing the pin between matrix cells would require rebuilding the APK 8 times or shipping a matrix-baked variant per cell.
- A runtime store lets one APK cover all cells; the debug command component writes the pin before each cell begins.
- Source-set separation guarantees the store code is physically absent from the release APK.

---

## §7 — Observability diff overview

Covered by §3 (existing/missing tables) + §4 (additive events + schema). No change to production behaviour; correlation is the only capability added; guardrails per §4 last block.

---

## §8 — Open questions and blockers (RESOLVED)

Architect answers 2026-08-11 collapsed into resolved decisions:

**Q1.** PR #397 merged into upstream `master` 2026-07-31. Local `master@fadc5c9c` is stale. **VPS deployment still unconfirmed** — WSS-1 first pass proceeds as client-only black-box (§3 last row). A read-only attestation of the running relay is a SEPARATE architect-gated task before any relay events enter evidence; no VPS `git pull` or `docker compose up` in scope.

**Q2.** **WSS-1 diagnostic is text ONLY.** Voice send path (`DMS.kt:1800`) is out of scope for the first pass.

**Q3.** After 120 s without recipient-app proof → verifier stamps `product_outcome=Unresolved` (Priority 3, §2). **No new client `MessageStatus.FAILED` state** — fixing failure/retry semantics for text is a separate later product block. `ACK_TIMEOUT_MS = 60 s` and the ack watchdog remain requeue-only in code; the 120-s ceiling is a verifier-side classification, NOT a client-side state transition, and the `WSS_DIAG` schema (§4) does NOT include an `unresolved_120s_marker` outcome_flag.

**Q4.** **Removed** — irrelevant to WSS diagnostic.

**Q5.** **Yes** — one Yota-radio phone + one Mac-network emulator is an intentionally asymmetric matrix. Emulator is the "network-normal reference"; phone is the "Yota-stressed side".

**Q6.** REST endpoint capability on the target relay is enforced by `preflight.sh` (§9.6) via one of two acceptable methods (REDLINE-2 refinement):
- **Method A (preferred, when available)**: hit the production REST capability contract endpoint (a `/rest/capability` or equivalent). If the production API exposes a machine-readable capability descriptor, preflight consumes it and stamps REST cells as `BLOCKED` when REST is disabled.
- **Method B (fallback)**: the production capability contract may not exist. In that case preflight marks REST capability as `UNKNOWN` and adds a **controlled fail-closed REST cell at the head of the matrix**: one envelope pinned to REST; if it comes back `relay_acceptance=disabled_by_capability` from `sender_rest_post_completed`, all subsequent REST cells (#7, #8) are stamped `BLOCKED` and skipped. A generic `HTTP GET /rest/send` without an application-level capability contract does NOT prove capability and is banned as a probe.

Either method, combined with the §6 `Pin.REST` fail-closed behaviour, prevents silent WSS fallback on REST-pinned cells.

**Q7.** **`preflight.sh` is mandatory.** Runs before the matrix. Checks: `adb`, `python3`, `bash`, `jq`; exactly one emulator + one physical device online; correct diagnostic APK variant installed on both; write-verify of the debug-only runtime pin store on both devices (via the debug command component — NOT via ADB file write); a `diagnostic_canary` event emit from both devices; clock-skew measurement (see Q10); dual-SIM default-data-subscription check (see Q9); REST capability check (see Q6).

**Q8.** Focused tests only — no full-suite `AppNotIdleException` gate.

**Q9 (REDLINE-2).** **Dual-SIM handling.** `getprop gsm.operator.numeric` MAY return concatenated values on a dual-SIM device (both SIM slots), so it cannot reliably identify which SIM is the default-data subscription. Preflight instead queries the app-side debug command component (§9.3) which calls `SubscriptionManager.getActiveDataSubscriptionId()` + `TelephonyManager.createForSubscriptionId(...).getSimOperator()` and returns the operator numeric of the DEFAULT DATA subscription only. Preflight requires the operator to confirm that value matches Yota MCC/MNC. If the phone is single-SIM, the check reduces to the same subscription without loss of correctness. `getprop` is retained ONLY as a secondary informational reading in `device-manifest.json`.

**Q10 (REDLINE-2).** **Clock skew.** Cross-device time comparisons use `wall_utc_ms` corrected by the measured phone↔emulator skew from `device-manifest.json`. A large skew reduces cross-device timing precision (per-event ordering across devices becomes lossy) but does NOT by itself invalidate the correlation bundle — `correlation_id` joins on stable UUIDs and remain accurate regardless of clock drift. Preflight WARNS at `|skew| > 2 000 ms` (records to manifest), FAILS only at `|skew| > 30 000 ms` (bundle correlation reliability drops below usable). Between warn and fail, `evidence_integrity=GREEN` remains valid.

No new blockers introduced by the REDLINE-1 or REDLINE-2 amend.

---

## §9 — Mac operator package (WSS-1 deliverable) — SPEC ONLY, not implemented

Per architect direction 2026-08-11 + REDLINE-1 + REDLINE-2: operator does not manually collect logcat, does not correlate envelope IDs, does not hard-code ADB serials, does not touch identity, does not act on relay state, does not compose arbitrary send text.

**Bundle layout** (planned) — root at `docs/tracks/direct-wss/operator-package/` in this branch:

```
operator-package/
├── android-debug-diagnostic.apk        # one APK, produced by ONE `assembleDebug` after WSS-1 lands
├── android-debug-diagnostic.apk.sha256 # checksum, verified by install-apk.sh
├── README-OPERATOR.md                  # step-by-step, Mac-only, no assumptions
├── run-yota-wss-diagnostic.sh          # entry point; drives the full matrix
├── preflight.sh                        # MANDATORY: env + capability + pin + skew + dual-SIM + canary
├── lib/
│   ├── detect-devices.sh               # emulator + phone auto-detect (§9.1)
│   ├── install-apk.sh                  # uninstall → verify absent → install checksum-verified APK
│   ├── capture-logs.sh                 # per-device logcat filtered to WSS_DIAG only
│   ├── run-matrix.sh                   # drives 8 directed cells × 5 envelopes = 40 envelopes
│   ├── diag-cmd.sh                     # thin wrapper around ADB → debug command component (§9.3)
│   └── bootstrap.sh                    # --fresh: uninstall protocol, no `pm clear`; then manual onboarding
├── verify-evidence.py                  # verifier; reports evidence_integrity + product_outcome separately
└── evidence/                           # auto-created, one dir per run
    └── yota-wss-YYYYMMDDTHHMMSSZ/
        ├── phone.logcat.wss_diag
        ├── emulator.logcat.wss_diag
        ├── device-manifest.json        # default-data operator, radio type, signal, wall-clock skew, APK sha256
        ├── matrix.json                 # cells + expected outcomes
        ├── preflight.json              # preflight results (incl. REST capability method + result)
        └── verification-report.md      # generated by verify-evidence.py
```

### 9.1 Device auto-detection (`lib/detect-devices.sh`)

Discovers ALL online devices via `adb devices`, classifies each as emulator or physical:

```bash
adb devices | awk 'NR>1 && $2 == "device" { print $1 }' | while read -r serial; do
  is_emu=$(adb -s "$serial" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r\n')
  if [ "$is_emu" = "1" ]; then
    echo "EMULATOR=$serial" >> "$OUT/roles.env"
  else
    echo "PHONE=$serial" >> "$OUT/roles.env"
  fi
done
```

Assertions:

- Exactly ONE emulator + ONE physical device online. Otherwise the script exits red.
- Yota confirmation is deferred to preflight (§9.6) via the default-data-subscription check — `getprop gsm.operator.numeric` alone is NOT authoritative on dual-SIM devices.

### 9.2 Evidence capture (`lib/capture-logs.sh`) — narrow

**Per P0-4:** only `WSS_DIAG` events are collected. No broad `PhantomHybrid` / `PhantomRelay` / `MessagingLog` / `PhantomUI` tags.

```bash
START_ISO=$(date -u +"%Y-%m-%dT%H:%M:%S.000")
for role in PHONE EMULATOR; do
  serial=$(cat "$OUT/roles.env" | grep "^$role=" | cut -d= -f2)
  adb -s "$serial" logcat -v threadtime -T "$START_ISO" \
      WSS_DIAG:V *:S \
      > "$OUT/$(echo "$role" | tr A-Z a-z).logcat.wss_diag" &
  echo $! >> "$OUT/log-pids"
done
```

Canary emission is performed by `preflight.sh` via `diag-cmd.sh canary` (§9.3) — the `diagnostic_canary` event fires INSIDE the app without invoking `sendMessage`, so the tag-emit spot-check does not enqueue any envelope, does not touch the chat store, and does not consume a matrix envelope slot.

### 9.3 Debug command component + matrix runner

**REDLINE-2 P0-1 fix.** The matrix cannot run without an in-app command surface. The diagnostic APK includes a **debug-only** command component:

- Kotlin lives ONLY under `apps/android/src/debug/kotlin/phantom/android/diagnostic/DiagnosticCommand*` (any release variant of the app has the file absent from dex — enforced by source-set separation and verified by the release ProGuard rule `verifyR8StripsTestSeams`).
- Exposed shape (WSS-1 diff draft picks a concrete Android component — `Activity` with `exported=true, enabled=true` gated to the debug manifest merger overlay OR a `BroadcastReceiver` under the same gating). Either shape accepts `am start …` / `am broadcast …` and returns synchronously.
- **Strict extra whitelist** — the component refuses to run if ANY extra outside this set is present:
  - `run_id` (opaque string ≤ 64 chars, `[a-zA-Z0-9._-]`)
  - `cell_id` (opaque string ≤ 128 chars, `[a-zA-Z0-9._:-]`)
  - `contact_alias` (opaque string ≤ 64 chars, references an ALREADY-PAIRED conversation from onboarding — the component looks it up in the local conversation store; if not found, exits red without touching sendMessage)
  - `sequence` (integer, 1..N)
  - `pin` (one of `none|wss|rest` — for the pin-write subcommand)
  - `subcommand` (one of `pin|send|canary|dual_sim_report|rest_capability_probe|health`)
- **No arbitrary plaintext** — send text is derived INTERNALLY as `"YOTA-WSS-${cell_id}-${sequence}"`. The operator cannot inject arbitrary text; the ADB caller cannot inject arbitrary text.
- **No key/QR/username extras.** The whitelist ban is enforced by refusing the whole `am` call on any unknown extra.
- Every subcommand emits `WSS_DIAG event=diagnostic_pin_active` (for `pin`) / `diagnostic_canary` (for `canary`) / `sender_enqueue` (for `send`) / diagnostic-only events for `dual_sim_report` and `rest_capability_probe`. The `health` subcommand returns a small structured JSON via stdout that `preflight.sh` parses for APK version, pin store round-trip, tag emit health.
- `lib/diag-cmd.sh` is a thin bash wrapper that translates `diag-cmd.sh <subcommand> --serial <S> --run-id <R> --cell-id <C> --contact-alias <A> --sequence <N>` into the corresponding ADB `am` invocation. No stateful behaviour lives in the shell wrapper.

**Matrix arithmetic** (unchanged from REDLINE-1): 8 directed cells × 5 envelopes = **40 envelopes per pass**.

| # | Pin | Direction | Scenario | Envelopes |
|---:|---|---|---|---:|
| 1 | WSS | Phone → Emulator | immediately after connect | 5 |
| 2 | WSS | Emulator → Phone | immediately after connect | 5 |
| 3 | WSS | Phone → Emulator | after natural idle (see below) | 5 |
| 4 | WSS | Emulator → Phone | after natural idle | 5 |
| 5 | WSS | Phone → Emulator | background → foreground (see below) | 5 |
| 6 | WSS | Emulator → Phone | background → foreground | 5 |
| 7 | REST | Phone → Emulator | same-path control | 5 |
| 8 | REST | Emulator → Phone | same-path control | 5 |

- **"Idle"** — script waits `>= 300 s` with the app in foreground; no user interaction, **no airplane-mode toggle**.
- **"Background → foreground"** — ADB home-key + foreground restore only. **`am force-stop` is banned by this spec.** Process kill is NOT part of this diagnostic.
- Envelope IDs come from the app (`uuid4()` at `ChatScreen.kt:1074`) — the script does NOT generate correlation IDs.
- Between cells the runner invokes `diag-cmd.sh pin <wss|rest>` and waits `>= 5 s` for a `diagnostic_pin_active` event with matching `pin` + `run_id` + `cell_id` on BOTH devices before firing the first envelope.

### 9.4 Evidence verifier (`verify-evidence.py`) — dual output

The verifier reports TWO INDEPENDENT results:

- **`evidence_integrity`** — bundle completeness. GREEN if every matrix cell's expected `WSS_DIAG` event set is present with matching `run_id`/`cell_id`/`emitter_id`/`correlation_id` and clock skew is within the FAIL threshold (§8-Q10). A large-but-tolerable skew emits a warning without lowering `evidence_integrity`. This says NOTHING about whether the product delivered messages — a fully-collected bundle of failure evidence is `evidence_integrity=GREEN`.
- **`product_outcome`** — per matrix cell, one of `Delivered once` (§2 Priority 2) / `Recovered` (§2 Priority 1) / `Unresolved` (§2 Priority 3) / `BLOCKED` (REST cell where preflight's capability check reported disabled OR the controlled fail-closed head cell returned `disabled_by_capability`). Aggregate `product_outcome=RED` if any cell is not `Delivered once` or `Recovered`.

`evidence_integrity=GREEN, product_outcome=RED` is a **successful diagnostic run** — we captured what's broken. `evidence_integrity=RED` is a diagnostic failure — the bundle is unusable for architect review.

Verifier behaviour:

- Reads only `phone.logcat.wss_diag`, `emulator.logcat.wss_diag`, `matrix.json`, `preflight.json`, `device-manifest.json`. Refuses to open any other file.
- Cross-device correlation joins on `correlation_id` + `run_id` + `cell_id`. Time comparisons across devices use `wall_utc_ms` corrected by the measured clock skew from `device-manifest.json`. `monotonic_ms` is used ONLY for ordering within a single `emitter_id`+`session_epoch`.
- **Priority-ordered outcome classification** per §2: check Priority 1 (Recovered) first; if unmet, check Priority 2 (Delivered once); if unmet after 120 s, stamp Priority 3 (Unresolved).
- Refuses to make ANY claim about relay ingress, dedup, or persistence in the first-pass report (§3 last row). Absence of relay data does NOT lower `evidence_integrity`.
- Rejects any log line where `event=unresolved_120s_marker` OR `outcome_flag=unresolved_120s_marker` appears (client-side emit of the verifier-only classification = schema violation, fails `evidence_integrity`).
- Report `verification-report.md` is a table: `cell_id`, `direction`, `pin`, expected outcome, observed `product_outcome`, `evidence_integrity` per cell, `outer_transport` actually selected per cell (proves the §6 pin held), and — for RED cells — the exact missing events with their `correlation_id`.

### 9.5 Identity bootstrap (`lib/bootstrap.sh`) — uninstall protocol, no `pm clear`

**REDLINE-2 clarification.** `pm clear` leaves package metadata and doesn't guarantee a clean state. Replace with a strict uninstall protocol:

- `bootstrap.sh --fresh` prints an explicit warning listing what will happen on BOTH devices (package uninstall — no `-k`, no data retention) and requires the operator to type the run ID as confirmation before proceeding.
- Sequence per device:
  1. `adb shell pm uninstall $APP_ID` — NOT `pm uninstall -k` (that would keep data). If package isn't installed, this returns cleanly.
  2. Verify absence: `adb shell pm list packages | grep -q $APP_ID` must NOT match. Preflight aborts if it does.
  3. `install-apk.sh` verifies the SHA-256 of `android-debug-diagnostic.apk` against `android-debug-diagnostic.apk.sha256` (both bundled) BEFORE installing; then `adb install -r $APK`. On checksum mismatch or install failure, bootstrap aborts.
  4. Operator runs the production onboarding flow on BOTH devices manually (through the real UI), creating two real Phantom identities.
  5. Operator uses the production QR-pairing flow (Profile → My Phantom QR → Share my Phantom contact) to pair the two devices — scan one QR from the other via a screen photo. Same code path a real user follows.
- `bootstrap.sh --verify` (no uninstall, no install) checks: both devices have the same APK sha256 installed, both have a Phantom identity, both share at least one paired conversation. Does not modify anything.
- No test-only identity injection, no shortcut, no pre-generated key material shipped with the operator package.

### 9.6 Preflight (`preflight.sh`) — mandatory

Runs before the matrix. Failing preflight aborts the run with `evidence_integrity=RED` and a clear message.

- **Environment**: `adb --version`, `python3 --version >= 3.9`, `bash`, `jq --version`.
- **Devices**: exactly one emulator + one physical device online per §9.1; APK sha256 matches `android-debug-diagnostic.apk.sha256` on both.
- **APK variant**: `pm dump $APP_ID | grep 'versionName\|versionCode'` matches expected; `pm dump` proves the installed variant is debug (release APK does NOT declare the debug command component in its merged manifest — presence of the receiver/activity is the proof).
- **Debug-only runtime pin store**: `diag-cmd.sh pin none` → observe `diagnostic_pin_active pin=none` on both logcats within 5 s; then read back the store via `diag-cmd.sh health` and assert it reports `pin=none`. Failure here → `evidence_integrity=RED`.
- **Canary**: `diag-cmd.sh canary` → observe `diagnostic_canary` on both logcats. Confirms `WSS_DIAG` tag emits on the device WITHOUT enqueueing an envelope (the canary explicitly skips `sendMessage` and chat-store insert).
- **Dual-SIM default-data-operator check** (REDLINE-2 Q9): `diag-cmd.sh dual_sim_report` returns the operator numeric of the DEFAULT DATA subscription (via `SubscriptionManager.getActiveDataSubscriptionId()` + per-subscription `TelephonyManager.getSimOperator()`). Preflight requires the operator to confirm this value matches Yota MCC/MNC. `getprop gsm.operator.numeric` is retained as an informational field only.
- **REST capability check** (REDLINE-2 Q6): `diag-cmd.sh rest_capability_probe` first attempts Method A (production capability contract, if the endpoint exists). If Method A returns a definitive answer, preflight records it. Otherwise the diagnostic APK runs a **controlled fail-closed REST cell** as the FIRST matrix cell: one envelope pinned `Pin.REST` — if the response is `sender_rest_post_completed relay_acceptance=disabled_by_capability`, `preflight.json` stamps `rest_capability=disabled` and cells #7 + #8 are pre-marked `BLOCKED` in `matrix.json`. **A generic `HTTP GET /rest/send` is NOT accepted as a capability probe.**
- **Clock skew** (REDLINE-2 Q10): `preflight.sh` records `wall_utc_ms` from both devices via ADB shell `date +%s%3N` at N=5 samples, computes the median skew (phone_wall − emulator_wall), writes to `device-manifest.json`. WARN at `|skew| > 2 000 ms`; FAIL only at `|skew| > 30 000 ms`. Between warn and fail, `evidence_integrity=GREEN` is unaffected.

### 9.7 `README-OPERATOR.md` (planned outline)

1. Prerequisites (Mac, `adb`, `python3 >= 3.9`, `jq`; one physical Yota phone; one running emulator).
2. Radio setup on the phone (Yota is the default-data subscription; no Wi-Fi/VPN/Tele2/auto-switching — the operator confirms preflight's dual-SIM default-data reading).
3. Fresh bootstrap (`./run-yota-wss-diagnostic.sh bootstrap --fresh`): uninstall → verify absent → install checksum-verified APK on both → manual onboarding + manual QR pairing per §9.5.
4. `./preflight.sh` (must exit 0 before the matrix; includes canary, dual-SIM check, REST capability method).
5. Run matrix (`./run-yota-wss-diagnostic.sh matrix`).
6. Read report (`open evidence/yota-wss-*/verification-report.md`).
7. Where to send the bundle for architect review.

No manual logcat commands. No manual envelope ID grep. No manual "tap Send five times in a row and screenshot the status pill". No `adb shell am force-stop` or airplane-mode toggle. No arbitrary text composition — the operator triggers cells; the app derives text internally.

---

## §10 — Deliverables checklist

| # | Item | Status |
|---|---|---|
| 1 | `direct-wss-yota-contract.md` (REDLINE-2) | ✅ this document |
| 2 | Production path map with `file:line` | §1 |
| 3 | Delivery outcomes with strict priority order + Priority 3 verifier-side only | §2 |
| 4 | Existing vs missing observability table | §3 |
| 5 | Minimal instrumentation diff plan (schema with `emitter_id`/`role` split; `outer_transport` + `inner_route`; `relay_acceptance` on REST; no `unresolved_120s_marker` event; `recipient_message_persisted`; `diagnostic_canary`) | §4 |
| 6 | Focused tests list (10 client tests — includes role-derived, REST semantics, WSS/REST fail-closed, canary no-enqueue, no verifier-only field on client) | §5 |
| 7 | Debug-only runtime pin — EncryptedSharedPreferences via app command, outer Direct enforcement (override OR fail-closed), no ADB file write, no silent fallback | §6 |
| 8 | Open questions collapsed (Q1–Q10, incl. dual-SIM Q9 + clock-skew Q10 + REST capability Q6 method A/B) | §8 |
| 9 | Confirmation no runtime fix has been made | §0 + this row — verified |
| 10 | Mac operator package spec (auto-detect, narrow capture, debug command component with strict whitelist for send, uninstall bootstrap protocol with SHA-256, dual-output verifier with Priority-order classification, mandatory preflight incl. canary + dual-SIM + REST capability method A/B + skew warn-vs-fail) | §9 |

**Nothing here is implemented.** After architect GREEN on this REDLINE-2 amend, one WSS-1 code round produces: minimal `WSS_DIAG` instrumentation on the client + debug-only runtime pin store + debug-only command component + `recipient_message_persisted` event + operator-package bundle (per §9) + focused tests (§5) + ONE diagnostic APK. Then one Yota-pass on device.
