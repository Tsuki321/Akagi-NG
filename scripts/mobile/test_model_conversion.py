"""Exercise real single-checkpoint exports and create Android import regression fixtures in CI."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

import torch

from export_models import ROOT


def convert(players: int, checkpoint: Path, native_cli: Path, output: Path) -> None:
    subprocess.run([
        sys.executable, str(ROOT / "scripts/mobile/convert_checkpoint.py"),
        "--players", str(players), "--checkpoint", str(checkpoint),
        "--name", f"Replacement {players}p", "--native-cli", str(native_cli), "--output", str(output),
    ], check=True)


def main() -> None:
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise SystemExit("Run model conversion tests in GitHub Actions.")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native-cli", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "android/app/src/debug/assets/model-import")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    work = ROOT / "artifacts/model-import-tests"
    work.mkdir(parents=True, exist_ok=True)
    for players, filename in ((4, "mortal.pth"), (3, "mortal3p.pth")):
        # A real checkpoint with a shifted value head proves the replacement is
        # actually used. Its legal scores differ by 1, while decisions stay stable.
        state = torch.load(ROOT / "models" / filename, map_location="cpu", weights_only=True)
        state["current_dqn"]["net.bias"][0] += 1.0
        checkpoint = work / f"replacement-{players}p.pth"
        torch.save(state, checkpoint)
        result = work / f"{players}p"
        convert(players, checkpoint, args.native_cli, result)
        bundle = result / f"Mortal-{players}p.akagimodel"
        with zipfile.ZipFile(bundle) as archive:
            manifest = json.loads(archive.read("manifest.json"))
            assert manifest["players"] == players and manifest["native_compatible"]
            assert manifest["format"] == "akagi-mortal-model"
            assert archive.namelist() == ["model.onnx", "manifest.json", "reference.json"]
        (args.output / f"replacement-{players}p.akagimodel").write_bytes(bundle.read_bytes())
        # Neither CLI nor exporter may accept a checkpoint for the other mode.
        wrong_mode = subprocess.run([
            sys.executable, str(ROOT / "scripts/mobile/convert_checkpoint.py"),
            "--players", str(7 - players), "--checkpoint", str(checkpoint),
            "--name", "Wrong mode", "--native-cli", str(args.native_cli), "--output", str(work / f"wrong-{players}p"),
        ], capture_output=True, text=True)
        assert wrong_mode.returncode != 0 and "size mismatch" in wrong_mode.stderr, wrong_mode.stderr
    print("PASS: independent 4p and 3p checkpoint conversion and wrong-mode rejection.")


if __name__ == "__main__":
    main()
