// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
//
// PR-2 M5b — queue-ram-budget bench.
//
// Opt-in bin (`--features queue-ram-budget-bench`). One scenario
// per process; every failure exits non-zero. NDJSON on stdout —
// exactly one line per scenario-process. `--all` is a local
// orchestrator that spawns five children; the Docker path in
// `run.sh` launches five clean containers instead so the
// operator's evidence is per-container instead of shared-process.
//
// The M5 authorisation pins the actual measurement to
// linux/amd64 under `--memory 512m`. On Windows every RSS/cgroup
// field is null so the harness still compiles + smoke-runs on
// the dev host.

use std::collections::HashMap;
use std::path::Path;
use std::process::{Command, Stdio};
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use tempfile::TempDir;
use tokio::sync::{broadcast, oneshot};

use phantom_relay::boot_loader::{
    boot, BootConfig, OwnershipExpectation, PreflightCaps,
};
use phantom_relay::capacity_ledger::CapacityCaps;
use phantom_relay::persistence::PersistedRecord;
use phantom_relay::queue_meta::{self, Phase, QueueMeta, META_VERSION};
use phantom_relay::rest_workers::{
    spawn_worker_runtime, AckOutcome, FatalReason, RestOp, RuntimeSendError, SendCandidate,
    SendDisposition, SendError, WorkerRuntimeSpec,
};
use phantom_relay::seq_mac::SeqMacRootKey;
use phantom_relay::tombstone_config::{TombstoneConfig, MAX_HORIZON_SECS};

mod metrics;
use metrics::{platform_label, MemorySample};

const SCHEMA_VERSION: u32 = 1;
const QUIESCENCE_SECS: u64 = 2;
const TEST_MAC_KEY: [u8; 32] = [0xA5u8; 32];
const REPLY_TIMEOUT_SECS: u64 = 10;
const DRAIN_DEADLINE_SECS: u64 = 15;
const RAM_BUDGET_HEADROOM_BYTES: u64 = 512 * 1024 * 1024;

#[derive(Copy, Clone, Debug)]
struct Scenario {
    id: &'static str,
    envelopes: u64,
    recipients: usize,
    payload_bytes: usize,
    sealed_bytes: usize,
    tombstone_pct: u32,
}

const SCENARIOS: [Scenario; 5] = [
    Scenario { id: "small-narrow",     envelopes: 10_000, recipients: 10,    payload_bytes: 128,   sealed_bytes: 64, tombstone_pct: 0 },
    Scenario { id: "small-broad",      envelopes: 10_000, recipients: 1_000, payload_bytes: 128,   sealed_bytes: 64, tombstone_pct: 0 },
    Scenario { id: "large-narrow",     envelopes: 10_000, recipients: 10,    payload_bytes: 4_096, sealed_bytes: 64, tombstone_pct: 0 },
    Scenario { id: "large-broad",      envelopes: 10_000, recipients: 1_000, payload_bytes: 4_096, sealed_bytes: 64, tombstone_pct: 0 },
    Scenario { id: "mixed-tombstoned", envelopes: 10_000, recipients: 100,   payload_bytes: 4_096, sealed_bytes: 64, tombstone_pct: 30 },
];

enum Mode {
    Help,
    Scenario(String),
    All,
    Error(String),
}

fn parse_args(args: &[String]) -> Mode {
    if args.is_empty() {
        return Mode::Error("no arguments; expected --scenario <id> or --all".into());
    }
    let mut it = args.iter();
    while let Some(arg) = it.next() {
        match arg.as_str() {
            "--help" | "-h" => {
                if it.next().is_some() {
                    return Mode::Error("--help takes no arguments".into());
                }
                return Mode::Help;
            }
            "--all" => {
                if it.next().is_some() {
                    return Mode::Error("--all takes no arguments".into());
                }
                return Mode::All;
            }
            "--scenario" => {
                let id = match it.next() {
                    Some(v) => v.clone(),
                    None => return Mode::Error("--scenario requires an id".into()),
                };
                if !SCENARIOS.iter().any(|s| s.id == id) {
                    return Mode::Error(format!(
                        "unknown scenario id {id:?}; known: {}",
                        SCENARIOS.iter().map(|s| s.id).collect::<Vec<_>>().join(",")
                    ));
                }
                if it.next().is_some() {
                    return Mode::Error(
                        "--scenario takes exactly one id; trailing arguments are not allowed".into(),
                    );
                }
                return Mode::Scenario(id);
            }
            other => {
                return Mode::Error(format!("unknown arg {other:?}"));
            }
        }
    }
    Mode::Error("no --scenario or --all specified".into())
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match parse_args(&args) {
        Mode::Help => {
            print_help(&mut std::io::stdout());
        }
        Mode::Scenario(id) => run_single(&id),
        Mode::All => run_all_orchestrator(),
        Mode::Error(msg) => {
            eprintln!("queue_ram_budget_bench: {msg}");
            print_help(&mut std::io::stderr());
            std::process::exit(2);
        }
    }
}

fn print_help(w: &mut dyn std::io::Write) {
    let _ = writeln!(
        w,
        "queue_ram_budget_bench — PR-2 M5b\n\
         Usage:\n  \
           queue_ram_budget_bench --scenario <id>\n  \
           queue_ram_budget_bench --all\n\
         Scenarios (id envelopes recipients payload_bytes sealed_bytes tombstone_pct):"
    );
    for s in SCENARIOS.iter() {
        let _ = writeln!(
            w,
            "  {:<20} {:>6} {:>5} {:>6} {:>4} {:>3}",
            s.id, s.envelopes, s.recipients, s.payload_bytes, s.sealed_bytes, s.tombstone_pct,
        );
    }
    let _ = writeln!(
        w,
        "\nExit codes:\n  0  success (one NDJSON line on stdout)\n  \
           1  fail-loud invariant break\n  2  bad CLI usage",
    );
}

fn run_single(id: &str) {
    let scenario = SCENARIOS
        .iter()
        .find(|s| s.id == id)
        .expect("parse_args validated the id");
    let rt = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(4)
        .enable_all()
        .build()
        .expect("tokio runtime build");
    let ndjson = rt.block_on(async { execute_scenario(scenario).await });
    println!("{ndjson}");
}

fn run_all_orchestrator() {
    let exe = std::env::current_exe().expect("current_exe");
    for scenario in SCENARIOS.iter() {
        eprintln!("=== running {} ===", scenario.id);
        let output = Command::new(&exe)
            .arg("--scenario")
            .arg(scenario.id)
            .stdin(Stdio::null())
            .output()
            .expect("child spawn");
        if !output.status.success() {
            eprintln!(
                "scenario {} failed: exit={:?}\nstderr:\n{}",
                scenario.id,
                output.status.code(),
                String::from_utf8_lossy(&output.stderr),
            );
            std::process::exit(output.status.code().unwrap_or(1));
        }
        std::io::Write::write_all(&mut std::io::stdout(), &output.stdout).expect("stdout write");
    }
}

fn build_state_dir() -> TempDir {
    let dir = TempDir::new().expect("TempDir");
    std::fs::create_dir_all(dir.path().join("queue")).expect("mkdir queue");
    let root_key = SeqMacRootKey::from_bytes(TEST_MAC_KEY);
    let meta = QueueMeta {
        version: META_VERSION,
        phase: Phase::Ready,
        boot_generation: 1,
        seq_mac_key_fingerprint: root_key.fingerprint(),
    };
    queue_meta::write_meta(dir.path(), &meta).expect("write_meta");
    dir
}

fn boot_cfg(state_dir: &Path) -> BootConfig {
    let root_key = SeqMacRootKey::from_bytes(TEST_MAC_KEY);
    BootConfig {
        state_dir: state_dir.to_path_buf(),
        caps: PreflightCaps::for_tests(),
        tombstone: TombstoneConfig::from_secs(MAX_HORIZON_SECS).expect("tombstone cfg"),
        current_seq_mac_key_fingerprint: root_key.fingerprint(),
        ownership: OwnershipExpectation::permissive_for_tests(),
    }
}

fn caps() -> CapacityCaps {
    CapacityCaps {
        max_envelopes: 100_000,
        max_bytes: RAM_BUDGET_HEADROOM_BYTES,
        ram_budget: RAM_BUDGET_HEADROOM_BYTES,
    }
}

fn recipient_of(idx: usize) -> String {
    format!("{idx:064x}")
}

fn build_candidate(scenario_id: &str, index: u64, payload_bytes: usize, sealed_bytes: usize, expires_at: u64) -> SendCandidate {
    let id = format!("env-{scenario_id}-{index:06}");
    let sealed_sender: String = std::iter::repeat('s').take(sealed_bytes).collect();
    let payload: String = std::iter::repeat('p').take(payload_bytes).collect();
    SendCandidate {
        id,
        sealed_sender,
        payload,
        sequence_ts: 1_720_000_000_000,
        expires_at,
    }
}

fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("system clock >= epoch")
        .as_secs()
}

async fn execute_scenario(s: &Scenario) -> String {
    let dir = build_state_dir();
    let state_dir_str = dir.path().display().to_string();

    let boot_result = boot(&boot_cfg(dir.path())).expect("boot OK");
    let (fatal_tx, mut fatal_rx) = broadcast::channel::<FatalReason>(64);
    let key = Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY));
    let spec = WorkerRuntimeSpec::from_boot(
        boot_result,
        20_000,
        key,
        caps(),
        fatal_tx.clone(),
    )
    .expect("from_boot OK");
    let runtime = Arc::new(spawn_worker_runtime(spec).expect("spawn OK"));

    let baseline = MemorySample::take();
    let expires_far = now_secs() + u64::from(MAX_HORIZON_SECS) - 60;

    let tombstone_target: u64 = (s.envelopes * s.tombstone_pct as u64) / 100;
    let queued_target: u64 = s.envelopes - tombstone_target;

    let seed_start = Instant::now();
    for i in 0..s.envelopes {
        let recipient = recipient_of((i as usize) % s.recipients);
        let candidate = build_candidate(s.id, i, s.payload_bytes, s.sealed_bytes, expires_far);
        let envelope_id = candidate.id.clone();

        let (tx, rx) = oneshot::channel();
        match runtime.try_send(RestOp::Send { recipient: recipient.clone(), candidate, reply: tx }) {
            Ok(()) => {}
            Err(RuntimeSendError::Full) => panic!(
                "M5b {} envelope#{i}: dispatch mpsc full — backpressure with generous caps is a regression",
                s.id
            ),
            Err(other) => panic!(
                "M5b {} envelope#{i}: try_send failed with {other:?}",
                s.id
            ),
        }
        let send_reply = tokio::time::timeout(Duration::from_secs(REPLY_TIMEOUT_SECS), rx)
            .await
            .unwrap_or_else(|_| panic!("M5b {} envelope#{i}: send reply timeout", s.id))
            .unwrap_or_else(|_| panic!("M5b {} envelope#{i}: send reply channel dropped", s.id));
        let outcome = match send_reply {
            Ok(o) => o,
            Err(SendError::CapacityExceeded(e)) => panic!(
                "M5b {} envelope#{i}: CapacityExceeded({e:?}) with ram_budget={} — cap too low for scenario",
                s.id, RAM_BUDGET_HEADROOM_BYTES
            ),
            Err(SendError::PerRecipientQueueFull { observed, cap }) => panic!(
                "M5b {} envelope#{i}: PerRecipientQueueFull(observed={observed}, cap={cap}) — max_envelopes_per_recipient too low",
                s.id
            ),
            Err(other) => panic!("M5b {} envelope#{i}: send returned Err({other:?})", s.id),
        };
        if !matches!(outcome.disposition, SendDisposition::Fresh) {
            panic!(
                "M5b {} envelope#{i}: expected Fresh disposition, got {:?}",
                s.id, outcome.disposition
            );
        }

        if i < tombstone_target {
            let (tx, rx) = oneshot::channel();
            match runtime.try_send(RestOp::Ack { recipient: recipient.clone(), envelope_id: envelope_id.clone(), reply: tx }) {
                Ok(()) => {}
                Err(RuntimeSendError::Full) => panic!(
                    "M5b {} envelope#{i}: Ack dispatch full — backpressure regression",
                    s.id
                ),
                Err(other) => panic!(
                    "M5b {} envelope#{i}: Ack try_send failed with {other:?}",
                    s.id
                ),
            }
            let ack_reply = tokio::time::timeout(Duration::from_secs(REPLY_TIMEOUT_SECS), rx)
                .await
                .unwrap_or_else(|_| panic!("M5b {} envelope#{i}: ack reply timeout", s.id))
                .unwrap_or_else(|_| panic!("M5b {} envelope#{i}: ack reply channel dropped", s.id));
            match ack_reply {
                Ok(AckOutcome::Acked { .. }) => {}
                Ok(other) => panic!(
                    "M5b {} envelope#{i}: expected Acked, got {other:?}",
                    s.id
                ),
                Err(other) => panic!("M5b {} envelope#{i}: ack returned Err({other:?})", s.id),
            }
        }
    }
    let elapsed_seed = seed_start.elapsed();
    let post_seed = MemorySample::take();

    tokio::time::sleep(Duration::from_secs(QUIESCENCE_SECS)).await;
    let quiescent = MemorySample::take();

    match fatal_rx.try_recv() {
        Err(broadcast::error::TryRecvError::Empty) => {}
        other => panic!("M5b {}: unexpected fatal broadcast: {other:?}", s.id),
    }

    let ledger = runtime.capacity().snapshot();
    let runtime_active = runtime.active_entry_count() as u64;
    let runtime_tombstone = runtime.tombstone_dedup_count() as u64;

    let (disk_queued, disk_tombstone, disk_active_bytes, disk_tombstone_bytes) =
        walk_and_classify(dir.path());

    if runtime_active != disk_queued {
        panic!(
            "M5b {}: runtime active_index count {runtime_active} != disk Queued count {disk_queued}",
            s.id
        );
    }
    if runtime_tombstone != disk_tombstone {
        panic!(
            "M5b {}: runtime tombstone_dedup count {runtime_tombstone} != disk AckedTombstone count {disk_tombstone}",
            s.id
        );
    }
    if ledger.active_envelopes != disk_queued {
        panic!(
            "M5b {}: ledger.active_envelopes {} != disk Queued {}",
            s.id, ledger.active_envelopes, disk_queued
        );
    }
    if ledger.tombstone_records != disk_tombstone {
        panic!(
            "M5b {}: ledger.tombstone_records {} != disk tombstone {}",
            s.id, ledger.tombstone_records, disk_tombstone
        );
    }
    if ledger.active_bytes != disk_active_bytes {
        panic!(
            "M5b {}: ledger.active_bytes {} != disk_active_bytes {}",
            s.id, ledger.active_bytes, disk_active_bytes
        );
    }
    if ledger.tombstone_bytes != disk_tombstone_bytes {
        panic!(
            "M5b {}: ledger.tombstone_bytes {} != disk_tombstone_bytes {}",
            s.id, ledger.tombstone_bytes, disk_tombstone_bytes
        );
    }
    if disk_queued != queued_target || disk_tombstone != tombstone_target {
        panic!(
            "M5b {}: final shape mismatch — expected queued={queued_target} tombstone={tombstone_target}, got queued={disk_queued} tombstone={disk_tombstone}",
            s.id
        );
    }

    runtime.close();
    let drain = runtime
        .drain_handles(Duration::from_secs(DRAIN_DEADLINE_SECS))
        .await
        .expect("drain_handles clean");
    for outcome in &drain {
        if !outcome.is_clean() {
            panic!("M5b {}: worker drained dirty: {outcome:?}", s.id);
        }
    }

    emit_ndjson(
        s,
        &state_dir_str,
        elapsed_seed.as_secs_f64(),
        disk_queued,
        disk_tombstone,
        disk_active_bytes + disk_tombstone_bytes,
        &ledger,
        &baseline,
        &post_seed,
        &quiescent,
    )
}

fn walk_and_classify(state_dir: &Path) -> (u64, u64, u64, u64) {
    let queue_dir = state_dir.join("queue");
    let mut queued_count = 0u64;
    let mut tombstone_count = 0u64;
    let mut active_bytes = 0u64;
    let mut tombstone_bytes = 0u64;
    if !queue_dir.exists() {
        return (0, 0, 0, 0);
    }
    for shard_entry in std::fs::read_dir(&queue_dir).expect("read queue dir").flatten() {
        let shard_path = shard_entry.path();
        if !shard_path.is_dir() { continue; }
        for recipient_entry in std::fs::read_dir(&shard_path).expect("read shard dir").flatten() {
            let recipient_path = recipient_entry.path();
            if !recipient_path.is_dir() { continue; }
            for file_entry in std::fs::read_dir(&recipient_path).expect("read recipient dir").flatten() {
                let file_path = file_entry.path();
                let file_name = file_entry.file_name();
                let name_str = file_name.to_string_lossy();
                if phantom_relay::atomic_write::is_staging_tempfile(&name_str) {
                    panic!("M5b: staging tempfile survived on disk: {file_path:?}");
                }
                if !file_path.is_file() { continue; }
                let bytes = std::fs::read(&file_path).expect("read record");
                let disk_bytes = bytes.len() as u64;
                match serde_json::from_slice::<PersistedRecord>(&bytes) {
                    Ok(PersistedRecord::Queued { .. }) => {
                        queued_count += 1;
                        active_bytes += disk_bytes;
                    }
                    Ok(PersistedRecord::AckedTombstone { .. }) => {
                        tombstone_count += 1;
                        tombstone_bytes += disk_bytes;
                    }
                    Err(e) => panic!("M5b: unparseable record at {file_path:?}: {e}"),
                }
            }
        }
    }
    (queued_count, tombstone_count, active_bytes, tombstone_bytes)
}

#[allow(clippy::too_many_arguments)]
fn emit_ndjson(
    s: &Scenario,
    state_dir: &str,
    elapsed_seed_secs: f64,
    queued_count: u64,
    tombstone_count: u64,
    sum_disk_bytes: u64,
    ledger: &phantom_relay::capacity_ledger::GlobalCapacityInner,
    baseline: &MemorySample,
    post_seed: &MemorySample,
    quiescent: &MemorySample,
) -> String {
    let oom_delta = match (baseline.cgroup_oom_kill_count, quiescent.cgroup_oom_kill_count) {
        (Some(before), Some(after)) => Some(after.saturating_sub(before)),
        _ => None,
    };
    let cgroup_max = metrics::read_cgroup_memory_max_bytes();

    let mut obj: HashMap<&'static str, serde_json::Value> = HashMap::new();
    obj.insert("schema_version", serde_json::json!(SCHEMA_VERSION));
    obj.insert("scenario_id", serde_json::json!(s.id));
    obj.insert(
        "params",
        serde_json::json!({
            "envelopes": s.envelopes,
            "recipients": s.recipients,
            "payload_bytes": s.payload_bytes,
            "sealed_bytes": s.sealed_bytes,
            "tombstone_pct": s.tombstone_pct,
        }),
    );
    obj.insert("platform", serde_json::json!(platform_label()));
    obj.insert("quiescence_secs", serde_json::json!(QUIESCENCE_SECS));
    obj.insert("elapsed_seed_secs", serde_json::json!(elapsed_seed_secs));
    obj.insert("state_dir", serde_json::json!(state_dir));
    obj.insert("envelopes_seeded", serde_json::json!(s.envelopes));
    obj.insert("queued_count", serde_json::json!(queued_count));
    obj.insert("tombstone_count", serde_json::json!(tombstone_count));
    obj.insert("sum_disk_bytes", serde_json::json!(sum_disk_bytes));
    obj.insert("ledger_active_bytes", serde_json::json!(ledger.active_bytes));
    obj.insert("ledger_tombstone_bytes", serde_json::json!(ledger.tombstone_bytes));
    obj.insert("ledger_ram_bytes", serde_json::json!(ledger.ram_bytes));
    obj.insert("rss_baseline_bytes", serde_json::json!(baseline.rss_bytes));
    obj.insert("rss_post_seed_bytes", serde_json::json!(post_seed.rss_bytes));
    obj.insert("rss_quiescent_bytes", serde_json::json!(quiescent.rss_bytes));
    obj.insert("rss_peak_baseline_bytes", serde_json::json!(baseline.hwm_bytes));
    obj.insert("rss_peak_post_seed_bytes", serde_json::json!(quiescent.hwm_bytes));
    obj.insert(
        "cgroup_memory_current_baseline_bytes",
        serde_json::json!(baseline.cgroup_current_bytes),
    );
    obj.insert(
        "cgroup_memory_current_quiescent_bytes",
        serde_json::json!(quiescent.cgroup_current_bytes),
    );
    obj.insert(
        "cgroup_memory_peak_baseline_bytes",
        serde_json::json!(baseline.cgroup_peak_bytes),
    );
    obj.insert(
        "cgroup_memory_peak_quiescent_bytes",
        serde_json::json!(quiescent.cgroup_peak_bytes),
    );
    obj.insert("cgroup_memory_max_bytes", serde_json::json!(cgroup_max));
    obj.insert("cgroup_oom_kill_delta", serde_json::json!(oom_delta));

    // Unused parameter reference to keep the signature future-proof
    // (post_seed's HWM is captured by `rss_peak_post_seed_bytes` via
    // the quiescent sample; the intermediate `post_seed` snapshot
    // still contributes its rss to the timeline).
    let _ = post_seed;

    // Use BTreeMap ordering so the emitted line is stable for
    // grep-based verification in step 5.
    let sorted: std::collections::BTreeMap<_, _> = obj.into_iter().collect();
    serde_json::to_string(&sorted).expect("serialize NDJSON")
}
