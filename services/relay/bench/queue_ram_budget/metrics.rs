// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
//
// PR-2 M5b — memory-observation helpers.
//
// Linux-only readers. On Windows the stubs return None so the
// harness can compile-check and smoke-run on the dev host without
// dragging in a cross-platform memory crate. The M5 authorisation
// pins the actual measurement target to a Docker container on
// linux/amd64 under `--memory 512m`.

#![allow(dead_code)]

/// Current resident set size in bytes. `None` on non-Linux.
pub fn read_vm_rss_bytes() -> Option<u64> {
    #[cfg(target_os = "linux")]
    {
        read_proc_self_status_field_kb("VmRSS:").map(|kb| kb.saturating_mul(1024))
    }
    #[cfg(not(target_os = "linux"))]
    {
        None
    }
}

/// Peak resident set size in bytes (`/proc/self/status VmHWM`).
/// Monotonic per process lifetime — captures transient allocator
/// peaks that VmRSS may have already released.
pub fn read_vm_hwm_bytes() -> Option<u64> {
    #[cfg(target_os = "linux")]
    {
        read_proc_self_status_field_kb("VmHWM:").map(|kb| kb.saturating_mul(1024))
    }
    #[cfg(not(target_os = "linux"))]
    {
        None
    }
}

/// cgroup v2 `memory.current` in bytes. `None` on non-Linux or
/// when the file is missing (v1 host, unmounted controller, host
/// process outside a memory cgroup).
pub fn read_cgroup_memory_current_bytes() -> Option<u64> {
    #[cfg(target_os = "linux")]
    {
        read_cgroup_u64("/sys/fs/cgroup/memory.current")
    }
    #[cfg(not(target_os = "linux"))]
    {
        None
    }
}

/// cgroup v2 `memory.peak` — highest `memory.current` observed
/// since cgroup creation.
pub fn read_cgroup_memory_peak_bytes() -> Option<u64> {
    #[cfg(target_os = "linux")]
    {
        read_cgroup_u64("/sys/fs/cgroup/memory.peak")
    }
    #[cfg(not(target_os = "linux"))]
    {
        None
    }
}

/// cgroup v2 `memory.max` — the hard limit configured on the
/// controller. `docker run --memory 512m` sets this to
/// 536_870_912. A literal string `"max"` (no limit) is reported
/// as `None` rather than a synthetic sentinel.
pub fn read_cgroup_memory_max_bytes() -> Option<u64> {
    #[cfg(target_os = "linux")]
    {
        match std::fs::read_to_string("/sys/fs/cgroup/memory.max") {
            Ok(s) => {
                let trimmed = s.trim();
                if trimmed == "max" {
                    None
                } else {
                    trimmed.parse::<u64>().ok()
                }
            }
            Err(_) => None,
        }
    }
    #[cfg(not(target_os = "linux"))]
    {
        None
    }
}

/// cgroup v2 `memory.events oom_kill` counter. Snapshot before
/// and after the scenario; the delta tells step 6 whether the
/// scenario overshot the container limit.
pub fn read_cgroup_oom_kill_count() -> Option<u64> {
    #[cfg(target_os = "linux")]
    {
        parse_memory_events_field("/sys/fs/cgroup/memory.events", "oom_kill")
    }
    #[cfg(not(target_os = "linux"))]
    {
        None
    }
}

pub fn platform_label() -> &'static str {
    if cfg!(target_os = "linux") {
        "linux"
    } else {
        "unsupported"
    }
}

#[cfg(target_os = "linux")]
fn read_proc_self_status_field_kb(prefix: &str) -> Option<u64> {
    let status = std::fs::read_to_string("/proc/self/status").ok()?;
    for line in status.lines() {
        if let Some(rest) = line.strip_prefix(prefix) {
            let trimmed = rest.trim();
            let mut it = trimmed.split_ascii_whitespace();
            let value = it.next()?;
            let unit = it.next().unwrap_or("kB");
            let parsed: u64 = value.parse().ok()?;
            if unit.eq_ignore_ascii_case("kb") {
                return Some(parsed);
            }
            return None;
        }
    }
    None
}

#[cfg(target_os = "linux")]
fn read_cgroup_u64(path: &str) -> Option<u64> {
    std::fs::read_to_string(path)
        .ok()
        .and_then(|s| s.trim().parse::<u64>().ok())
}

#[cfg(target_os = "linux")]
fn parse_memory_events_field(path: &str, field: &str) -> Option<u64> {
    let contents = std::fs::read_to_string(path).ok()?;
    for line in contents.lines() {
        let mut it = line.split_ascii_whitespace();
        let key = it.next()?;
        let value = it.next()?;
        if key == field {
            return value.parse::<u64>().ok();
        }
    }
    None
}

/// Structured RSS/HWM/cgroup snapshot; every field is `Option<u64>`
/// so the same serialiser handles Linux + Windows.
#[derive(Debug, Clone)]
pub struct MemorySample {
    pub rss_bytes: Option<u64>,
    pub hwm_bytes: Option<u64>,
    pub cgroup_current_bytes: Option<u64>,
    pub cgroup_peak_bytes: Option<u64>,
    pub cgroup_oom_kill_count: Option<u64>,
}

impl MemorySample {
    pub fn take() -> Self {
        Self {
            rss_bytes: read_vm_rss_bytes(),
            hwm_bytes: read_vm_hwm_bytes(),
            cgroup_current_bytes: read_cgroup_memory_current_bytes(),
            cgroup_peak_bytes: read_cgroup_memory_peak_bytes(),
            cgroup_oom_kill_count: read_cgroup_oom_kill_count(),
        }
    }
}
