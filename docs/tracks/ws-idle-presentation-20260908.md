# Silence-only probation presentation

Approved scope, 2026-09-08: retain REST fallback and the 60-second WS
probation. During probation triggered solely by missing inbound frames,
show the existing `Online / Limited realtime` presentation while raw WS
is Connected. Failures, route changes and unknown evidence retain
`Recovering`. This refines the candidate presentation row in
`ws-health-state.md`; it does not change its transport recovery gates.

Silence cannot establish failure. A single inbound frame cannot establish
sustained health either: isolated frames occurred on impaired links in
the earlier field evidence. Therefore immediate WsActive admission was
considered and rejected. No ping loop, server protocol, reconnect cadence,
timeout threshold, feature flag, voice capability or routing change is made.

RestStateMachine owns the classification. It publishes mode and cause as
one presentation snapshot after an event, including cause changes within
the same mode. The existing mode flow remains the operational authority.
An observed WS close, ACK timeout or route change supersedes silence; a
new session during fallback does not inherit the old session's exemption.
Known failures remain remembered until existing recovery proof succeeds.
Unknown evidence is conservative. Stale lifecycle events may conservatively
remove the presentation exemption; they cannot grant it.

AppContainer forwards the snapshot to the pure UI derivation. The service
notification consumes that same derived presentation, rather than maintaining
a second candidate-to-label table. Raw WS errors still show Recovering.
No fallback state is presented as fully Online by this exception.

Acceptance: quiet-input regression first fails on the old UI derivation;
unchanged REST activation and 60-second probation; no promotion without
incoming WS evidence; strong-failure cases and sticky recovery preserved;
real Hybrid collectors keep the REST poll alive through candidate state
and propagate a late ACK timeout even outside WsActive. These host tests
inject transport events, not a real carrier outage. Physical acceptance
requires a separately source-bound APK and device logs.
