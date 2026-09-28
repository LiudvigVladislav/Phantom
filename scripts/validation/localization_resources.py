#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
"""Check resource coverage, printf contracts and Russian plural branches."""

import argparse
import copy
from pathlib import Path
import re
import xml.etree.ElementTree as ET


FORMAT = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(<]*\d*(?:\.\d+)?([tT][a-zA-Z]|[a-zA-Z%])")


def arguments(text):
    result = []
    implicit = 0
    previous = None
    for match in FORMAT.finditer(text):
        index, kind = match.groups()
        if kind in ("%", "n"):
            continue
        if "<" in match.group():
            if previous is None:
                raise ValueError("format reuses a missing argument")
            position = previous
        elif index:
            position = int(index)
        else:
            implicit += 1
            position = implicit
        previous = position
        result.append((position, kind))
    return sorted(result)


def inventory(root):
    entries = {}
    for element in root:
        if element.tag not in ("string", "plurals", "string-array"):
            continue
        key = element.attrib["name"]
        if key in entries:
            raise ValueError(f"duplicate resource: {key}")
        entries[key] = element
    return entries


def validate(english, russian):
    all_source = inventory(english)
    source = {key: value for key, value in all_source.items()
              if value.get("translatable") != "false"}
    translated = inventory(russian)
    if source.keys() != translated.keys():
        raise ValueError(f"missing={sorted(source.keys() - translated.keys())}, "
                         f"extra={sorted(translated.keys() - source.keys())}")
    for key, base in source.items():
        target = translated[key]
        if base.tag != target.tag:
            raise ValueError(f"{key}: resource type mismatch")
        pairs = [(base, target)]
        if base.tag == "plurals":
            quantities = [item.get("quantity") for item in target]
            if len(quantities) != len(set(quantities)) or set(quantities) != {"one", "few", "many", "other"}:
                raise ValueError(f"{key}: incomplete/duplicate Russian plural branches")
            baseline = next(item for item in base if item.get("quantity") == "other")
            pairs = [(baseline, item) for item in target]
        elif base.tag == "string-array":
            if len(base) != len(target):
                raise ValueError(f"{key}: array size mismatch")
            pairs = list(zip(base, target))
        for before, after in pairs:
            text = "".join(after.itertext())
            if not text.strip():
                raise ValueError(f"{key}: empty translation")
            if arguments("".join(before.itertext())) != arguments(text):
                raise ValueError(f"{key}: format argument mismatch")
    return len(source)


def self_test(english, russian):
    validate(english, russian)
    mutations = []
    missing = copy.deepcopy(russian)
    missing.remove(missing[0])
    mutations.append(missing)
    duplicate = copy.deepcopy(russian)
    duplicate.append(copy.deepcopy(duplicate[0]))
    mutations.append(duplicate)
    empty = copy.deepcopy(russian)
    next(item for item in empty if item.tag == "string").text = ""
    mutations.append(empty)
    plural = copy.deepcopy(russian)
    branch = next(item for item in plural if item.tag == "plurals")
    branch.remove(next(item for item in branch if item.get("quantity") == "few"))
    mutations.append(plural)
    for replacement in ("%1$s", "%2$d"):
        wrong_format = copy.deepcopy(russian)
        item = next(item for item in wrong_format if item.tag == "string" and "%1$d" in (item.text or ""))
        item.text = item.text.replace("%1$d", replacement)
        mutations.append(wrong_format)
    extra = copy.deepcopy(russian)
    ET.SubElement(extra, "string", name="unexpected_translation").text = "extra"
    mutations.append(extra)
    for index, mutation in enumerate(mutations, 1):
        validate(english, russian)
        try:
            validate(english, mutation)
        except ValueError:
            continue
        raise AssertionError(f"negative control {index} escaped")
    print(f"NEGATIVE_CONTROLS_OK {len(mutations)}/{len(mutations)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--res", type=Path, default=Path(__file__).resolve().parents[2] / "apps/android/src/androidMain/res")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    en = ET.parse(args.res / "values/strings.xml").getroot()
    ru = ET.parse(args.res / "values-ru/strings.xml").getroot()
    print(f"LOCALIZATION_RESOURCES_OK {validate(en, ru)} entries")
    if args.self_test:
        self_test(en, ru)
