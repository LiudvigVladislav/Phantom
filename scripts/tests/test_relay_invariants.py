# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
"""PR-2 M6-2 -- unit fixtures for `scripts/relay_invariants.py`.

Every gate (I1..I5) gets at least one positive fixture (the
invariant holds -- `violations == []`) and one negative fixture
(the invariant is broken -- non-empty violation list matching an
expected substring).

Round-1 REDLINE additions:
  * I2 fires on the 5th writer in a grandfathered file
    (count-based baseline enforcement, not skip-file).
  * I5 fires when a `#[cfg(test)] static FLAG;` line
    (previously mis-classified by `_strip_test_regions`)
    precedes an undocumented production env var.
  * I4 fires when a bench-harness needle appears in a
    workflow other than `relay-invariants.yml`.
  * I5 fires when an undocumented `RELAY_*_KEY` name is
    added.

M6-3 round-1 REDLINE P1-1 note: the real
`I2_LEGACY_WRITER_BASELINE` is now empty (routes.rs writers
were removed). To keep the I2 fixture set exercising the
identity- and count-based comparison, every I2 test patches
`ri.I2_LEGACY_WRITER_BASELINE` to a synthetic
`_TEST_I2_BASELINE` for the test's duration; the fixture
`routes.rs` shape matches that synthetic baseline. Tests
therefore stay meaningful even after the real baseline
empties.

Fixtures use `unittest.TestCase.setUp` to build a fake repo
layout under `tempfile.TemporaryDirectory()` and pass its Path
to the check under test. No dependency on the real repo state;
running via `python -m unittest discover scripts/tests` succeeds
on a fresh clone that has NOT yet landed M6-2/M6-3 changes as
long as the script file itself is on disk.
"""

from __future__ import annotations

import sys
import tempfile
import textwrap
import unittest
from pathlib import Path
from unittest import mock

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO_ROOT / "scripts"))

import relay_invariants as ri  # noqa: E402


# Synthetic I2 baseline used by the fixture layer -- independent
# of `ri.I2_LEGACY_WRITER_BASELINE` (which is empty on the real
# repo after M6-3 removed the routes.rs legacy writers). Every I2
# test patches `ri.I2_LEGACY_WRITER_BASELINE` to this dict for
# the test's duration.
_TEST_I2_BASELINE: dict[str, dict[str, int]] = {
    "services/relay/src/routes.rs": {
        "handle_socket": 1,
        "send_envelope": 1,
        "fetch_envelopes": 1,
        "ack_envelope": 1,
    },
}


def _write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(textwrap.dedent(content), encoding="utf-8")


def _routes_body_with_writers(fn_specs: list[tuple[str, int]]) -> str:
    """Emit a routes.rs body containing one fn per (name,
    write_count) pair. Each fn is an `async fn NAME(state:
    AppState) { ... }` with `write_count` copies of
    `state.store.write().await` in its body."""
    parts = []
    for name, count in fn_specs:
        writers = "\n            ".join(
            "let _ = state.store.write().await;" for _ in range(count)
        )
        parts.append(
            f"""
        pub async fn {name}(state: AppState) {{
            {writers}
        }}
        """
        )
    return textwrap.dedent("\n".join(parts))


def _test_baseline_fn_specs() -> list[tuple[str, int]]:
    """Sorted (fn, expected_count) pairs from the synthetic
    fixture baseline `_TEST_I2_BASELINE`."""
    return sorted(_TEST_I2_BASELINE["services/relay/src/routes.rs"].items())


def _bootstrap_repo(root: Path) -> None:
    """Minimum layout: state matching all invariants -- POSITIVE
    baseline that every check should pass on before a negative
    fixture perturbs a single input.

    The routes.rs shape matches the synthetic `_TEST_I2_BASELINE`
    exactly, so I2 tests that also patch
    `ri.I2_LEGACY_WRITER_BASELINE` to `_TEST_I2_BASELINE` pass.
    Tests that DO NOT patch (BaselineFixtureTests) rely on
    routes.rs being absent OR carrying zero writers -- see the
    class docstring for the exact shape.
    """
    r = ri.RepoPaths(root)
    r.src.mkdir(parents=True, exist_ok=True)
    r.tests.mkdir(parents=True, exist_ok=True)
    r.deploy_dir.mkdir(parents=True, exist_ok=True)
    (root / ".github" / "workflows").mkdir(parents=True, exist_ok=True)
    _write(
        r.main_rs,
        """
        //! test-fixture main.rs
        const DEFAULT_RELAY_RAM_BUDGET_BYTES: u64 = 80 * 1024 * 1024;

        fn caps() {
            parse_u64_env("RELAY_QUEUE_RAM_BUDGET_BYTES", DEFAULT_RELAY_RAM_BUDGET_BYTES);
            parse_u64_env("RELAY_PREFLIGHT_RAM_BUDGET", DEFAULT_RELAY_RAM_BUDGET_BYTES);
            parse_u64_env("RELAY_STATE_DIR", "");
        }
        """,
    )
    _write(
        r.src / "atomic_write.rs",
        """
        use tempfile::NamedTempFile;
        pub fn f() -> NamedTempFile { unimplemented!() }
        """,
    )
    _write(
        r.src / "rest_workers.rs",
        """
        pub fn actor() {
            let mut w = state.store.write().await;
            drop(w);
        }
        """,
    )
    _write(r.src / "routes.rs", _routes_body_with_writers(_test_baseline_fn_specs()))
    _write(
        r.dockerfile,
        """
        FROM rust:1.88 AS builder
        COPY services/relay ./relay
        RUN cargo build --release
        """,
    )
    _write(
        r.compose,
        """
        services:
          relay:
            environment:
              RELAY_QUEUE_RAM_BUDGET_BYTES: "83886080"
              RELAY_PREFLIGHT_RAM_BUDGET: "83886080"
              RELAY_STATE_DIR: /var/phantom
            deploy:
              resources:
                limits:
                  memory: 512M
        """,
    )
    _write(
        root / ".github" / "workflows" / "unrelated.yml",
        """
        name: unrelated
        on: [push]
        jobs:
          noop:
            runs-on: ubuntu-latest
            steps:
              - run: echo hi
        """,
    )
    _write(
        r.env_reference,
        """
        # Relay env reference (test fixture)

        - RELAY_QUEUE_RAM_BUDGET_BYTES
        - RELAY_PREFLIGHT_RAM_BUDGET
        - RELAY_STATE_DIR
        """,
    )


def _patch_i2_baseline():
    """Return a context manager that patches
    `ri.I2_LEGACY_WRITER_BASELINE` to `_TEST_I2_BASELINE` for the
    body's duration. Every I2 test uses this so the fixture
    layer stays decoupled from the real production baseline."""
    return mock.patch.object(ri, "I2_LEGACY_WRITER_BASELINE", _TEST_I2_BASELINE)


class BaselineFixtureTests(unittest.TestCase):
    """The synthetic-baseline layout must pass every check.
    Guards the fixtures themselves against silent drift.

    For I2, patch `ri.I2_LEGACY_WRITER_BASELINE` to the synthetic
    fixture baseline. On the real repo the baseline is empty and
    the fixture's routes.rs carries 4 writers -- without the
    patch that would trip "path not in I2_LEGACY_WRITER_BASELINE".
    """

    def test_baseline_passes_all_checks(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            _bootstrap_repo(root)
            with _patch_i2_baseline():
                for name, fn in ri.ALL_CHECKS.items():
                    with self.subTest(check=name):
                        res = fn(root)
                        self.assertEqual(
                            res.violations,
                            [],
                            f"{name} unexpectedly failed on baseline: {res.violations}",
                        )

    def test_real_baseline_grandfathers_no_files(self) -> None:
        """Round-1 REDLINE P1-1 guard: the REAL
        `I2_LEGACY_WRITER_BASELINE` in `relay_invariants.py`
        must stay empty. Re-adding an entry undoes the M6-3
        code migration."""
        self.assertEqual(
            ri.I2_LEGACY_WRITER_BASELINE,
            {},
            "I2_LEGACY_WRITER_BASELINE is non-empty; PR-2 M6-3 round-1 "
            "P1-1 removed the routes.rs legacy writers so the real "
            "baseline should stay empty. Any new entry requires a "
            "fresh legacy bypass with rationale.",
        )


class I1TempfilePolicyTests(unittest.TestCase):
    def test_positive_atomic_write_only(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            _bootstrap_repo(root)
            with _patch_i2_baseline():
                self.assertTrue(ri.check_i1(root).passed)

    def test_negative_named_tempfile_in_main(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            _bootstrap_repo(root)
            _write(
                ri.RepoPaths(root).src / "main.rs",
                """
                use tempfile::NamedTempFile;
                const DEFAULT_RELAY_RAM_BUDGET_BYTES: u64 = 80 * 1024 * 1024;
                fn f() {
                    parse_u64_env("RELAY_QUEUE_RAM_BUDGET_BYTES", DEFAULT_RELAY_RAM_BUDGET_BYTES);
                    parse_u64_env("RELAY_PREFLIGHT_RAM_BUDGET", DEFAULT_RELAY_RAM_BUDGET_BYTES);
                    parse_u64_env("RELAY_STATE_DIR", "");
                }
                """,
            )
            res = ri.check_i1(root)
            self.assertFalse(res.passed)
            self.assertTrue(any("NamedTempFile" in v for v in res.violations))

    def test_negative_env_temp_dir_in_test_file(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            _bootstrap_repo(root)
            _write(
                ri.RepoPaths(root).tests / "my_test.rs",
                """
                #[test]
                fn t() {
                    let d = std::env::temp_dir();
                    let _ = d;
                }
                """,
            )
            res = ri.check_i1(root)
            self.assertFalse(res.passed)
            self.assertTrue(any("env::temp_dir" in v for v in res.violations))


class I2StoreTripwireTests(unittest.TestCase):
    def test_positive_shard_worker_and_baseline_grandfathered(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            _bootstrap_repo(root)
            with _patch_i2_baseline():
                self.assertTrue(ri.check_i2(root).passed)

    def test_negative_new_file_writes_store(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            _bootstrap_repo(root)
            _write(
                ri.RepoPaths(root).src / "new_handler.rs",
                """
                pub async fn h(state: AppState) {
                    let mut w = state.store.write().await;
                    drop(w);
                }
                """,
            )
            with _patch_i2_baseline():
                res = ri.check_i2(root)
            self.assertFalse(res.passed)
            self.assertTrue(any("new_handler.rs" in v for v in res.violations))

    def test_negative_multi_line_write_chain(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            _bootstrap_repo(root)
            _write(
                ri.RepoPaths(root).src / "delayed_handler.rs",
                """
                pub async fn h(runtime: WorkerRuntime) {
                    let store = runtime.rest_store();
                    // intervening line
                    let mut w = store.write().await;
                    drop(w);
                }
                """,
            )
            with _patch_i2_baseline():
                res = ri.check_i2(root)
            self.assertFalse(res.passed)
            self.assertTrue(any("delayed_handler.rs" in v for v in res.violations))

    def test_negative_extra_writer_in_grandfathered_file(self) -> None:
        """Round-1 REDLINE P1-1: routes.rs is grandfathered at
        exactly the `I2_LEGACY_WRITER_BASELINE` fn set. Adding a
        NEW writer inside a new fn must fire."""
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            _bootstrap_repo(root)
            _write(
                ri.RepoPaths(root).src / "routes.rs",
                _routes_body_with_writers(
                    _test_baseline_fn_specs() + [("extra_writer", 1)]
                ),
            )
            with _patch_i2_baseline():
                res = ri.check_i2(root)
            self.assertFalse(res.passed)
            self.assertTrue(any("extra_writer" in v for v in res.violations))
            self.assertTrue(any("unexpected writer" in v for v in res.violations))

    def test_negative_migration_removes_baseline_fn(self) -> None:
        """Symmetric: removing / renaming a baseline writer
        without updating I2_LEGACY_WRITER_BASELINE is also a
        violation. The baseline set must shrink in the same
        commit that migrates a fn."""
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            _bootstrap_repo(root)
            shortened = _test_baseline_fn_specs()[:-1]
            _write(
                ri.RepoPaths(root).src / "routes.rs",
                _routes_body_with_writers(shortened),
            )
            with _patch_i2_baseline():
                res = ri.check_i2(root)
            self.assertFalse(res.passed)
            self.assertTrue(
                any("baseline expects writers in" in v for v in res.violations)
            )

    def test_negative_replacement_writers_same_count_new_fns(self) -> None:
        """Round-2 REDLINE P1-1: replacing ALL baseline writers
        with the SAME NUMBER of NEW writers under different
        function names must fire. Round-1's count-only shape
        passed this case; round-2's identity-based baseline
        catches it."""
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            _bootstrap_repo(root)
            replacement_specs = [
                (f"replaced_{i}", 1) for i in range(len(_test_baseline_fn_specs()))
            ]
            _write(
                ri.RepoPaths(root).src / "routes.rs",
                _routes_body_with_writers(replacement_specs),
            )
            with _patch_i2_baseline():
                res = ri.check_i2(root)
            self.assertFalse(res.passed)
            self.assertTrue(any("unexpected writer" in v for v in res.violations))
            self.assertTrue(
                any("baseline expects writers in" in v for v in res.violations)
            )

    def test_negative_nested_basename_shadow_of_rest_workers(self) -> None:
        """Round-2 REDLINE P1-1: pre-amendment `f.name ==
        "rest_workers.rs"` matched a basename anywhere in the
        tree. Someone could create
        `services/relay/src/handlers/rest_workers.rs` and
        inherit the shard-worker actor exemption. The
        path-based `I2_EXEMPT_PATHS` closes this."""
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            _bootstrap_repo(root)
            _write(
                ri.RepoPaths(root).src / "handlers" / "rest_workers.rs",
                """
                pub async fn nested_bypass(state: AppState) {
                    let mut w = state.store.write().await;
                    drop(w);
                }
                """,
            )
            with _patch_i2_baseline():
                res = ri.check_i2(root)
            self.assertFalse(res.passed)
            self.assertTrue(
                any("handlers/rest_workers.rs" in v for v in res.violations)
            )
            self.assertTrue(
                any("this path is not in I2_LEGACY_WRITER_BASELINE" in v for v in res.violations)
            )

    def test_negative_second_writer_inside_baseline_fn(self) -> None:
        """Round-3 REDLINE P1: adding a SECOND writer inside a
        function the baseline permits at count=1. Round-2's
        set-based comparison collapsed duplicates and returned
        PASS (set of fn names still matched); round-3's per-fn
        Counter-style baseline catches the drift."""
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            _bootstrap_repo(root)
            baseline = _test_baseline_fn_specs()
            self.assertGreater(len(baseline), 0)
            first_fn, first_count = baseline[0]
            drifted_specs = [(first_fn, first_count + 1)] + baseline[1:]
            _write(
                ri.RepoPaths(root).src / "routes.rs",
                _routes_body_with_writers(drifted_specs),
            )
            with _patch_i2_baseline():
                res = ri.check_i2(root)
            self.assertFalse(res.passed)
            self.assertTrue(any("writer-count drift" in v for v in res.violations))
            self.assertTrue(
                any(
                    first_fn in v and f"found={first_count + 1}" in v and f"expected={first_count}" in v
                    for v in res.violations
                )
            )


class I3ConstantVsComposeTests(unittest.TestCase):
    def test_positive_match(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            _bootstrap_repo(root)
            self.assertTrue(ri.check_i3(root).passed)

    def test_negative_compose_drifted(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            _bootstrap_repo(root)
            _write(
                ri.RepoPaths(root).compose,
                """
                services:
                  relay:
                    environment:
                      RELAY_QUEUE_RAM_BUDGET_BYTES: "402653184"
                      RELAY_PREFLIGHT_RAM_BUDGET: "83886080"
                """,
            )
            res = ri.check_i3(root)
            self.assertFalse(res.passed)
            self.assertTrue(any("402653184" in v for v in res.violations))

    def test_negative_missing_env(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            _bootstrap_repo(root)
            _write(
                ri.RepoPaths(root).compose,
                """
                services:
                  relay:
                    environment:
                      RELAY_STATE_DIR: /var/phantom
                """,
            )
            res = ri.check_i3(root)
            self.assertFalse(res.passed)
            self.assertTrue(
                any("does not set RELAY_QUEUE_RAM_BUDGET_BYTES" in v for v in res.violations)
            )

    def test_negative_missing_constant(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            _bootstrap_repo(root)
            _write(ri.RepoPaths(root).main_rs, "fn main() {}\n")
            res = ri.check_i3(root)
            self.assertFalse(res.passed)
            self.assertTrue(any("could not locate" in v for v in res.violations))


class I4BenchLeakTests(unittest.TestCase):
    def test_positive_no_leak(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            _bootstrap_repo(root)
            self.assertTrue(ri.check_i4(root).passed)

    def test_negative_bench_reference_in_src(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            _bootstrap_repo(root)
            _write(
                ri.RepoPaths(root).src / "leak.rs",
                "// oops: bench/queue_ram_budget/main.rs\n",
            )
            res = ri.check_i4(root)
            self.assertFalse(res.passed)
            self.assertTrue(any("bench/queue_ram_budget" in v for v in res.violations))

    def test_negative_bench_reference_in_dockerfile(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            _bootstrap_repo(root)
            _write(
                ri.RepoPaths(root).dockerfile,
                """
                FROM rust:1.88 AS builder
                RUN cargo build --release --features queue-ram-budget-bench
                """,
            )
            res = ri.check_i4(root)
            self.assertFalse(res.passed)
            self.assertTrue(
                any("queue-ram-budget-bench" in v for v in res.violations)
            )

    def test_negative_bench_reference_in_compose(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            _bootstrap_repo(root)
            _write(
                ri.RepoPaths(root).compose,
                """
                services:
                  relay:
                    environment:
                      RELAY_QUEUE_RAM_BUDGET_BYTES: "83886080"
                      RELAY_PREFLIGHT_RAM_BUDGET: "83886080"
                      RELAY_STATE_DIR: /var/phantom
                  bench:
                    image: phantom-relay-bench:m5b
                    entrypoint: [/usr/local/bin/queue_ram_budget_bench]
                """,
            )
            res = ri.check_i4(root)
            self.assertFalse(res.passed)
            self.assertTrue(any("queue_ram_budget_bench" in v for v in res.violations))

    def test_negative_bench_reference_in_workflow(self) -> None:
        """Round-1 REDLINE P1-3: bench-harness needle in any
        workflow OTHER than relay-invariants.yml must fire."""
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            _bootstrap_repo(root)
            _write(
                root / ".github" / "workflows" / "rogue-bench.yml",
                """
                name: rogue
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - run: cargo build --features queue-ram-budget-bench
                """,
            )
            res = ri.check_i4(root)
            self.assertFalse(res.passed)
            self.assertTrue(
                any(
                    "rogue-bench.yml" in v and "queue-ram-budget-bench" in v
                    for v in res.violations
                )
            )

    def test_negative_bench_cmd_in_relay_invariants_workflow(self) -> None:
        """Round-2 REDLINE P1-2: pre-amendment I4 exempted the
        `relay-invariants.yml` workflow file by name, so someone
        could add a `cargo build --features
        queue-ram-budget-bench` step directly there and I4
        would return PASS. Round-2 removes the exemption --
        the real workflow ships with ZERO needle strings, so
        any occurrence in the file is either a real leak or a
        misplaced documentation string. Either way, fix it in
        the check-runner rather than exempting the file."""
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            _bootstrap_repo(root)
            _write(
                root / ".github" / "workflows" / "relay-invariants.yml",
                """
                name: relay-invariants
                on: [push, pull_request]
                jobs:
                  malicious:
                    runs-on: ubuntu-latest
                    steps:
                      - run: cargo build --features queue-ram-budget-bench
                """,
            )
            res = ri.check_i4(root)
            self.assertFalse(res.passed)
            self.assertTrue(
                any(
                    "relay-invariants.yml" in v and "queue-ram-budget-bench" in v
                    for v in res.violations
                )
            )


class I5EnvReferenceTests(unittest.TestCase):
    def test_positive_complete_reference(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            _bootstrap_repo(root)
            self.assertTrue(ri.check_i5(root).passed)

    def test_negative_missing_reference_file(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            _bootstrap_repo(root)
            ri.RepoPaths(root).env_reference.unlink()
            res = ri.check_i5(root)
            self.assertFalse(res.passed)
            self.assertTrue(any("missing" in v for v in res.violations))

    def test_negative_undocumented_env_var(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            _bootstrap_repo(root)
            main = ri.RepoPaths(root).main_rs
            main.write_text(
                main.read_text(encoding="utf-8")
                + '\nfn extra() { parse_u64_env("RELAY_UNDOCUMENTED_KNOB", 0); }\n',
                encoding="utf-8",
            )
            res = ri.check_i5(root)
            self.assertFalse(res.passed)
            self.assertTrue(any("RELAY_UNDOCUMENTED_KNOB" in v for v in res.violations))

    def test_negative_undocumented_key_suffix(self) -> None:
        """Round-1 REDLINE P1-4: the pre-amendment `_KEY`
        suffix exemption silently let secret material through
        (RELAY_SEQ_MAC_KEY, hypothetical
        RELAY_NEW_PRODUCTION_KEY). Now every `_KEY` name must
        be either documented OR listed in the explicit
        I5_TEST_ONLY_ALLOWLIST."""
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            _bootstrap_repo(root)
            main = ri.RepoPaths(root).main_rs
            main.write_text(
                main.read_text(encoding="utf-8")
                + '\nfn extra() { let _ = "RELAY_NEW_PRODUCTION_KEY"; }\n',
                encoding="utf-8",
            )
            res = ri.check_i5(root)
            self.assertFalse(res.passed)
            self.assertTrue(any("RELAY_NEW_PRODUCTION_KEY" in v for v in res.violations))

    def test_negative_undocumented_env_after_cfg_test_static(self) -> None:
        """Round-1 REDLINE P1-2: the pre-amendment
        `_strip_test_regions` stripper searched for a `{`
        after `#[cfg(test)]` and swallowed the next
        production block; a `#[cfg(test)] static FLAG;`
        line followed by an undocumented production env var
        would erroneously PASS. Now with stripping removed,
        it must FAIL."""
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            _bootstrap_repo(root)
            _write(
                ri.RepoPaths(root).src / "with_static.rs",
                """
                #[cfg(test)]
                static UNRELATED_TEST_FLAG: bool = false;

                pub fn f() {
                    let _ = "RELAY_ANOTHER_UNDOCUMENTED";
                }
                """,
            )
            res = ri.check_i5(root)
            self.assertFalse(res.passed)
            self.assertTrue(any("RELAY_ANOTHER_UNDOCUMENTED" in v for v in res.violations))

    def test_positive_explicit_allowlist_excluded(self) -> None:
        """Only names in `I5_TEST_ONLY_ALLOWLIST` are exempt.
        The two known test-fixture env vars pass; anything
        else must be documented."""
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            _bootstrap_repo(root)
            main = ri.RepoPaths(root).main_rs
            main.write_text(
                main.read_text(encoding="utf-8")
                + '\n#[cfg(test)]\nmod t { fn f() { let _ = "RELAY_PARSE_U64_VALID_KEY"; } }\n',
                encoding="utf-8",
            )
            self.assertTrue(ri.check_i5(root).passed)


class RustExprEvaluatorTests(unittest.TestCase):
    def test_plain_integer(self) -> None:
        self.assertEqual(ri._eval_simple_rust_int_expr("83886080"), 83886080)

    def test_underscored_integer(self) -> None:
        self.assertEqual(ri._eval_simple_rust_int_expr("83_886_080"), 83886080)

    def test_multiplication(self) -> None:
        self.assertEqual(ri._eval_simple_rust_int_expr("80 * 1024 * 1024"), 83886080)

    def test_refuses_addition(self) -> None:
        self.assertIsNone(ri._eval_simple_rust_int_expr("80 + 1024"))

    def test_refuses_parens(self) -> None:
        self.assertIsNone(ri._eval_simple_rust_int_expr("(80 * 1024) * 1024"))

    def test_refuses_hex(self) -> None:
        self.assertIsNone(ri._eval_simple_rust_int_expr("0x1000"))


if __name__ == "__main__":
    unittest.main()
