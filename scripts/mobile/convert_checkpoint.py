"""Convert one Mortal .pth checkpoint into a validated Android import bundle in CI."""

from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import socket
import urllib.parse
import urllib.request
import zipfile

import torch

from export_models import ATOL, RTOL, export_model, load_network, sha256

MAX_CHECKPOINT_BYTES = 1024 * 1024 * 1024
MAX_MODEL_BYTES = 512 * 1024 * 1024


def validate_download_url(url: str) -> None:
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.port not in (None, 443):
        raise ValueError("Use a direct HTTPS checkpoint URL without embedded credentials")
    addresses = socket.getaddrinfo(parsed.hostname, 443, type=socket.SOCK_STREAM)
    if not addresses or any(not ipaddress.ip_address(address[4][0]).is_global for address in addresses):
        raise ValueError("The checkpoint URL must resolve to a public HTTPS host")


class PublicHttpsRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        validate_download_url(newurl)
        return super().redirect_request(request, fp, code, msg, headers, newurl)


def download_checkpoint(url: str, destination: Path) -> None:
    validate_download_url(url)
    opener = urllib.request.build_opener(PublicHttpsRedirect())
    request = urllib.request.Request(url, headers={"User-Agent": "Akagi-Android-Model-Converter"})
    try:
        with opener.open(request, timeout=60) as response, destination.open("wb") as output:
            advertised = response.headers.get("Content-Length")
            if advertised and int(advertised) > MAX_CHECKPOINT_BYTES:
                raise ValueError("Checkpoint exceeds the 1 GiB conversion limit")
            total = 0
            while chunk := response.read(1024 * 1024):
                total += len(chunk)
                if total > MAX_CHECKPOINT_BYTES:
                    raise ValueError("Checkpoint exceeds the 1 GiB conversion limit")
                output.write(chunk)
            if not total:
                raise ValueError("The checkpoint download was empty")
    except Exception:
        # URLs can contain temporary download tokens; do not echo them into logs.
        raise RuntimeError("Checkpoint download failed; check the HTTPS download URL and its expiry") from None


def make_bundle(players: int, name: str, output: Path, cases: list[dict], report: dict, destination: Path) -> None:
    description = dict(report[f"{players}p"]["model"])
    graph = output / description["file"]
    if not description["native_compatible"] or not 0 < graph.stat().st_size <= MAX_MODEL_BYTES:
        raise ValueError("The model must pass native parity and fit within the 512 MiB Android limit")
    reference = (json.dumps({"cases": cases}, indent=2, allow_nan=False) + "\n").encode()
    description.update(
        format="akagi-mortal-model", format_version=1, name=name, file="model.onnx",
        encoder="akagi-mortal-4p-v4" if players == 4 else "akagi-mortal-3p-v4-legacy",
        reference_sha256=hashlib.sha256(reference).hexdigest(),
    )
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.write(graph, "model.onnx")
        archive.writestr("manifest.json", json.dumps(description, indent=2, allow_nan=False) + "\n")
        archive.writestr("reference.json", reference)


def main() -> None:
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise SystemExit("Model conversion runs in GitHub Actions (no-local-compilation skill).")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--players", type=int, choices=(4, 3), required=True)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--checkpoint", type=Path)
    source.add_argument("--checkpoint-url-env", help="Environment variable containing a public HTTPS checkpoint URL")
    parser.add_argument("--sha256", default="", help="Optional expected source checkpoint checksum")
    parser.add_argument("--name", required=True)
    parser.add_argument("--native-cli", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    name = args.name.strip()
    if not name or len(name) > 100 or any(ord(character) < 32 or ord(character) == 127 for character in name):
        parser.error("Choose a model name between 1 and 100 visible characters")
    if args.sha256 and not re.fullmatch(r"[0-9a-fA-F]{64}", args.sha256):
        parser.error("The checkpoint SHA-256 must have 64 hexadecimal characters")
    args.output.mkdir(parents=True, exist_ok=True)
    workspace = args.output / "validation"
    workspace.mkdir(exist_ok=True)
    checkpoint = args.checkpoint
    if args.checkpoint_url_env:
        checkpoint = workspace / "replacement.pth"
        download_checkpoint(os.environ[args.checkpoint_url_env], checkpoint)
    assert checkpoint is not None
    if not checkpoint.is_file() or not 0 < checkpoint.stat().st_size <= MAX_CHECKPOINT_BYTES:
        raise ValueError("Checkpoint is missing or exceeds 1 GiB")
    checksum = sha256(checkpoint)
    if args.sha256 and checksum != args.sha256.lower():
        raise ValueError("Checkpoint SHA-256 does not match; no model was converted")
    output = workspace / "model"
    fixtures = workspace / "fixtures"
    output.mkdir(exist_ok=True)
    fixtures.mkdir(exist_ok=True)
    torch.set_num_threads(2)
    torch.set_num_interop_threads(1)
    torch.manual_seed(0)
    report = {"passed": False, "players": args.players, "name": name, "checkpoint_sha256": checksum, "atol": ATOL, "rtol": RTOL}
    try:
        cases = export_model(load_network(), args.players, output, fixtures, args.native_cli.resolve(), report,
                             validate_sanma=args.players == 3, checkpoint_path=checkpoint, checkpoint_sha256=checksum)
        bundle = args.output / f"Mortal-{args.players}p.akagimodel"
        make_bundle(args.players, name, output, cases, report, bundle)
        (args.output / "SHA256SUMS.txt").write_text(f"{sha256(bundle)}  {bundle.name}\n", encoding="utf-8")
        report["passed"] = True
        print(f"PASS: {args.players}-player checkpoint converted; {len(cases)} reference observations validated.")
    finally:
        (args.output / "conversion-report.json").write_text(json.dumps(report, indent=2, allow_nan=False) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
