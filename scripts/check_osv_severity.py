#!/usr/bin/env python3
"""Fail on high/critical OSV findings or missing/malformed scan evidence."""

import argparse
import json
import math
from pathlib import Path


def summarize(path: Path) -> dict:
    document = json.loads(path.read_text(encoding="utf-8-sig"))
    if document.get("version") != "2.1.0" or not document.get("runs"):
        raise ValueError(f"Missing SARIF runs: {path}")
    summary = {"file": str(path), "findings": 0, "unscored": 0, "high_critical": []}
    for run in document["runs"]:
        rules = {rule["id"]: rule for rule in run["tool"]["driver"]["rules"]}
        for result in run["results"]:
            rule = rules[result["ruleId"]]
            summary["findings"] += 1
            severity = rule.get("properties", {}).get("security-severity")
            if severity is None:
                summary["unscored"] += 1
                continue
            score = float(severity)
            if not math.isfinite(score) or not 0 <= score <= 10:
                raise ValueError(f"Invalid security severity for {result['ruleId']}")
            if score >= 7:
                summary["high_critical"].append({"id": result["ruleId"], "score": score,
                                                "message": result["message"]["text"]})
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sarif", type=Path, nargs="+")
    args = parser.parse_args()
    summaries = [summarize(path) for path in args.sarif]
    print(json.dumps(summaries, indent=2))
    return int(any(summary["high_critical"] for summary in summaries))


if __name__ == "__main__":
    raise SystemExit(main())
