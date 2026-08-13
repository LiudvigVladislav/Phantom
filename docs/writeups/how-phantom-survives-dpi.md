# When TLS stays open but accepted writes do not arrive: diagnosing a mobile-path flow stall

*An engineering note from the PHANTOM project (Willen LLC).*

PHANTOM is an open-source, end-to-end encrypted messenger built on the assumption that the network is hostile. "Hostile" is easy to say and hard to design against, because a hostile network rarely fails cleanly. It does not send you a `403`. It does not close the connection with a reason. It lets TLS and WebSocket setup complete; the client-side API accepts application writes; and yet the relay never reports seeing them, while the socket stays open and your client keeps believing everything is fine — right up until it declares the connection failed.

This is a write-up about one specific fight with that kind of failure: a repeatable, deniable stall on a Russian mobile path that took down our direct WebSocket transport, and the falsification process we used before we changed a single line of transport code.

The interesting part is not "there is DPI in Russia." That is well documented. The interesting part is the shape of the investigation: how you test five plausible causes when the network refuses to tell you anything, how you narrow what the evidence actually supports, and — just as importantly — where you have to stop and admit that some of them are still standing.

**Scope, stated up front, because it governs how you should read every number below.** Everything here was measured on one testbed: a single budget Android handset in one Russian region, across several measurement windows in late Q2 2026 — one mobile operator (Tele2 LTE), one Wi-Fi ISP for comparison, one device.

This article analyses the controlled raw-OkHttp diagnostic arms. The production Ktor path showed a distinct and more severe baseline — 29 sessions at roughly 31 seconds, with zero client-reported successful ping/pong rounds before timeout — and we do not combine those measurements here. Where the architecture section draws conclusions, those rest on the production code, the corresponding architecture decision records, and production observation; the *numbers* in the diagnostic sections do not. One operator, one region, one device, one measurement campaign spanning several windows. Where we give a number, it is an observation from that testbed — not a claim about "Russian mobile networks" in general, and not a claim about any specific piece of censorship infrastructure. Extrapolating from one operator in one region to a national policy is a separate research programme, and this is not it.

---

## The symptom

The raw-OkHttp diagnostic path reproduced the Direct WebSocket failure class with a rhythm you could set your watch by. (A reminder from the scope note: this is the diagnostic client, not the production one. Mixing the two would produce a tidier story and a false one.)

- **First session after a cold start:** roughly 30 seconds of life, then silence — the client observed no control Pong at all during that session.
- **Every session after that:** roughly 45 seconds, with exactly one client-observed control Pong, then silence.
- **Wi-Fi comparison, same build, same code:** much longer-lived, but it failed too — around 150 seconds, after eight successful ping/pong rounds.

That last line matters: the Wi-Fi path is not a healthy control. Direct WS also failed there, but later. That broadens the failure surface beyond LTE; it does not establish that the two paths shared a trigger or a mechanism.

"Silence" is the important word. No remote close arrived, and no TLS alert — the connection was never torn down from the other end. The client eventually failed the socket locally: when it went to send the next scheduled Ping, the library found it was still awaiting a Pong for the previous one. Up to that point its own logs showed `send()` accepting writes into a connection whose application payload the relay never logged receiving. The failure lay somewhere between acceptance into the client's outbound path and delivery to the relay application; these logs did not localise it further.

A rhythm this clean is a gift. Deniable failures are miserable to debug, but a *repeatable* deniable failure is a lever: it gives you a baseline, and each hypothesis makes some observable prediction when you change one relevant condition. That is enough to start knocking them over one at a time.

---

## Five plausible causes, and what each experiment established

When a connection dies at 30 seconds, everyone has a favourite theory within the hour. The discipline is to write them all down, then design the cheapest experiment that can *kill* each one — not confirm it, kill it. Two of the five survived that process in weakened form, and saying so is part of the report.

### 1. "It's our own heartbeat cadence."

The first suspect is always yourself. Maybe our ping interval was tripping some idle timer.

We varied the WebSocket ping interval across several values and measured session lifetime for each. The result was almost insultingly linear: **the first session tracked roughly twice the ping interval, and subsequent sessions roughly three times it**, across every interval we tried.

That pattern falls straight out of how the client's HTTP library detects a dead socket: it allows one outstanding Ping. In the first session, the first Ping got no Pong, and the next scheduled Ping found the outstanding one and failed the socket — two ticks, so roughly 2×. Later sessions completed one ping/pong round first, so the failure landed on the third tick, roughly 3×. This is a one-outstanding-Ping detector, not a three-missed-heartbeat policy.

A slope alone doesn't settle this: a middlebox acting on keepalive counts would produce a similar curve. Relay-side timestamps did establish one thing — the relay had already failed to observe application delivery *before* the client declared the connection failed — which separates the stall from its detection. It does not, on its own, rule the cadence out of the trigger. Settling that would need outbound capture or queue counters, or a test in which application delivery is first confirmed working and then disappears across different intervals — and we never observed a session in which application delivery worked at all, so there was no transition moment to measure.

So the honest conclusion is narrower than "theory dead": **the cadence governs detection latency, and whether it also participates in the trigger remains unproven.** The useful reframing survives either way — *application-data non-arrival and socket-failure detection are two different events, and we had been conflating them.*

### 2. "It's our edge server mishandling WebSocket frames."

Next suspect: our own edge. We terminate TLS at a reverse proxy that is fully WebSocket-aware — plenty of room for a subtle frame-handling bug.

So we removed it from the equation. We stood up a second edge that did nothing but unwrap TLS and forward raw TCP: no HTTP awareness, no WebSocket awareness, no opinion about frames at all. Two completely different TLS stacks, two completely different proxy behaviours.

Both produced **the same timing and the same failure signature**, over 21 sessions on each. Same 30/45-second rhythm, same silence, same disagreement between the endpoints.

This strongly argued against an implementation-specific edge or WebSocket-framing bug. Note the boundary of that conclusion: it does not clear the shared configuration, the origin address, the host kernel and network path, or the relay application itself — all of which were constant across both edges. And we wrote the verdict down carefully: not "our edge is innocent, therefore the carrier is guilty," but "an implementation-specific framing bug is unlikely; the shared factors remain in scope." You do not get to convict a suspect just because you cleared another one.

### 3. "It's a client-side send-buffer artefact."

On the client, a successful `send()` on a WebSocket only means the frame was accepted into the outbound queue — not that it left the radio. So maybe the frames we thought we were sending were piling up locally and never egressing.

This one we could not kill, and the honest thing is to say so. It is tempting to point at the control-versus-application asymmetry described below as evidence against local buffering — but that argument doesn't hold: control frames and application frames may traverse different internal queues in the client, and our logs cannot distinguish that from a network-side effect. Settling it would need outbound capture or queue counters, which we did not have. It stands in the record as **open**, not refuted. A debugging log that only contains victories is lying to you.

### 4. "The handset firmware is parking the radio."

Aggressive power management on budget Android hardware is real, and a dozing radio would produce exactly this kind of silence.

A targeted test matrix ruled out *full radio parking*: the failure reproduced in states where the radio was demonstrably active and carrying other traffic. Note the narrowness of that conclusion — it excludes the radio being asleep. It does not exclude every firmware or host-network-stack effect, and we don't claim it does.

### 5. "TLS or WebSocket setup never becomes healthy."

The nuclear hypothesis: something is fundamentally wrong with establishing the secure channel at all.

The setup-level version of this hypothesis refutes itself on the evidence, and understanding *why* points at the answer. **Sessions completed a full TLS handshake, and the relay received the client's control Ping in the 20 anchored sessions** — the secure channel was established successfully and carried frames at the transport level. A handshake that completes and a channel that carries frames for tens of seconds is not a broken handshake. Note what this does *not* clear: a post-setup bug in the client's WebSocket layer or in the relay application remains in scope, as do the shared factors from §2 and §3. And note what it does *not* establish: the relay logged zero application heartbeats across those sessions, so we never observed the application-data path working at all. Setup succeeded; application delivery was never demonstrably healthy in the first place. The failure is not in establishing the channel — setup itself succeeded. What follows is some post-setup property or correlated shared factor: age, byte or packet volume, event count, or another path state.

That reframing — *TLS/WebSocket setup success and application-delivery success are separate properties; our experiments established the former but never observed the latter* — is what turned a pile of dead theories into something testable.

---

## Two measurements, and what they do and don't show

### Observation 1: some control traffic arrives while application writes don't

We instrumented both ends of the same connection and compared logs. This is the single most important methodological move in the exercise: **the stall is only visible because the same session was logged from the handset and from the relay, and the two logs disagreed about what arrived.**

These figures come from the same edge-comparison work described above, not a separate campaign: within the Caddy-edge series, 21 sessions opened and 20 reached the control-Ping anchor before capture ended. Across those 20:

| Event | Direction | Observed |
|---|---|---|
| Control Ping | client → relay | received by relay in all 20 anchored sessions |
| Control Pong | relay → client | first session: none seen by client. Later sessions: exactly one, then silence |
| Application heartbeat | client → relay | client logged `send()` reporting success; **received by relay: zero** |
| Application echo | relay → client | never generated — there was no heartbeat to reply to |

Read the causal order carefully, because it is easy to garble: the relay received the client's *control* frames, but never logged an application heartbeat from it. The echo is absent not because it was lost on the way back, but because the relay had nothing to echo. Whether application frames ever reached the wire at all is precisely what we could not establish.

So during the same established sessions, on the same path: control frames reached the relay; application writes accepted by the client were not observed there. Whether those writes ever left the client's outbound queue is exactly what we could not determine.

**What this does not show.** It is tempting to conclude that something on the path distinguishes WebSocket control frames from text frames. It cannot, at least not directly: the opcode lives inside the WebSocket frame, which lives inside TLS. An observer without TLS termination cannot read it. What an on-path observer *can* see is size, timing, direction, and TLS record structure — and control frames and application frames differ on exactly those axes. Differences in payload size, write cadence, batching, client-side queueing, or TLS recordisation all remain plausible explanations for the asymmetry we measured. We are reporting an observation about our endpoints, not a mechanism inside the network.

### Observation 2: a single flow, one complete chunk observed

Separately, we ran one slow trickle over plain HTTPS: a chunked POST sending 40 KiB of body as eight 5 KiB chunks, ten seconds apart.

The relay application observed the first **5 KiB** chunk and no later upload payload. One-eighth of the upload body the client attempted to send.

**What this does not show, and this is the important part.** This was a *single run at a single configuration*, and it was not a sole-connection test: a session-auth call and three prekey-publish requests ran alongside it, two of which timed out client-side. Whatever affected the POST may have been shaped by that concurrency, or may have affected those requests too — we cannot separate the two. On top of that, chunk size, cadence, byte count, packet count, and elapsed time all varied together, and the boundary between what arrived and what didn't fell exactly on the first chunk boundary. With one run we cannot separate a byte-count trigger from an idle timeout between chunks one and two, a buffering window at our own edge, a PDP-context boundary, or any of several scheduling effects that happen to coincide with that boundary. Nor can we bound where a byte threshold would sit if that is what it was: all we know is that **the first 5 KiB chunk arrived and nothing after it did** — any limit could lie anywhere beyond that point, with chunk two simply never delivered. A different chunk size or a different pause might have produced a different result, or none at all.

So the honest statement is: **in this one configuration, the relay application observed the first 5 KiB chunk and no later upload payload — consistent with a flow-level cutoff, but neither establishing one nor identifying its trigger.** Anything stronger — "a per-flow byte budget of X" — is a hypothesis we have not earned. Establishing it would need repeats across chunk sizes and cadences, plus a demonstration that a fresh TCP flow again passes an initial portion.

**On prior art.** The community has documented flow-level freezes on Russian mobile operators — the [net4people/bbs Issue #490](https://github.com/net4people/bbs/issues/490) thread initially describes a freeze after roughly 15–20 KB server→client, with a later update characterising it as around 25 packets in either direction, averaging near 16 KB. Two things make that a poor calibration for our run. The original case is predominantly server→client while our slow POST is client→server. And more fundamentally, the two figures are different kinds of quantity: theirs is an inferred cutoff, ours is simply the last complete chunk observed. Our run cannot be placed below or above theirs, because we never measured a cutoff at all. What survives the comparison is only this: nobody should assume a universal threshold, and the two observations may not even share a trigger. A design that hard-codes an assumption about "the threshold" is building on sand.

**What we say, and what we don't.** From this testbed: on Tele2 LTE, the baseline diagnostic WebSocket sessions showed 30/45-second lifetimes; in the 20 anchored Caddy-edge sessions, the relay received no application heartbeat; and in one slow HTTPS POST, the relay application observed only the first 5 KiB chunk. What we deliberately do **not** say: that we have identified a threshold, that it holds for other operators, that we know the mechanism, or that PHANTOM "defeats" anything. We route around a specific, observed effect on a specific testbed. That is a much smaller and much truer claim.

---

## No single transport is a universal foundation

Here the diagnosis stops being a war story and becomes a design constraint. A note on what this section rests on: the diagnostic numbers above motivated the work, but the design conclusions below come from the production code, the architecture decision records, and production observation — not from the diagnostic arms. If flows can stall early on some paths, and the conditions that trigger it are *not* a stable known constant, a few things follow.

**No single transport is a foundation.** Direct WebSocket, plain HTTPS long-poll, TLS-mimicry tunnels, and Tor present different failure modes and trade-offs. If your realtime substrate is one wire protocol, you inherit that protocol's specific failure. So the substrate cannot be a protocol. It has to be a *set* of them behind one interface — though not every mode gets a fallback, and that is a deliberate choice rather than an oversight (see the privacy modes below).

PHANTOM speaks to its transports through a single `RelayTransport` interface, with several classes behind it:

- **Direct WebSocket** — the cheapest and lowest-latency option when application delivery is demonstrably healthy. On the diagnostic WebSocket paths we tested over Tele2 it was not (we did not test application heartbeats over Wi-Fi), so whatever its merits elsewhere, it cannot be assumed as the system's delivery foundation.
- **REST send and poll.** Envelope transfer over bounded HTTPS request/response. Each exchange is bounded *by construction* — small request, small response, one envelope at a time — which **reduces exposure** to whatever ends the long-lived flows. It is not immunity: our own slow POST shows an HTTPS request can stall early too. Where a longer-held poll is viable it is used; the short poll is the most conservative fallback we retain, not a guarantee of delivery.
- **TLS-mimicry tunnel (REALITY).** Changes the observable outer TLS shape to resemble the configured cover service. Worth being precise about the claim: this addresses filtering that keys on outer-flow appearance, and it is not a guarantee against all filtering. It is also not designed to address the stall behaviour above, and we did not run an experiment isolating whether it does. Mimicry and session stability are two different problems, and keeping them separate in your head is most of the battle.
- **Tor** — a different trade, offering stronger origin hiding at a latency cost. Stronger, not absolute.

The user does not select a concrete transport. They select a privacy policy, and the transport manager chooses among the routes and capabilities that policy permits. Standard and Private retain broader candidate chains and roughly comparable functionality. Ghost is the deliberate exception: Tor-only and fail-closed, and in the current Alpha, text-only — trading media and realtime availability for stronger origin hiding, rather than silently downgrading to a less private path.

They do differ in what happens when a transport is unavailable — and the honest description is narrower than "it just fails over." During *initial connection selection*, the standard and private modes walk ordered candidate chains. Once a connection is established, runtime WebSocket degradation is handled primarily by the REST fallback layer; it does **not** automatically re-walk the outer-transport chain. That was deliberate on the tested paths: at the time this policy was chosen, REST-over-Direct had remained the faster recovery path in the relevant validation, while the mimicry transport was not yet validated and Tor could take minutes to bootstrap. Abandoning a working REST-over-Direct path merely because the WebSocket had degraded would have made recovery slower, not better. (Later Tele2 testing found a separate REST long-poll failure mode on that path — a reminder that this too is a time-and-network-scoped observation, not a standing property.) The strictest mode permits only Tor and **fails closed** rather than downgrading — because silently falling back to a less private path is exactly the behaviour someone choosing that mode is trying to avoid.

**The load-bearing idea**, worth taking away even if you never touch a censored network: the delivery contract lives *above* the transport interface, not inside any transport.

Messages are queued at the relay, replayable across reconnects, and sequenced per recipient, with relay-side idempotency and bounded tombstone retention reducing duplicate delivery — and client-side envelope-ID deduplication as the final defence. All of that is a property of the delivery layer, sitting on top of the transport abstraction.

So when a direct WebSocket flow silently stalls, the sender can retry the *same sealed envelope* through the REST send path, and the recipient retrieves it by polling — no re-encryption, no re-signing. Within the relay's idempotency and tombstone-retention window, the same envelope ID lets the REST send path suppress duplicate retries. Beyond that window, client-side envelope-ID dedup remains the final defence. A transport failure need not become message loss when another path completes — but the abstraction cannot guarantee that an alternate path will work on every network; on at least one Tele2 LTE path, REST long-poll itself failed to complete delivery. Resilience was never any single transport's job, but it is not a guarantee either.

(One caveat on current state: durable relay queues have since landed in `master`, but were not deployed during these experiments, and production rollout is still pending.)

Compressed: **where policy permits fallback, recoverability is a property of the substrate rather than any one wire protocol.** You don't make a hostile network reliable by finding the one magic transport that always works — there isn't one, and Standard/Private are built on that premise. Ghost is the deliberate exception: it accepts Tor-only availability to preserve its fail-closed privacy contract, trading recoverability for a guarantee that it will never quietly fall back to something less private.

---

## What we'd tell the next person

- **Separate application-data non-arrival from socket-failure detection.** Endpoint timestamps showed the relay had failed to observe application delivery before the client declared the connection failed; cadence variation separately showed that the *declared* lifetime tracked the ping interval. Two experiments, two different results — and neither closes the question of what triggers the stall.
- **Instrument both ends.** The whole diagnosis turned on the handset log and the relay log disagreeing. Single-ended logging would have shown only that `send()` reported success; it could not distinguish client-side queueing from downstream non-arrival.
- **Kill hypotheses, don't confirm them, and change one variable per experiment.** Two edge implementations, one failure signature, argue against an implementation-specific edge or framing bug. Varying only the ping interval showed session lifetime tracking cadence — a correlation that constrains the detector, without separating it from the trigger.
- **Know the difference between an observation and a mechanism.** We measured an asymmetry between control and application traffic. We *cannot* see inside TLS, so we cannot claim the network reads frame opcodes. Reporting the first and resisting the second is the whole discipline.
- **One run is one run.** In our single slow POST, one complete chunk arrived and nothing after it. That is consistent with several different triggers, and with one data point we don't get to pick our favourite — nor even bound where any limit sits.
- **Assume your local measurement is local.** Our last-complete-chunk observation and the public report's inferred cutoff are not directly comparable. Neither should be treated as a universal threshold, and treating either as "the" number would mean hard-coding a fragile assumption.

None of this required knowing *who* was doing it or *why*. It required treating a hostile network as a debuggable system: form hypotheses, design experiments that can falsify them, instrument both ends, and hold conclusions to the confidence the evidence actually supports. The network is allowed to be adversarial. It is not allowed to be magic — and you are not allowed to be certain.

---

*PHANTOM is open source (AGPL-3.0-or-later). Architecture decisions, threat model, and known issues are public. If you build transports for hostile networks, or want to poke holes in ours, the code and design docs are at [github.com/LiudvigVladislav/Phantom](https://github.com/LiudvigVladislav/Phantom) — and the project lives at [phntm.pro](https://phntm.pro).*
