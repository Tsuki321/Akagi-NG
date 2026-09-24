"""Generate Android wire/event parity fixtures using the real desktop protocol code.

Run in GitHub Actions before :app:testDebugUnitTest, with Python >= 3.12 and
protobuf, numpy, aiohttp, loguru installed. This is deterministic data generation;
no model, proxy, network session, account credentials, or mocked parser is used.

The bridge package eagerly imports all other game drivers. Load that package as
a namespace here to avoid requiring the unrelated mitmproxy driver. The actual
LiqiProto, MajsoulBridge, BaseBridge, and event dataclasses are unmodified imports.
"""

from __future__ import annotations

import base64
import dataclasses
import hashlib
import json
import shutil
import struct
import sys
import types
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "akagi_backend"))
package = types.ModuleType("akagi_ng.bridge")
package.__path__ = [str(ROOT / "akagi_backend" / "akagi_ng" / "bridge")]
sys.modules[package.__name__] = package

from google.protobuf.json_format import ParseDict  # noqa: E402

from akagi_ng.bridge.majsoul.bridge import MajsoulBridge  # noqa: E402
from akagi_ng.bridge.majsoul.liqi import LiqiProto, decode  # noqa: E402

SCHEMA = LiqiProto()
DEFINITIONS = SCHEMA.jsonProto["nested"]["lq"]["nested"]


def message(name: str, fields: dict) -> bytes:
    cls = SCHEMA.get_message_class(name)
    assert cls is not None, name
    return ParseDict(fields, cls()).SerializeToString()


def varint(value: int) -> bytes:
    result = bytearray()
    while value > 127:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def wrapper(name: str, payload: bytes) -> bytes:
    # Keep explicit empty name/data fields: this is the game's actual wire
    # representation and is required by the reference's two-block parser.
    encoded = name.encode()
    return b"\x0a" + varint(len(encoded)) + encoded + b"\x12" + varint(len(payload)) + payload


def operation(seat: int, kind: int = 1) -> dict:
    return {"seat": seat, "operationList": [{"type": kind}]}


def new_round(tiles: list[str], players: int = 4, dealer: int = 0, **extra) -> dict:
    return {"chang": 0, "ju": dealer, "ben": 0, "tiles": tiles, "liqibang": 0,
            "scores": [35000 if players == 3 else 25000] * players, "doras": ["9p"], **extra}


def action_record(name: str, step: int, data: dict) -> dict:
    return {"name": name, "step": step, "data": base64.b64encode(message(name, data)).decode()}


class Trace:
    def __init__(self, name: str, players: int = 4, seat: int = 0, interleave: bool = False):
        self.name = name
        self.players = players
        self.seat = seat
        self.sequence = 0
        self.next_step = 0
        self.bridge = MajsoulBridge()
        self.parsers: dict[str, LiqiProto] = {}
        self.captures: list[dict] = []
        self.expected: list[dict] = []
        self.conn = "game"
        self.add("capture_ready")
        self.open("game")
        if interleave:
            self.open("lobby")
            self.rpc(".lq.Route.heartbeat", {}, 7, "lobby")
        self.rpc(".lq.FastTest.authGame", {"accountId": 12345, "gameUuid": f"fixture-{name}"}, 7)
        if interleave:
            self.rpc(".lq.Route.heartbeat", {}, 7, "lobby", response=True)
        seats = [20001 + index for index in range(players)]
        seats[seat] = 12345
        self.rpc(".lq.FastTest.authGame", {"seatList": seats, "gameConfig": {"meta": {"modeId": 1}}},
                 7, response=True)

    def add(self, kind: str, **fields) -> dict:
        self.sequence += 1
        capture = {"type": kind, "generation": f"document-{self.name}", "mainFrame": True,
                   "sequence": self.sequence, **fields}
        self.captures.append(capture)
        return capture

    def open(self, conn: str):
        self.parsers[conn] = LiqiProto()
        self.add("websocket_created", connectionId=conn, url=f"wss://fixture.invalid/{conn}")

    def close(self):
        self.add("websocket_closed", connectionId=self.conn, url=f"wss://fixture.invalid/{self.conn}")

    def frame(self, wire: bytes, outbound: bool, conn: str | None = None, **metadata):
        conn = conn or self.conn
        self.add("websocket", connectionId=conn, url=f"wss://fixture.invalid/{conn}",
                 direction="outbound" if outbound else "inbound", binary=True,
                 data=base64.b64encode(wire).decode(), **metadata)
        parsed = self.parsers[conn].parse(wire)
        assert parsed, f"Desktop Liqi parser rejected {self.name}: {metadata}"
        events = self.bridge.parse_liqi(parsed)
        for event in events:
            if event.type == "system_event":
                assert str(event.code) == "game_syncing", event
                continue
            self.expected.append({key: value for key, value in dataclasses.asdict(event).items() if value is not None})

    def rpc(self, name: str, fields: dict, request_id: int, conn: str | None = None, response: bool = False):
        _, _, service, method_name = name.split(".")
        method = DEFINITIONS[service]["methods"][method_name]
        payload = message(method["responseType" if response else "requestType"], fields)
        wire = bytes([3 if response else 2]) + struct.pack("<H", request_id) + wrapper("" if response else name, payload)
        self.frame(wire, not response, conn, fixtureMethod=name)

    def action(self, name: str, data: dict):
        record = action_record(name, self.next_step, data)
        record["data"] = base64.b64encode(decode(base64.b64decode(record["data"]))).decode()
        wire = b"\x01" + wrapper(".lq.ActionPrototype", message("ActionPrototype", record))
        self.frame(wire, False, fixtureAction=name, fixtureStep=self.next_step)
        self.next_step += 1

    def notify(self, name: str, data: dict):
        self.frame(b"\x01" + wrapper(f".lq.{name}", message(name, data)), False, fixtureMethod=f".lq.{name}")

    def export(self) -> dict:
        return {"name": self.name, "players": self.players, "seat": self.seat,
                "captures": self.captures, "expected": self.expected}


def make_traces() -> list[dict]:
    traces = []

    trace = Trace("four_player_red_reach", seat=2, interleave=True)
    trace.action("ActionMJStart", {})
    trace.action("ActionNewRound", new_round(
        ["1m", "2m", "3m", "4m", "0m", "6m", "2p", "3p", "4p", "5s", "6s", "7s", "5z"]))
    trace.action("ActionDiscardTile", {"seat": 0, "tile": "1z", "moqie": False, "isLiqi": False})
    trace.action("ActionDealTile", {"seat": 1, "tile": ""})
    trace.action("ActionDiscardTile", {"seat": 1, "tile": "4z", "moqie": True, "isLiqi": True})
    trace.action("ActionDealTile", {"seat": 2, "tile": "0p", "operation": operation(2)})
    traces.append(trace.export())

    trace = Trace("pon_red_kakan", seat=1)
    trace.action("ActionMJStart", {})
    trace.action("ActionNewRound", new_round(
        ["0p", "5p", "1m", "2m", "3m", "4m", "6m", "7m", "8m", "1s", "2s", "3s", "9s"]))
    trace.action("ActionDiscardTile", {"seat": 0, "tile": "5p", "moqie": False, "operation": operation(1, 3)})
    trace.action("ActionChiPengGang", {"seat": 1, "type": 1, "tiles": ["0p", "5p", "5p"],
                                      "froms": [1, 1, 0], "operation": operation(1)})
    trace.action("ActionDiscardTile", {"seat": 1, "tile": "9s", "moqie": False})
    trace.action("ActionDealTile", {"seat": 2, "tile": ""})
    trace.action("ActionDiscardTile", {"seat": 2, "tile": "1z", "moqie": True})
    trace.action("ActionDealTile", {"seat": 1, "tile": "5p", "operation": operation(1)})
    trace.action("ActionAnGangAddGang", {"seat": 1, "type": 2, "tiles": "5p"})
    trace.action("ActionDealTile", {"seat": 1, "tile": "2p", "doras": ["9p", "2s"], "operation": operation(1)})
    trace.action("ActionDiscardTile", {"seat": 1, "tile": "2p", "moqie": True})
    traces.append(trace.export())

    trace = Trace("concealed_kan")
    trace.action("ActionMJStart", {})
    trace.action("ActionNewRound", new_round(
        ["0m", "5m", "5m", "5m", "1p", "2p", "3p", "1s", "2s", "3s", "7s", "8s", "9s", "5z"],
        operation=operation(0)))
    trace.action("ActionAnGangAddGang", {"seat": 0, "type": 3, "tiles": "5m"})
    trace.action("ActionDealTile", {"seat": 0, "tile": "5z", "doras": ["9p", "3z"], "operation": operation(0)})
    trace.action("ActionDiscardTile", {"seat": 0, "tile": "5z", "moqie": True})
    trace.action("ActionHule", {})
    trace.notify("NotifyGameTerminate", {})
    traces.append(trace.export())

    trace = Trace("three_player_repeated_kita", players=3)
    trace.action("ActionMJStart", {})
    trace.action("ActionNewRound", new_round(
        ["1m", "9m", "1p", "2p", "3p", "0p", "6p", "7p", "1s", "2s", "3s", "1z", "2z", "4z"],
        players=3, operation=operation(0)))
    trace.action("ActionBaBei", {"seat": 0, "moqie": True})
    trace.action("ActionDealTile", {"seat": 0, "tile": "4z", "operation": operation(0)})
    trace.action("ActionBaBei", {"seat": 0, "moqie": True})
    trace.action("ActionDealTile", {"seat": 0, "tile": "0s", "operation": operation(0)})
    trace.action("ActionDiscardTile", {"seat": 0, "tile": "0s", "moqie": True})
    trace.action("ActionDealTile", {"seat": 1, "tile": ""})
    trace.action("ActionDiscardTile", {"seat": 1, "tile": "7z", "moqie": True})
    traces.append(trace.export())

    trace = Trace("chi_and_open_kan", seat=1)
    trace.action("ActionMJStart", {})
    trace.action("ActionNewRound", new_round(
        ["1m", "2m", "4m", "5m", "6m", "1p", "2p", "3p", "1s", "2s", "3s", "9s", "7z"]))
    trace.action("ActionDiscardTile", {"seat": 0, "tile": "3m", "moqie": False, "operation": operation(1, 2)})
    trace.action("ActionChiPengGang", {"seat": 1, "type": 0, "tiles": ["1m", "2m", "3m"],
                                      "froms": [1, 1, 0], "operation": operation(1)})
    trace.action("ActionDiscardTile", {"seat": 1, "tile": "7z", "moqie": False})
    trace.action("ActionChiPengGang", {"seat": 2, "type": 2, "tiles": ["7z"] * 4, "froms": [2, 2, 2, 1]})
    trace.action("ActionDealTile", {"seat": 2, "tile": "", "doras": ["9p", "8m"]})
    trace.action("ActionNoTile", {})
    traces.append(trace.export())

    trace = Trace("reconnect_history", players=4, seat=1)
    trace.close()
    trace.conn = "game-reconnected"
    trace.open(trace.conn)
    trace.rpc(".lq.FastTest.authGame", {"accountId": 12345, "gameUuid": "fixture-reconnect_history"}, 1)
    trace.rpc(".lq.FastTest.authGame", {"seatList": [20001, 12345, 20003, 20004]}, 1, response=True)
    records = [action_record("ActionMJStart", 0, {}),
               action_record("ActionNewRound", 1, new_round(
                   ["1m", "2m", "3m", "4m", "0m", "6m", "2p", "3p", "4p", "5s", "6s", "7s", "5z"])),
               action_record("ActionDiscardTile", 2, {"seat": 0, "tile": "9m", "moqie": False}),
               action_record("ActionDealTile", 3, {"seat": 1, "tile": "3s", "operation": operation(1)})]
    trace.rpc(".lq.FastTest.syncGame", {"step": 0, "roundId": "fixture-round"}, 2)
    trace.rpc(".lq.FastTest.syncGame", {"step": 3, "gameRestore": {
        "snapshot": {"players": [{}, {}, {}, {}]}, "actions": records}}, 2, response=True)
    traces.append(trace.export())

    # Actual non-XOR ActionNewRound bytes from test_majsoul_sync_and_reconnect.py,
    # originally recorded in the desktop log's reconnect regression (line 515).
    real_round = (
        "CAAQABgAIgI5cCICNXoiAjdzIgI3eiICNHMiAjdwIgIycCICOXAiAjN6IgI3cCICOHMiAjV6IgI4cyICNnoy"
        "CbiRAriRAriRAjoMCAASAggBIAAomL8SQABYAGg2cgIyc3oCCAB6AggBegIIApoBQGQxMjI2MDQwYzY0N2RiNTM0"
        "NTA1NzU5NmFhNzE1OGUxMzZlYjk3NmEwYWE5YTViMzFhZWE3ZWIyODJlOGM3NziqAUA0ZjA4NGQ2YWViYTZl"
        "ZmIxMDEzMTY4NjFmMzU0MWJiZjdlNDBiMzE0MDgzODA5MTUyNDBhMDRmMGYxZTM0MzU3"
    )
    for method, name in [("syncGame", "captured_three_player_sync"), ("enterGame", "captured_three_player_enter")]:
        trace = Trace(name, players=3)
        trace.rpc(f".lq.FastTest.{method}", {}, 2)
        trace.rpc(f".lq.FastTest.{method}", {"gameRestore": {
            "snapshot": {"players": [{}, {}, {}]}, "actions": [
                {"name": "ActionMJStart", "step": 0, "data": ""},
                {"name": "ActionNewRound", "step": 1, "data": real_round}]}}, 2, response=True)
        exported = trace.export()
        exported["source"] = "akagi_backend/tests/unit/test_majsoul_sync_and_reconnect.py:test_reconnect_with_real_log_data"
        traces.append(exported)

    return traces


def main():
    destination = ROOT / "android" / "app" / "src" / "test" / "resources" / "protocol"
    destination.mkdir(parents=True, exist_ok=True)
    schema = ROOT / "assets" / "liqi.json"
    result = {"format": 1, "reference": "akagi_ng.bridge.majsoul.MajsoulBridge",
              "schemaSha256": hashlib.sha256(schema.read_bytes()).hexdigest(), "cases": make_traces()}
    (destination / "fixtures.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    shutil.copyfile(schema, destination / "liqi.json")
    print(f"Generated {len(result['cases'])} serialized protocol traces from the real Python reference")


if __name__ == "__main__":
    main()
