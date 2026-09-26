"""Export bundled Mortal v4 checkpoints and validate real desktop/native parity.

Run in GitHub Actions only. No model, observation, or score here is mocked.
The exact desktop network.py is loaded without starting the desktop application.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any

import numpy as np
import onnx
import onnxruntime as ort
import torch

ROOT = Path(__file__).resolve().parents[2]
SOURCE_REVISION = "e11e17452cc49f2a3cd8e26286130bb4448d3285"
SANMA_SOURCE_REVISION = "8d149e3bbbc380b5b5f1c1d60f51f2d029812414"
CHECKPOINTS = {
    4: ("mortal.pth", 1012, 46, "e94dc90bc3aaf412b0270d670660d7fe55c9d33b14419f97caf42fe77c01456f"),
    3: ("mortal3p.pth", 775, 44, "7b77cab4cd9782f48b0a8538b264840e5f5d20f9a8469914cdb52b7d0912f384"),
}
ATOL = 3e-4
RTOL = 2e-4


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_network() -> Any:
    sys.path.insert(0, str(ROOT / "akagi_backend"))
    spec = importlib.util.spec_from_file_location(
        "mobile_desktop_network", ROOT / "akagi_backend/akagi_ng/mjai_bot/network.py"
    )
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def desktop_library(players: int) -> Any:
    if sys.version_info[:2] != (3, 12) or sys.platform != "linux":
        raise RuntimeError("Parity requires the shipped Linux x86_64 CPython 3.12 library")
    name = "libriichi" if players == 4 else "libriichi3p"
    binary = ROOT / "lib" / f"{name}-3.12-x86_64-unknown-linux-gnu.so"
    spec = importlib.util.spec_from_file_location(name, binary)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def starting_events(hand: list[str], *, players: int = 4, dealer: int = 0) -> list[dict[str, Any]]:
    assert len(hand) == 13
    scores = [25000] * 4 if players == 4 else [35000, 35000, 35000, 0]
    return [
        {"type": "start_game", "id": 0, "players": players, "is_3p": players == 3},
        {"type": "start_kyoku", "bakaze": "E", "dora_marker": "9s", "kyoku": 1,
         "honba": 0, "kyotaku": 0, "oya": dealer, "scores": scores,
         "tehais": [hand] + [["?"] * 13 for _ in range(3)]},
    ]


def draw(tile: str, actor: int = 0) -> dict[str, Any]:
    return {"type": "tsumo", "actor": actor, "pai": tile}


def discard(tile: str, actor: int = 0, tsumogiri: bool = True) -> dict[str, Any]:
    return {"type": "dahai", "actor": actor, "pai": tile, "tsumogiri": tsumogiri}


def fixture_traces(players: int) -> dict[str, list[dict[str, Any]]]:
    if players == 3:
        hand = "1m 9m 1p 2p 4p 5pr 7p 9p 1s 3s 5sr 7s N".split()
        traces = {
            "sanma_kita": starting_events(hand, players=3) + [draw("8s"), {"type": "nukidora", "actor": 0, "pai": "N"}, draw("6s")],
            "sanma_ankan": starting_events("5pr 5p 5p 1p 2p 3p 2s 3s 4s 7s 8s 9s E".split(), players=3) + [draw("5p")],
            "sanma_two_ankan": starting_events("5pr 5p 5p 5p 5sr 5s 5s 1s 2s 3s E E F".split(), players=3) + [draw("5s")],
            "sanma_reach": starting_events("1p 2p 3p 4p 5p 6p 2s 3s 4s 6s 7s 8s E".split(), players=3)
                + [draw("9p"), {"type": "reach", "actor": 0}],
            "sanma_pon_red": starting_events("5pr 5p 1m 9m 1p 2p 3p 2s 3s 4s E F F".split(), players=3, dealer=1)
                + [draw("?", 1), discard("5p", 1)],
            "sanma_daiminkan": starting_events("5pr 5p 5p 1m 9m 1p 2p 2s 3s 4s E F F".split(), players=3, dealer=1)
                + [draw("?", 1), discard("5p", 1)],
            "sanma_ron": starting_events("E E E 1p 2p 3p 4p 5p 6p 7s 8s 9s C".split(), players=3, dealer=1)
                + [draw("?", 1), discard("C", 1)],
            "sanma_tsumo": starting_events("E E E 1p 2p 3p 4p 5p 6p 7s 8s 9s C".split(), players=3) + [draw("C")],
            "sanma_abort": starting_events("1m 9m 1p 9p 1s 9s E S W N P F C".split(), players=3) + [draw("5p")],
        }
        traces["sanma_kakan"] = starting_events("5pr 5p 1p 2p 3p 2s 3s 4s 7s 8s 9s F E".split(), players=3, dealer=2) + [
            draw("?", 2), discard("5p", 2),
            {"type": "pon", "actor": 0, "target": 2, "pai": "5p", "consumed": ["5pr", "5p"]},
            discard("E", tsumogiri=False), draw("?", 1), discard("C", 1),
            draw("?", 2), discard("W", 2), draw("5p"),
        ]
        traces["sanma_nuki_riichi"] = starting_events("1m 1m 1m 1p 2p 3p 4p 5p 6p 7s 8s 9s C".split(), players=3) + [
            draw("F"), {"type": "reach", "actor": 0}, discard("F"), {"type": "reach_accepted", "actor": 0},
            draw("?", 1), discard("E", 1), draw("?", 2), discard("P", 2), draw("N"),
        ]
        traces["sanma_other_nuki"] = starting_events(hand, players=3) + [draw("8s"), discard("8s"),
            draw("?", 1), {"type": "nukidora", "actor": 1, "pai": "N"}, draw("?", 1), discard("C", 1),
            draw("?", 2), discard("F", 2), draw("6s")]
        traces["sanma_dora_1m"] = starting_events(hand, players=3) + [draw("8s")]
        traces["sanma_dora_1m"][1]["dora_marker"] = "1m"
        traces["sanma_multiple_kita_win"] = starting_events("1p 2p 3p 4p 5p 6p 2s 3s 4s 6s 7s 8s C".split(), players=3) + [
            draw("N"), {"type": "nukidora", "actor": 0, "pai": "N"},
            draw("N"), {"type": "nukidora", "actor": 0, "pai": "N"}, draw("C"),
        ]
        traces["sanma_south3"] = starting_events("F F F 1p 2p 3p 4p 5p 6p 7s 8s 9s C".split(), players=3, dealer=1) + [draw("?", 1), discard("C", 1)]
        traces["sanma_south3"][1].update(bakaze="S", kyoku=3, scores=[20000, 45000, 40000, 0])
        for label, waiting_hand in {
            "kokushi": "1m 9m 1p 9p 1s 9s E S W P F C C",
            "yakuhai": "E E E 1p 2p 3p 4p 5p 6p 7s 8s 9s N",
        }.items():
            traces[f"sanma_ron_kita_{label}"] = starting_events(waiting_hand.split(), players=3, dealer=1) + [
                draw("?", 1), {"type": "nukidora", "actor": 1, "pai": "N"},
            ]
        for seat in (1, 2):
            rotated = json.loads(json.dumps(traces["sanma_kita"]))
            rotated[0]["id"] = seat
            for event in rotated:
                for field in ("actor", "target", "oya"):
                    if field in event:
                        event[field] = (event[field] + seat) % 3
                for field in ("scores", "tehais", "deltas", "names"):
                    if field in event:
                        values = event[field]
                        event[field] = values[3 - seat:3] + values[:3 - seat] + values[3:]
            traces[f"sanma_kita_seat{seat}"] = rotated
        # The legacy v4 discard history retains four-seat padding during
        # calls, even though relative seats are three-player. Cover every
        # absolute caller/target combination to catch that ABI edge case.
        for actor in range(3):
            for target in range(3):
                if actor == target:
                    continue
                events = starting_events("5pr 5p 1p 2p 3p 2s 3s 4s 7s 8s 9s F E".split(), players=3, dealer=target)
                events[0]["id"] = actor
                events[1]["tehais"][actor], events[1]["tehais"][0] = events[1]["tehais"][0], events[1]["tehais"][actor]
                events.extend([draw("?", target), discard("5p", target),
                               {"type": "pon", "actor": actor, "target": target, "pai": "5p", "consumed": ["5pr", "5p"]}])
                traces[f"sanma_pon_actor{actor}_target{target}"] = events
        return traces
    traces = {
        "discard_red": [json.loads(line) for line in (ROOT / "native/fixtures/smoke_4p.jsonl").read_text().splitlines() if line],
        "reach_lookahead": starting_events("1m 2m 3m 4m 5m 6m 2p 3p 4p 6s 7s 8s E".split())
            + [draw("9p"), {"type": "reach", "actor": 0}],
        "chi_red": starting_events("3m 5mr 7m 8m 9m 1p 2p 3p 4s 5s 6s E E".split(), dealer=3)
            + [draw("?", 3), discard("4m", 3)],
        "pon_red": starting_events("5pr 5p 1m 3m 4m 7m 8m 2s 3s 4s E F F".split(), dealer=1)
            + [draw("?", 1), discard("5p", 1)],
        "daiminkan_red": starting_events("5pr 5p 5p 3m 4m 7m 8m 2s 3s 4s E F F".split(), dealer=1)
            + [draw("?", 1), discard("5p", 1)],
        "ankan_red": starting_events("5m 5m 5mr 1p 2p 3p 4p 5p 6p 2s 3s 4s E".split()) + [draw("5m")],
        "two_ankan": starting_events("5mr 5m 5m 5m 5pr 5p 5p 1s 2s 3s E E F".split()) + [draw("5p")],
        "ron": starting_events("E E E 1m 2m 3m 1p 2p 3p 7s 8s 9s C".split(), dealer=1)
            + [draw("?", 1), discard("C", 1)],
        "tsumo": starting_events("E E E 1m 2m 3m 1p 2p 3p 7s 8s 9s C".split()) + [draw("C")],
        "abortive_draw": starting_events("1m 9m 1p 9p 1s 9s E S W N P F C".split()) + [draw("5p")],
    }
    traces["kakan_red"] = starting_events("5pr 5p 1m 2m 3m 2s 3s 4s 7s 8s 9s F E".split(), dealer=3) + [
        draw("?", 3), discard("5p", 3),
        {"type": "pon", "actor": 0, "target": 3, "pai": "5p", "consumed": ["5pr", "5p"]},
        discard("E", tsumogiri=False), draw("?", 1), discard("C", 1),
        draw("?", 2), discard("N", 2), draw("?", 3), discard("W", 3), draw("5p"),
    ]
    return traces


class Recorder:
    name = "Android parity recorder"
    is_oracle = False
    version = 4
    enable_quick_eval = False
    enable_rule_based_agari_guard = False

    def __init__(self, forced: int | None = None) -> None:
        self.batches: list[tuple[np.ndarray, np.ndarray]] = []
        self.forced = forced

    def react_batch(self, obs: Any, masks: Any, invisible_obs: Any = None) -> tuple[Any, ...]:
        obs = np.asarray(obs, dtype=np.float32)
        masks = np.asarray(masks, dtype=np.bool_)
        self.batches.append((obs.copy(), masks.copy()))
        scores = np.where(masks, -np.arange(masks.shape[1], dtype=np.float32), -np.inf)
        actions = scores.argmax(-1)
        if self.forced is not None and masks[-1, self.forced]:
            actions[-1] = self.forced
        return actions.tolist(), scores.tolist(), masks.tolist(), [True] * len(actions)


def capture(library: Any, events: list[dict[str, Any]], forced: int | None = None) -> list[dict[str, Any]]:
    recorder = Recorder(forced)
    bot = library.mjai.Bot(recorder, events[0].get("id", 0))
    decisions = []
    for line, event in enumerate(events):
        before = len(recorder.batches)
        reaction = bot.react(json.dumps(event, separators=(",", ":")))
        if len(recorder.batches) == before:
            continue
        obs, masks = recorder.batches[-1]
        assert len(obs) in (1, 2), f"Unexpected batch layout: {obs.shape}"
        decisions.append({"line": line, "obs": obs[-1], "mask": masks[-1],
                          "kan_obs": obs[0] if len(obs) == 2 else None,
                          "kan_mask": masks[0] if len(obs) == 2 else None,
                          "reaction": json.loads(reaction)})
    return decisions


def canonical_event(event: dict[str, Any]) -> dict[str, Any]:
    return {k: v for k, v in event.items() if k not in {"meta", "can_act"} and v is not None}


def validate_native(cli: Path, trace_path: Path, events: list[dict[str, Any]], desktop: list[dict[str, Any]], library: Any, players: int) -> dict[str, Any]:
    player = events[0].get("id", 0)
    process = subprocess.run([str(cli), str(trace_path), str(player), str(players)], check=False, text=True, capture_output=True)
    trace_path.with_suffix(".native.jsonl").write_text(process.stdout, encoding="utf-8")
    trace_path.with_suffix(".native.stderr.txt").write_text(process.stderr, encoding="utf-8")
    np.savez_compressed(trace_path.with_suffix(".desktop.npz"), **{
        f"{i}_{kind}": case[kind] for i, case in enumerate(desktop) for kind in ("obs", "mask", "kan_obs", "kan_mask") if case[kind] is not None
    })
    process.check_returncode()
    native = [json.loads(line) for line in process.stdout.splitlines() if line]
    assert len(native) == len(desktop), f"Native/desktop decision count differs for {trace_path.name}"
    max_error = 0.0
    decoded_count = 0
    for actual, reference in zip(native, desktop, strict=True):
        assert actual["line"] == reference["line"]
        np.testing.assert_array_equal(actual["normal"]["mask"], reference["mask"])
        obs = np.asarray(actual["normal"]["obs"], dtype=np.float32).reshape(reference["obs"].shape)
        np.testing.assert_allclose(obs, reference["obs"], atol=1e-6, rtol=1e-6,
                                   err_msg=f"Native observation mismatch: {trace_path.name}:{actual['line']}")
        max_error = max(max_error, float(np.max(np.abs(obs - reference["obs"]))))
        assert (actual["kan"] is None) == (reference["kan_obs"] is None), "Kan batch differs"
        if actual["kan"] is not None:
            np.testing.assert_array_equal(actual["kan"]["mask"], reference["kan_mask"])
            np.testing.assert_allclose(np.asarray(actual["kan"]["obs"], dtype=np.float32).reshape(reference["kan_obs"].shape),
                                       reference["kan_obs"], atol=1e-6, rtol=1e-6)
        if actual.get("reach") is not None:
            replay = events[: reference["line"] + 1] + [{"type": "reach", "actor": player}]
            reach_reference = capture(library, replay)[-1]
            np.testing.assert_array_equal(actual["reach"]["mask"], reach_reference["mask"])
            np.testing.assert_allclose(np.asarray(actual["reach"]["obs"], dtype=np.float32).reshape(reach_reference["obs"].shape),
                                       reach_reference["obs"], atol=1e-6, rtol=1e-6,
                                       err_msg=f"Cloned riichi state differs from desktop replay: {trace_path.name}")
        for action in actual["actions"]:
            forced_decisions = capture(library, events[: reference["line"] + 1], forced=action["index"])
            expected = canonical_event(forced_decisions[-1]["reaction"])
            assert canonical_event(action["event"]) == expected, f"Action decode differs: {trace_path.name}: {action} != {expected}"
            decoded_count += 1
    return {"trace": trace_path.name, "decisions": len(native), "decoded_actions": decoded_count, "max_observation_error": max_error}


class MortalGraph(torch.nn.Module):
    def __init__(self, brain: torch.nn.Module, dqn: torch.nn.Module) -> None:
        super().__init__()
        self.brain = brain
        self.dqn = dqn

    def forward(self, obs: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
        # Call the repository's exact forward; legal-only advantage mean matters.
        return self.dqn(self.brain(obs), mask)


def export_model(network: Any, players: int, output: Path, fixtures: Path, native_cli: Path | None, report: dict[str, Any], validate_sanma: bool = False) -> list[dict[str, Any]]:
    filename, channels, actions, expected_sha = CHECKPOINTS[players]
    checkpoint = ROOT / "models" / filename
    assert sha256(checkpoint) == expected_sha, f"Unexpected checkpoint: {checkpoint}"
    state = torch.load(checkpoint, map_location="cpu", weights_only=True)
    cfg = state["config"]
    assert cfg["control"]["version"] == 4 and "policy_net" not in state
    library = desktop_library(players)
    assert tuple(library.consts.obs_shape(4)) == (channels, 34)
    assert library.consts.ACTION_SPACE == actions
    brain = network.Brain(obs_shape_func=library.consts.obs_shape, oracle_obs_shape_func=library.consts.oracle_obs_shape,
                          conv_channels=cfg["resnet"]["conv_channels"], num_blocks=cfg["resnet"]["num_blocks"], version=4).eval()
    dqn = network.DQN(action_space=actions, version=4).eval()
    brain.load_state_dict(state["mortal"], strict=True)
    dqn.load_state_dict(state["current_dqn"], strict=True)
    graph = MortalGraph(brain, dqn).eval()

    cases: list[tuple[str, dict[str, Any]]] = []
    native_results = []
    native_errors = []
    for name, events in fixture_traces(players).items():
        path = fixtures / f"{players}p_{name}.jsonl"
        path.write_text("".join(json.dumps(event, separators=(",", ":")) + "\n" for event in events), encoding="utf-8")
        captured = capture(library, events)
        assert captured, f"No real desktop decisions for {name}"
        if native_cli and (players == 4 or validate_sanma):
            try:
                native_results.append(validate_native(native_cli, path, events, captured, library, players))
            except Exception as error:
                if players == 4:
                    raise
                # Preserve every native/reference pair for diagnosis in one CI
                # run. This still fails the job and never approves compatibility.
                native_errors.append({"trace": path.name, "error": str(error)})
                print(f"Native parity failed for {path.name}: {error}", flush=True)
        for i, decision in enumerate(captured):
            cases.append((f"{players}p_{name}_{i}", decision))
            if decision["kan_obs"] is not None:
                cases.append((f"{players}p_{name}_{i}_kan", {"obs": decision["kan_obs"], "mask": decision["kan_mask"]}))

    path = output / f"mortal{players}p.onnx"
    sample = cases[0][1]
    with torch.inference_mode():
        torch.onnx.export(graph, (torch.from_numpy(sample["obs"][None]), torch.from_numpy(sample["mask"][None])),
                          str(path), input_names=["obs", "mask"], output_names=["q_values"],
                          opset_version=17, dynamo=False, do_constant_folding=True)
    onnx.checker.check_model(str(path))
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    runtime = ort.InferenceSession(str(path), sess_options=options, providers=["CPUExecutionProvider"])
    reference_assets = []
    numerical = []
    for name, case in cases:
        obs = np.asarray(case["obs"][None], dtype=np.float32)
        mask = np.asarray(case["mask"][None], dtype=np.bool_)
        with torch.inference_mode():
            expected = graph(torch.from_numpy(obs), torch.from_numpy(mask)).numpy()
        actual = runtime.run(["q_values"], {"obs": obs, "mask": mask})[0]
        assert np.isneginf(actual[~mask]).all() and np.isneginf(expected[~mask]).all()
        np.testing.assert_allclose(actual[mask], expected[mask], atol=ATOL, rtol=RTOL, err_msg=name)
        assert int(actual.argmax(-1)[0]) == int(expected.argmax(-1)[0]), f"Argmax changed for {name}"
        numerical.append({"case": name, "max_q_error": float(np.max(np.abs(actual[mask] - expected[mask]))),
                          "argmax": int(expected.argmax(-1)[0])})
        # Keep every real case in the APK: this validates the Android runtime and
        # both players' distinct model graphs, including kan selection inputs.
        asset_path = output / "reference" / f"{name}.f32"
        asset_path.parent.mkdir(parents=True, exist_ok=True)
        asset_path.write_bytes(obs.astype("<f4").tobytes())
        bits = sum(1 << i for i, legal in enumerate(mask[0]) if legal)
        reference_assets.append({"name": name, "players": players, "observation": f"reference/{name}.f32",
                                 "mask_bits": bits, "q_values": [float(q) if legal else None for q, legal in zip(expected[0], mask[0], strict=True)],
                                 "argmax": int(expected.argmax(-1)[0])})
    description = {
        "file": path.name, "sha256": sha256(path), "checkpoint": filename,
        "checkpoint_sha256": expected_sha, "version": 4, "players": players,
        "observation_shape": [1, channels, 34], "mask_shape": [1, actions],
        "score_semantics": "legal_masked_dueling_q", "inference": "deterministic_fp32_cpu",
        "native_compatible": native_cli is not None and (players == 4 or validate_sanma) and not native_errors,
        "native_source": SOURCE_REVISION if players == 4 else SANMA_SOURCE_REVISION,
        "license": "models/LICENSE",
    }
    (output / f"mortal{players}p.json").write_text(json.dumps(description, indent=2) + "\n", encoding="utf-8")
    report[f"{players}p"] = {"model": description, "numerical_parity": numerical,
                             "native_parity": native_results, "native_errors": native_errors}
    if native_errors:
        raise RuntimeError(f"{len(native_errors)} sanma traces failed native compatibility; see preserved diagnostics")
    return reference_assets


def main() -> None:
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise SystemExit("Model export and validation must run in GitHub Actions (no-local-compilation skill).")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "android/app/src/main/assets/models")
    parser.add_argument("--fixtures", type=Path, default=ROOT / "native/fixtures/generated")
    parser.add_argument("--native-cli", type=Path, help="Built mortal-fixtures binary; required to approve 4p compatibility")
    parser.add_argument("--validate-sanma", action="store_true", help="Also require native sanma compatibility before approving it")
    parser.add_argument("--report", type=Path, default=ROOT / "artifacts/mobile-model-parity.json")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    args.fixtures.mkdir(parents=True, exist_ok=True)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    torch.set_num_threads(2)
    torch.set_num_interop_threads(1)
    torch.manual_seed(0)
    report: dict[str, Any] = {"passed": False, "torch": torch.__version__, "onnxruntime": ort.__version__,
                             "atol": ATOL, "rtol": RTOL, "native_source": SOURCE_REVISION}
    try:
        network = load_network()
        cases = []
        for players in (4, 3):
            cases.extend(export_model(network, players, args.output, args.fixtures,
                                      args.native_cli.resolve() if args.native_cli else None, report, args.validate_sanma))
        (args.output / "reference.json").write_text(json.dumps({"cases": cases}, indent=2) + "\n", encoding="utf-8")
        shutil.copyfile(ROOT / "native/fixtures/smoke_4p.jsonl", args.output / "smoke_4p.jsonl")
        shutil.copyfile(ROOT / "native/fixtures/smoke_3p.jsonl", args.output / "smoke_3p.jsonl")
        shutil.copyfile(ROOT / "models/LICENSE", args.output / "LICENSE")
        report["passed"] = True
    except BaseException as error:
        report["error"] = str(error)
        raise
    finally:
        args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"Validated and exported both bundled checkpoints to {args.output}")


if __name__ == "__main__":
    main()
