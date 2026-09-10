# USB-independent background diagnostics

Scope: observation only, prompted by failed background delivery of envelope
a4a20d0a-dec6-416e-ac0a-6b711e709f9b and a shell logcat collector which did
not survive screen lock. Do not equate the generic held verdict with a
proven network or cryptographic root cause.

The debug boot provider can arm an in-process observer using
no_backup/background-diagnostic/armed-until: a decimal UTC epoch deadline
in milliseconds, strictly future and at most two hours away. Absent,
malformed or expired arming performs no registration and creates no file.
Release has no provider or recorder implementation; optional Android log
hooks default to null. No build flag, server request, wake lock, retry,
database operation or transport decision is added.

An explicitly armed run projects selected existing WSS, messaging, REST and
relay log events into a closed field vocabulary. Two class-only messaging
events expose the otherwise lost first hold/receive-failure boundary.
Raw log lines, exception messages, payloads, URLs, addresses, SSIDs, peer
identities, conversation IDs and key material are not persisted. Envelope
UUIDs/prefixes are correlation metadata and remain private local diagnostics.
Unknown categorical values become "other", not arbitrary text.

Passive default-network and power/USB/screen callbacks add local state.
No probe is issued and no periodic sampler or diagnostic wake lock is used.
Each queued record has wall time, elapsed realtime, PID and a sequence.
One bounded 512-item queue writes on a private background thread. Append
syncs the file descriptor; the caller does not wait for disk. Exact repeated
envelope events are suppressed in a bounded 4096-key map; power/network
cycles are never suppressed. Queue drop and suppression counters are carried
in written records. Storage errors disable writing rather than failing the
application. The disk failure itself may leave no persistent failure record.

At most four 1-MiB files live in the no-backup private directory. Rotation
can remove old evidence; a new process_start/sequence reset identifies a
new process. A killed process can lose its not-yet-written queue. This is
not a transaction log or a guarantee of last-event survival on sudden power
loss. The final quiet interval may have no further record reporting a drop.
Expiry uses elapsed realtime after validation of the persisted UTC deadline.
No timer wakes a sleeping phone to close the idle writer; the next event
after expiry closes it, and idle threads time out independently.

Acceptance requires tests for field redaction, bounded queue/disk usage,
non-throwing diagnostic failure, append/reopen persistence and no arming by
default. Then a short unplug plus screen-lock physical test must demonstrate
that the app-owned journal continues, before another long background run.
The planned comparison uses identical network/VPN settings with a wall
charger versus no charging; computer USB is absent in both cases. Actual
Doze evidence, not elapsed test duration, determines the power-state claim.

No release acceptance, background fix, recovery of old held messages or
cryptographic change is claimed by adding this observer.
