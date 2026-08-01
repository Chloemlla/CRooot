#!/usr/bin/env python3
"""Refresh Google attestation roots stored in res/raw."""

from __future__ import annotations

import json
import pathlib
import sys
import urllib.request

ROOT_URL = "https://android.googleapis.com/attestation/root"
RAW_DIR = pathlib.Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res" / "raw"
OUTPUT = RAW_DIR / "google_attestation_roots.json"


def parse_roots(payload: str) -> list[str]:
    roots = json.loads(payload)
    if not isinstance(roots, list) or not roots:
        raise SystemExit("Google attestation root endpoint returned an empty or invalid payload.")

    normalized: list[str] = []
    for index, pem in enumerate(roots):
        if not isinstance(pem, str):
            raise SystemExit(f"Root entry {index} is not a PEM string.")
        if "-----BEGIN CERTIFICATE-----" not in pem or "-----END CERTIFICATE-----" not in pem:
            raise SystemExit(f"Root entry {index} does not look like a PEM certificate.")
        normalized.append(pem)
    return normalized


def main() -> int:
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(ROOT_URL, timeout=10) as response:
        payload = response.read().decode("utf-8")

    roots = parse_roots(payload)
    OUTPUT.write_text(json.dumps(roots, indent=2) + "\n", encoding="utf-8")

    print(f"Updated {OUTPUT} with {len(roots)} root certificate(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
