#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
"""WSS-3 Carrier x VPN Matrix — 8-row report CLI.

Wraps verify_evidence_wss3.verify_evidence_root and emits a single
8-row report separating `evidence_integrity` from `product_outcome`
per contract §3.5. Non-zero exit if ANY row is not GREEN/GREEN.

  Usage:
    ./compare-vpn-matrix.py --evidence-root <path>
    ./compare-vpn-matrix.py                            # defaults to ./evidence
"""

from __future__ import annotations

import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import verify_evidence_wss3 as ve3  # noqa: E402


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument("--evidence-root", default=os.path.join(HERE, "evidence"))
    ap.add_argument("--format", choices=["table", "json"], default="table")
    args = ap.parse_args(argv)

    report = ve3.verify_evidence_root(args.evidence_root)

    if args.format == "json":
        out = {
            "retry_lineage_present": report.retry_lineage_present,
            "rows": [
                {
                    "profile_id": r.profile_id,
                    "evidence_integrity": "GREEN" if r.integrity_ok else "RED",
                    "product_outcome": r.product_outcome,
                    "cells_ran": r.cells_ran,
                    "cells_expected_to_run": r.cells_expected_to_run,
                    "abort_reason": r.abort_reason,
                    "attempts_seen": r.attempts_seen,
                    "latest_attempt_id": r.latest_attempt_id,
                    "issues": r.integrity_issues,
                }
                for r in report.rows
            ],
        }
        print(json.dumps(out, indent=2, sort_keys=True))
    else:
        print(f"WSS-3 Carrier x VPN Matrix — 8-row report")
        print(f"evidence-root: {args.evidence_root}")
        print(f"retry_lineage_present: {report.retry_lineage_present}")
        print()
        # Fixed-width table so operators can eyeball at a glance.
        header = ("profile_id", "evidence_integrity", "product_outcome", "cells", "attempts", "notes")
        widths = [max(len(row[i]) for row in ([header] + [
            (
                r.profile_id,
                "GREEN" if r.integrity_ok else "RED",
                r.product_outcome,
                f"{r.cells_ran}/{r.cells_expected_to_run}",
                str(r.attempts_seen),
                (r.abort_reason or (r.integrity_issues[0] if r.integrity_issues else "")),
            )
            for r in report.rows
        ])) for i in range(len(header))]
        fmt = " | ".join(f"{{:<{w}}}" for w in widths)
        print(fmt.format(*header))
        print("-+-".join("-" * w for w in widths))
        for r in report.rows:
            note = r.abort_reason or (r.integrity_issues[0] if r.integrity_issues else "")
            print(fmt.format(
                r.profile_id,
                "GREEN" if r.integrity_ok else "RED",
                r.product_outcome,
                f"{r.cells_ran}/{r.cells_expected_to_run}",
                str(r.attempts_seen),
                note,
            ))
        if report.retry_lineage_present:
            print()
            print("(retry_lineage_present: true — at least one profile has a supersedes chain)")

    # Non-zero exit if not every row is GREEN/GREEN.
    all_green = all(
        r.integrity_ok and r.product_outcome == "GREEN" for r in report.rows
    )
    if all_green:
        # Implicit runtime-state cleanup per §3.6 invariant 8 —
        # a successful all-GREEN compare removes .runtime/wss3/ in its
        # entirety (audit ROUND-9 follow-up).
        rt_root = os.environ.get("WSS3_RUNTIME_ROOT")
        if not rt_root:
            pkg_root = os.environ.get("WSS3_OPERATOR_PACKAGE_ROOT", HERE)
            rt_root = os.path.join(pkg_root, ".runtime", "wss3")
        if os.path.isdir(rt_root):
            import shutil
            try:
                shutil.rmtree(rt_root)
                print(f"\n(implicit-cleanup: removed {rt_root} — track fully GREEN)")
            except OSError as e:
                print(f"\n(implicit-cleanup FAILED: {e})", file=sys.stderr)
    return 0 if all_green else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
