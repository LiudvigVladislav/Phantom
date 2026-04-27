#!/usr/bin/env python3
"""
Prepend SPDX license headers to .kt and .rs source files in the repo.

Skips files that already carry an `SPDX-License-Identifier` line so the
script is idempotent — safe to re-run after adding new files.

Usage:
    python scripts/add-spdx-headers.py

The header format follows the Linux Kernel / FSF SPDX convention:

    // SPDX-License-Identifier: AGPL-3.0-or-later
    // Copyright (c) 2026 Willen LLC

For Kotlin, the header goes above the `package` declaration with a
blank line between. For Rust, it goes at the top of the file.
"""

from __future__ import annotations

from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

HEADER_LINES = (
    "// SPDX-License-Identifier: AGPL-3.0-or-later",
    "// Copyright (c) 2026 Willen LLC",
)

# Roots to walk. Build outputs and tooling caches are excluded.
SOURCE_ROOTS = ("apps", "shared", "services")
EXCLUDE_DIR_PARTS = {"build", ".gradle", ".kotlin", "target", "node_modules"}


def is_excluded(path: Path) -> bool:
    return any(part in EXCLUDE_DIR_PARTS for part in path.parts)


def needs_header(text: str) -> bool:
    return "SPDX-License-Identifier" not in text.splitlines()[0:5][0:5]  # check first 5 lines


def has_spdx_in_first_lines(text: str, lookahead: int = 5) -> bool:
    for line in text.splitlines()[:lookahead]:
        if "SPDX-License-Identifier" in line:
            return True
    return False


def prepend_header(path: Path) -> bool:
    """Return True if the file was modified."""
    text = path.read_text(encoding="utf-8")
    if has_spdx_in_first_lines(text):
        return False

    header = "\n".join(HEADER_LINES) + "\n\n"
    new_text = header + text
    path.write_text(new_text, encoding="utf-8")
    return True


def main() -> None:
    targets: list[Path] = []
    for root in SOURCE_ROOTS:
        root_path = REPO_ROOT / root
        if not root_path.exists():
            continue
        for path in root_path.rglob("*"):
            if not path.is_file():
                continue
            if path.suffix not in (".kt", ".rs"):
                continue
            if is_excluded(path):
                continue
            targets.append(path)

    modified = 0
    skipped = 0
    for path in sorted(targets):
        if prepend_header(path):
            modified += 1
        else:
            skipped += 1

    print(f"Modified: {modified}")
    print(f"Skipped (already had SPDX): {skipped}")
    print(f"Total scanned: {len(targets)}")


if __name__ == "__main__":
    main()
