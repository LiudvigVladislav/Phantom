# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
"""PR-2 M6-2 -- relay-side invariant checks (importable + CLI).

Five scoped tripwires flagging drift on invariants that cannot
be enforced by rustc alone:

    I1  tempfile / env::temp_dir policy
    I2  store-mutation bypass (tripwire, not proof)
    I3  Rust constant vs compose value equality (structural)
    I4  bench-harness leakage into production
    I5  every production RELAY_* env var documented

Each check is a function `check_iN(repo_root: Path) -> list[str]`
returning a list of human-readable violation strings. The CLI
main() runs all five and prints per-check verdicts; empty
violation list -> PASS, any entries -> FAIL.

Module is import-safe: no side-effects at import time. Tests in
`scripts/tests/test_relay_invariants.py` exercise each check
against synthetic repo layouts under `tmp_path`. Any change to
the invariants MUST update the fixtures in the same commit.

Invocation:
    python scripts/relay_invariants.py [--check IN[,IN...]]
    python scripts/relay_invariants.py --list

Exit codes:
    0   all checks PASS
    1   one or more violations
    2   bad CLI usage
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Iterable

# ─── constants ────────────────────────────────────────────

# Bench-harness needles that MUST NOT appear in production
# code, deploy files, or the production Dockerfile. Guards
# the M5b opt-in feature gate from silent bleed-through.
BENCH_NEEDLES = (
    "queue_ram_budget_bench",
    "queue-ram-budget-bench",
    "bench/queue_ram_budget",
    "Dockerfile.bench",
)

# I2 legacy-writer baseline (round-3 REDLINE P1 fix): pre-M6
# store-mutation bypass sites that M4 did NOT migrate to a
# shard-worker RestOp. The baseline pins EXACT hit counts
# PER function -- round-2's set-based shape let a second
# writer sneak into a baseline-permitted fn (a set collapses
# duplicate fn names into one entry, so 2 writers in
# handle_socket looked identical to 1).
#
# The check fires on ANY deviation:
#   * writer in an un-listed file / un-listed function -> new bypass
#   * writer count in a listed function != baseline count -> new bypass or migration
#   * baseline fn with zero writers -> migration; update baseline
#
# The exempt path list mirrors the same key shape (exact
# relative path, POSIX slashes). Basename matching let an
# attacker create `nested/rest_workers.rs` and inherit the
# shard-worker actor exemption.
#
# Follow-up (out of PR-2 scope): migrate routes.rs writers to
# send RestOp::Send/Ack via WorkerRuntime::try_send so every
# per-fn count drops to zero and this baseline empties.
I2_EXEMPT_PATHS: frozenset[str] = frozenset({
    "services/relay/src/rest_workers.rs",
})

I2_LEGACY_WRITER_BASELINE: dict[str, dict[str, int]] = {
    # PR-2 M6-3 round-1 REDLINE P1-1: the routes.rs legacy
    # writers are gone. Three admin-token-guarded handlers
    # (`send_envelope`, `fetch_envelopes`, `ack_envelope`)
    # were removed entirely; `handle_socket`'s reconnect
    # expiry path is now read-only (filter + clone, no
    # `retain` on the shared Arc). The routes.rs entry that
    # used to sit here has been dropped so I2 no longer
    # grandfathers ANY writer path outside `rest_workers.rs`.
    #
    # Adding a new entry here requires a NEW pre-M6-3 style
    # legacy bypass -- and a strong rationale in the commit.
}

# I5 test-only env var allowlist (round-1 REDLINE P1-4 fix):
# EXPLICIT names, not a suffix pattern. The pre-amendment
# `_KEY` suffix exemption meant every future production
# `RELAY_*_KEY` env var (secret material, session keys,
# feature-gate keys) would silently escape the documentation
# gate. Only these two literal test-fixture names are exempt;
# everything else must appear in `relay-env-reference.md`.
#
# Adding a new production `RELAY_*_KEY` REQUIRES adding a row
# to the env reference. Adding a new test-only fixture name
# to this set REQUIRES architect sign-off (it weakens the
# gate).
I5_TEST_ONLY_ALLOWLIST: frozenset[str] = frozenset({
    "RELAY_PARSE_U64_DEFAULT_KEY",
    "RELAY_PARSE_U64_VALID_KEY",
})


@dataclass(frozen=True)
class RepoPaths:
    """Anchored file/directory paths derived from a repo root."""

    root: Path

    @property
    def src(self) -> Path:
        return self.root / "services" / "relay" / "src"

    @property
    def tests(self) -> Path:
        return self.root / "services" / "relay" / "tests"

    @property
    def dockerfile(self) -> Path:
        return self.root / "services" / "relay" / "Dockerfile"

    @property
    def deploy_dir(self) -> Path:
        return self.root / "deploy"

    @property
    def compose(self) -> Path:
        return self.deploy_dir / "docker-compose.yml"

    @property
    def env_reference(self) -> Path:
        return self.root / "docs" / "operations" / "relay-env-reference.md"

    @property
    def main_rs(self) -> Path:
        return self.src / "main.rs"


@dataclass
class CheckResult:
    name: str
    description: str
    violations: list[str] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        return not self.violations


# ─── helpers ──────────────────────────────────────────────


def _iter_rust_files(root: Path) -> Iterable[Path]:
    """Yield every *.rs file under `root`, deterministic order."""
    if not root.exists():
        return
    yield from sorted(p for p in root.rglob("*.rs") if p.is_file())


def _read_text_safe(path: Path) -> str | None:
    """UTF-8 read; return None on non-text files."""
    try:
        return path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, PermissionError, OSError):
        return None


# ─── I1: tempfile + env::temp_dir policy ─────────────────


def check_i1(repo_root: Path) -> CheckResult:
    """`NamedTempFile` may only appear in atomic_write.rs;
    `env::temp_dir` is fleet-wide-forbidden (including tests --
    tests must use `tempfile::TempDir` for state-dir-scoped
    fixtures).
    """
    r = RepoPaths(repo_root)
    result = CheckResult(
        "I1",
        "tempfile/env::temp_dir policy (NamedTempFile -> atomic_write.rs only; env::temp_dir forbidden fleet-wide)",
    )
    roots = [r.src, r.tests]
    for root in roots:
        for f in _iter_rust_files(root):
            text = _read_text_safe(f)
            if text is None:
                continue
            for lineno, raw in enumerate(text.splitlines(), 1):
                line = raw.strip()
                if line.startswith("//"):
                    continue
                if "NamedTempFile" in line and f.name != "atomic_write.rs":
                    result.violations.append(
                        f"I1: {f.relative_to(repo_root)}:{lineno}: NamedTempFile "
                        f"outside atomic_write.rs (state-dir-scoped writes only)"
                    )
                if "env::temp_dir" in line:
                    result.violations.append(
                        f"I1: {f.relative_to(repo_root)}:{lineno}: env::temp_dir "
                        f"forbidden fleet-wide (use tempfile::TempDir)"
                    )
    return result


# ─── I2: store-mutation tripwire ─────────────────────────


_I2_WRITE_ACCESS = re.compile(
    r"\.(?:rest_store|store)"
    r"(?:\(\))?"
    r"\s*"
    r"\.\s*write\b"
)


_I2_FN_DEF = re.compile(
    r"^\s*(?:pub(?:\(\s*\w+\s*\))?\s+)?(?:async\s+)?fn\s+(?P<name>[A-Za-z_][A-Za-z0-9_]*)\b"
)


def _enclosing_fn_name(lines: list[str], hit_lineno: int) -> str | None:
    """Walk backward from `hit_lineno` (1-indexed) looking for
    the nearest `[pub[(vis)] ][async ]?fn NAME` at any indent
    level. Returns the fn name or None if nothing matches
    (module-level statement, macro expansion, etc.)."""
    for i in range(hit_lineno - 1, -1, -1):
        m = _I2_FN_DEF.match(lines[i])
        if m:
            return m.group("name")
    return None


def _find_i2_hits(text: str) -> list[tuple[int, str | None]]:
    r"""Find write-access matches in `text`. Returns a list of
    `(line_number, enclosing_fn_name_or_None)` tuples.

    Catches (unchanged from round-1):
      * single-line `.rest_store/.store(.|())\s*.\s*write\b`
      * multi-line: accessor on line N followed by `.write` on
        one of lines N+1..N+3 (naive tripwire, alias vars not
        caught).

    Doc-comment lines (`//`, `///`) are skipped.
    """
    lines = text.splitlines()
    hit_lines: set[int] = set()
    for lineno, raw in enumerate(lines, 1):
        if raw.strip().startswith("//"):
            continue
        if _I2_WRITE_ACCESS.search(raw):
            hit_lines.add(lineno)
    for lineno, raw in enumerate(lines, 1):
        if lineno in hit_lines:
            continue
        if raw.strip().startswith("//"):
            continue
        if not re.search(r"\.(?:rest_store|store)(?:\(\))?", raw):
            continue
        if _I2_WRITE_ACCESS.search(raw):
            continue
        subsequent = "\n".join(lines[lineno : min(len(lines), lineno + 3)])
        if re.search(r"\.\s*write\b", subsequent):
            hit_lines.add(lineno)
    return [(ln, _enclosing_fn_name(lines, ln)) for ln in sorted(hit_lines)]


def check_i2(repo_root: Path) -> CheckResult:
    """Field/accessor `.rest_store(.|())` or `.store(.|())`
    followed by `.write` in any production file OTHER than
    the exact-path exemptions in `I2_EXEMPT_PATHS` is flagged
    as a possible alias-bypass of the per-recipient
    serialisation contract.

    Round-3 REDLINE P1 correction: the baseline pins EXACT
    hit counts PER function -- round-2's set-only baseline
    swallowed count deltas inside a permitted fn (2 writers
    in `handle_socket` looked identical to 1). The comparison
    is now Counter-style: baseline dict {fn: expected_count}
    vs actual dict {fn: found_count}; ANY per-fn count
    mismatch fires, PLUS unexpected fn AND missing baseline
    fn diagnostics are unchanged from round-2.

    Explicit tripwire, NOT proof of no bypass -- a determined
    variable-alias will not be caught. See P1-2 removal of the
    `_strip_test_regions` heuristic; if a writer appears in a
    test-mod inside an src file, it counts just like any
    production writer.
    """
    r = RepoPaths(repo_root)
    baseline_desc = (
        ", ".join(
            f"{k}={dict(sorted(v.items()))}"
            for k, v in sorted(I2_LEGACY_WRITER_BASELINE.items())
        )
        or "(none)"
    )
    result = CheckResult(
        "I2",
        "store-mutation tripwire (.rest_store/.store followed by .write outside "
        + ", ".join(sorted(I2_EXEMPT_PATHS))
        + "; legacy-writer baseline (path->{fn: count}): "
        + baseline_desc
        + ")",
    )
    for f in _iter_rust_files(r.src):
        rel = f.relative_to(repo_root).as_posix()
        if rel in I2_EXEMPT_PATHS:
            continue
        text = _read_text_safe(f)
        if text is None:
            continue
        hits = _find_i2_hits(text)
        if not hits and rel not in I2_LEGACY_WRITER_BASELINE:
            continue
        expected: dict[str, int] = I2_LEGACY_WRITER_BASELINE.get(rel, {})
        # Per-fn hit counts (None enclosing fn -> "<anon>" key).
        found_counts: dict[str, int] = {}
        for _, fn_name in hits:
            key = fn_name if fn_name is not None else "<anon>"
            found_counts[key] = found_counts.get(key, 0) + 1
        anon_hits = [(ln, n) for (ln, n) in hits if n is None]
        if rel not in I2_LEGACY_WRITER_BASELINE and hits:
            result.violations.append(
                f"I2: {rel}: found {len(hits)} write-access site(s) to "
                f"`.rest_store/.store` at lines {[ln for ln, _ in hits]} "
                f"(per-fn: {dict(sorted(found_counts.items()))}); this "
                f"path is not in I2_LEGACY_WRITER_BASELINE -- route through "
                f"WorkerRuntime::try_send OR add the path + per-fn counts "
                f"with a rationale"
            )
            continue
        if anon_hits:
            result.violations.append(
                f"I2: {rel}: {len(anon_hits)} write-access hit(s) "
                f"outside any function (lines {[ln for ln, _ in anon_hits]}); "
                f"identity-based baseline cannot key these -- refactor into "
                f"a named fn"
            )
        all_fn_names = set(expected.keys()) | set(found_counts.keys()) - {"<anon>"}
        unexpected_fns = [
            fn for fn in sorted(all_fn_names) if fn not in expected and found_counts.get(fn, 0) > 0
        ]
        missing_fns = [
            fn for fn in sorted(all_fn_names) if fn in expected and found_counts.get(fn, 0) == 0
        ]
        drifted_fns = [
            fn
            for fn in sorted(all_fn_names)
            if fn in expected
            and found_counts.get(fn, 0) > 0
            and found_counts[fn] != expected[fn]
        ]
        if unexpected_fns:
            result.violations.append(
                f"I2: {rel}: unexpected writer fn(s) "
                f"{unexpected_fns} (per-fn counts: "
                f"{ {fn: found_counts[fn] for fn in unexpected_fns} }) -- "
                f"baseline permits {dict(sorted(expected.items()))}. A new "
                f"bypass path was added; route through "
                f"WorkerRuntime::try_send OR extend "
                f"I2_LEGACY_WRITER_BASELINE with a rationale"
            )
        if missing_fns:
            result.violations.append(
                f"I2: {rel}: baseline expects writers in "
                f"{ {fn: expected[fn] for fn in missing_fns} } "
                f"but none matched; either the fn was renamed / removed "
                f"(migration completed -- shrink the baseline in the same "
                f"commit) or the writer was renamed / deleted"
            )
        if drifted_fns:
            drift_desc = ", ".join(
                f"{fn}: found={found_counts[fn]} expected={expected[fn]}"
                for fn in drifted_fns
            )
            result.violations.append(
                f"I2: {rel}: writer-count drift inside baseline fn(s) -- "
                f"{drift_desc}. A NEW writer was added inside a permitted "
                f"function (or an existing writer was removed without "
                f"updating the baseline)"
            )
    return result


# ─── I3: Rust constant vs compose value equality ──────────


_RUST_CONST_LINE = re.compile(
    r"^\s*const\s+DEFAULT_RELAY_RAM_BUDGET_BYTES\s*:\s*u64\s*=\s*(?P<expr>[^;]+?);",
    re.MULTILINE,
)


def _eval_simple_rust_int_expr(expr: str) -> int | None:
    """Evaluate a restricted `<int> * <int> * <int>` / plain int
    Rust literal. Refuses anything with parens, hex prefixes with
    `_` in the middle, or operators other than `*`. Returns None
    on any parse failure.
    """
    expr = expr.strip()
    # remove Rust integer underscores
    expr = expr.replace("_", "")
    if not re.fullmatch(r"[0-9]+(\s*\*\s*[0-9]+)*", expr):
        return None
    parts = [int(x.strip()) for x in expr.split("*")]
    total = 1
    for p in parts:
        total *= p
    return total


def _extract_compose_env(text: str, key: str) -> str | None:
    """Naive PyYAML-free extraction: find first
    `<key>: "<value>"` OR `<key>: <value>` line under any
    indentation. Suits the compose file's stable shape.
    """
    pattern = re.compile(rf"^\s*{re.escape(key)}\s*:\s*\"?([^\"\s]+)\"?\s*$", re.MULTILINE)
    m = pattern.search(text)
    return m.group(1) if m else None


def check_i3(repo_root: Path) -> CheckResult:
    """Three-way equality: Rust `DEFAULT_RELAY_RAM_BUDGET_BYTES`
    equals `services.relay.environment.RELAY_QUEUE_RAM_BUDGET_BYTES`
    equals `services.relay.environment.RELAY_PREFLIGHT_RAM_BUDGET`
    in `deploy/docker-compose.yml`.

    Uses structural parse of main.rs (regex-anchored on
    `const ... = <expr>;`) + structural parse of compose (regex
    on `<key>: "<value>"` under any indentation). The stronger
    `docker compose config --format json` normalisation lives
    in the deploy-lint `ram-budget-invariants` job which needs
    the docker CLI at runtime -- this check is the code-side
    tripwire that runs without docker.
    """
    r = RepoPaths(repo_root)
    result = CheckResult(
        "I3",
        "Rust constant vs compose value equality (structural)",
    )
    main_text = _read_text_safe(r.main_rs)
    if main_text is None:
        result.violations.append(f"I3: cannot read {r.main_rs.relative_to(repo_root)}")
        return result
    m = _RUST_CONST_LINE.search(main_text)
    if m is None:
        result.violations.append(
            f"I3: could not locate `const DEFAULT_RELAY_RAM_BUDGET_BYTES: u64 = ...;` in main.rs"
        )
        return result
    const_val = _eval_simple_rust_int_expr(m.group("expr"))
    if const_val is None:
        result.violations.append(
            f"I3: could not evaluate constant expression `{m.group('expr')}` "
            f"(only plain int * int * ... supported)"
        )
        return result
    compose_text = _read_text_safe(r.compose)
    if compose_text is None:
        result.violations.append(f"I3: cannot read {r.compose.relative_to(repo_root)}")
        return result
    queue_env = _extract_compose_env(compose_text, "RELAY_QUEUE_RAM_BUDGET_BYTES")
    preflight_env = _extract_compose_env(compose_text, "RELAY_PREFLIGHT_RAM_BUDGET")
    if queue_env is None:
        result.violations.append(
            f"I3: {r.compose.relative_to(repo_root)} does not set RELAY_QUEUE_RAM_BUDGET_BYTES"
        )
    if preflight_env is None:
        result.violations.append(
            f"I3: {r.compose.relative_to(repo_root)} does not set RELAY_PREFLIGHT_RAM_BUDGET"
        )
    if queue_env is not None:
        try:
            queue_int = int(queue_env)
        except ValueError:
            result.violations.append(
                f"I3: RELAY_QUEUE_RAM_BUDGET_BYTES compose value {queue_env!r} not a base-10 integer"
            )
        else:
            if queue_int != const_val:
                result.violations.append(
                    f"I3: RELAY_QUEUE_RAM_BUDGET_BYTES compose value {queue_int} "
                    f"!= Rust constant {const_val}"
                )
    if preflight_env is not None:
        try:
            preflight_int = int(preflight_env)
        except ValueError:
            result.violations.append(
                f"I3: RELAY_PREFLIGHT_RAM_BUDGET compose value {preflight_env!r} not a base-10 integer"
            )
        else:
            if preflight_int != const_val:
                result.violations.append(
                    f"I3: RELAY_PREFLIGHT_RAM_BUDGET compose value {preflight_int} "
                    f"!= Rust constant {const_val}"
                )
    return result


# ─── I4: bench harness must not leak into production ─────


def check_i4(repo_root: Path) -> CheckResult:
    """None of the bench-harness needles may appear in
    `services/relay/src/**`, `deploy/**`, `.github/workflows/**`
    (round-1 REDLINE P1-3 addition), or in the production
    `services/relay/Dockerfile`.

    `services/relay/Cargo.toml` IS allowed to reference the
    feature name / bin name -- the opt-in `[[bin]]
    required-features` gate is where the harness lives.
    Cargo.toml is NOT under any scanned root, so no exemption
    logic is required.

    Round-2 REDLINE P1-2 correction: no workflow file is
    exempted. Round-1 exempted `relay-invariants.yml` on
    the assumption it contained the needle strings as
    documentation, but the current workflow uses NONE of
    the needles verbatim (it invokes the Python script
    which owns the constants). Removing the exemption
    closes the "add `cargo build --features
    queue-ram-budget-bench` directly to the invariants
    workflow" bypass.

    The Python script `scripts/relay_invariants.py` itself
    lives outside every scanned root (I4 scans src/,
    deploy/, .github/workflows/, and the production
    Dockerfile) so the `BENCH_NEEDLES` constants inside it
    are not double-scanned.
    """
    r = RepoPaths(repo_root)
    result = CheckResult(
        "I4",
        "no production dependency on bench harness "
        "(needles: " + ", ".join(BENCH_NEEDLES) + ")",
    )
    workflows_dir = repo_root / ".github" / "workflows"
    scan_roots: list[Path] = [r.src, r.deploy_dir, workflows_dir]
    for root in scan_roots:
        if not root.exists():
            continue
        for f in sorted(p for p in root.rglob("*") if p.is_file()):
            text = _read_text_safe(f)
            if text is None:
                continue
            for lineno, raw in enumerate(text.splitlines(), 1):
                for needle in BENCH_NEEDLES:
                    if needle in raw:
                        result.violations.append(
                            f"I4: {f.relative_to(repo_root)}:{lineno}: "
                            f"contains bench-harness needle {needle!r}"
                        )
    # Production Dockerfile (single-file check).
    if r.dockerfile.exists():
        text = _read_text_safe(r.dockerfile)
        if text is not None:
            for lineno, raw in enumerate(text.splitlines(), 1):
                for needle in BENCH_NEEDLES:
                    if needle in raw:
                        result.violations.append(
                            f"I4: {r.dockerfile.relative_to(repo_root)}:{lineno}: "
                            f"contains bench-harness needle {needle!r}"
                        )
    return result


# ─── I5: every production RELAY_* env var documented ─────


_RELAY_ENV_PATTERN = re.compile(r'"(RELAY_[A-Z0-9_]+)"')


def _discover_prod_env_vars(src_root: Path) -> set[str]:
    """Discover `RELAY_*` env var names referenced as string
    literals under `src_root`. Round-1 REDLINE P1-2 removed
    the `_strip_test_regions` heuristic (item-boundary
    detection was unreliable for non-block items -- a
    `#[cfg(test)] static FLAG;` line would have caused the
    stripper to swallow the next production block). Instead,
    every occurrence is discovered and the explicit
    `I5_TEST_ONLY_ALLOWLIST` names are filtered out.

    Consequence: a new `RELAY_*` used ANYWHERE in src (test
    module or production) must be either documented in
    `relay-env-reference.md` or added to
    `I5_TEST_ONLY_ALLOWLIST` (which requires architect
    sign-off).
    """
    found: set[str] = set()
    for f in _iter_rust_files(src_root):
        text = _read_text_safe(f)
        if text is None:
            continue
        for m in _RELAY_ENV_PATTERN.finditer(text):
            name = m.group(1)
            if name in I5_TEST_ONLY_ALLOWLIST:
                continue
            found.add(name)
    return found


def check_i5(repo_root: Path) -> CheckResult:
    """Every production `RELAY_*` env var referenced in
    `services/relay/src/**` must appear literally in
    `docs/operations/relay-env-reference.md`. The check is a
    presence-in-file grep; formatting / ordering / operator
    context is out of scope. M6-2 lands a minimal skeleton to
    satisfy the invariant; M6-3 expands operational depth.
    """
    r = RepoPaths(repo_root)
    result = CheckResult(
        "I5",
        "every production RELAY_* env var appears in the operational env reference",
    )
    if not r.env_reference.exists():
        result.violations.append(
            f"I5: env reference missing: {r.env_reference.relative_to(repo_root)}"
        )
        return result
    ref_text = _read_text_safe(r.env_reference)
    if ref_text is None:
        result.violations.append(
            f"I5: cannot read env reference at {r.env_reference.relative_to(repo_root)}"
        )
        return result
    discovered = _discover_prod_env_vars(r.src)
    for name in sorted(discovered):
        if name not in ref_text:
            result.violations.append(
                f"I5: {name} referenced in production code but missing from "
                f"{r.env_reference.relative_to(repo_root)}"
            )
    return result


# ─── CLI dispatch ─────────────────────────────────────────


ALL_CHECKS: dict[str, Callable[[Path], CheckResult]] = {
    "I1": check_i1,
    "I2": check_i2,
    "I3": check_i3,
    "I4": check_i4,
    "I5": check_i5,
}


def run_checks(repo_root: Path, selected: list[str] | None = None) -> list[CheckResult]:
    keys = selected if selected else list(ALL_CHECKS.keys())
    return [ALL_CHECKS[k](repo_root) for k in keys]


def _print_summary(results: list[CheckResult]) -> bool:
    all_passed = True
    for res in results:
        if res.passed:
            print(f"[{res.name}] PASS -- {res.description}")
        else:
            all_passed = False
            print(f"[{res.name}] FAIL -- {res.description}")
            for v in res.violations:
                print(f"    {v}")
    return all_passed


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="relay_invariants",
        description="PR-2 M6-2 relay-side invariant checks.",
    )
    parser.add_argument(
        "--check",
        help=f"Comma-separated subset of {{{','.join(ALL_CHECKS)}}}. Default: all.",
        default=None,
    )
    parser.add_argument(
        "--list",
        action="store_true",
        help="List all available checks with descriptions and exit.",
    )
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=Path(__file__).resolve().parent.parent,
        help="Repository root (default: two levels up from this script).",
    )
    args = parser.parse_args(argv)

    if args.list:
        for name, fn in ALL_CHECKS.items():
            # instantiate against a bogus root to grab the description
            print(f"{name}\t{fn(Path('.')).description}")
        return 0

    selected: list[str] | None = None
    if args.check:
        parts = [p.strip().upper() for p in args.check.split(",") if p.strip()]
        for p in parts:
            if p not in ALL_CHECKS:
                print(f"ERROR: unknown check {p!r}; known: {','.join(ALL_CHECKS)}", file=sys.stderr)
                return 2
        selected = parts

    results = run_checks(args.repo_root, selected=selected)
    all_passed = _print_summary(results)
    return 0 if all_passed else 1


if __name__ == "__main__":
    sys.exit(main())
