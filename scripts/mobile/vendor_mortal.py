"""Reproduce the Python-free Mortal rules subset. This does not compile anything.

The input must be the checkout of the pinned upstream revision. Original rules,
observations, tables, tests and action decoding are retained. Only the Python
module glue is removed; mobile state ownership lives in mobile-core.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
from pathlib import Path

REVISION = "e11e17452cc49f2a3cd8e26286130bb4448d3285"
ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / "native/vendor/mortal"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkout", type=Path)
    args = parser.parse_args()
    src = args.checkout / "libriichi/src"
    DEST.mkdir(parents=True, exist_ok=True)
    provenance: dict[str, str] = {}
    for path in sorted(src.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(src)
        if relative.parts[0] not in {
            "algo", "array.rs", "chi_type.rs", "consts.rs", "hand.rs", "macros.rs",
            "rankings.rs", "state", "tile.rs", "vec_ops.rs", "mjai",
        } or relative.as_posix() == "mjai/bot.rs":
            continue
        provenance[relative.as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
        target = DEST / "src" / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        if path.suffix != ".rs":
            shutil.copyfile(path, target)
            continue
        content = path.read_text(encoding="utf-8")
        if relative.as_posix() == "state/obs_repr.rs":
            start = content.index("#[pymethods]\nimpl PlayerState")
            end = content.index("\nimpl PlayerState", start + len("#[pymethods]\nimpl PlayerState"))
            content = content[:start] + content[end:]
        if relative.as_posix() in {"consts.rs", "state/mod.rs", "mjai/mod.rs"}:
            content = content[:content.index("pub(crate) fn register_module")]
        content = re.sub(r"^use (?:pyo3|numpy|crate::py_helper)::[^\n]+\n", "", content, flags=re.M)
        content = re.sub(r"^\s*#\[(?:pyclass|pymethods|pyfunction|getter|new|pyo3\([^\n]*\))\]\n", "\n", content, flags=re.M)
        content = content.replace("mod bot;\n", "").replace("use bot::Bot;\n", "")
        target.write_text(content, encoding="utf-8", newline="\n")
    shutil.copyfile(args.checkout / "LICENSE", DEST / "LICENSE")
    (DEST / "src/lib.rs").write_text(
        "//! Python-free rules subset; see SOURCE.json and MODIFICATIONS.md.\n"
        "mod array;\nmod macros;\nmod rankings;\nmod vec_ops;\n"
        "pub mod algo;\npub mod chi_type;\npub mod consts;\npub mod hand;\n"
        "pub mod mjai;\npub mod state;\npub mod tile;\npub mod mobile_action;\n",
        encoding="utf-8",
    )

    # Extract, instead of reimplementing, the exact upstream action map.
    original = (src / "agent/mortal.rs").read_text(encoding="utf-8")
    start = original.index("        let event = match action {")
    end = original.index("\n        let mut meta =", start)
    body = original[start:end]
    body = body.replace("if let Some(kan_idx) = kan_select_idx", "if let Some(kan_action) = kan_action")
    body = body.replace("must_tile!(self.actions[kan_idx])", "must_tile!(kan_action)")
    (DEST / "src/mobile_action.rs").write_text(
        "//! Action mapping extracted from upstream agent/mortal.rs; source license applies.\n"
        "use crate::mjai::Event;\nuse crate::state::PlayerState;\n"
        "use crate::{must_tile, tu8};\nuse anyhow::{Context, Result, ensure};\n\n"
        "pub fn decode(state: &PlayerState, action: usize, kan_action: Option<usize>) -> Result<Event> {\n"
        "    ensure!(action < crate::consts::ACTION_SPACE, \"action out of range\");\n"
        "    let actor = state.player_id();\n    let akas_in_hand = state.akas_in_hand();\n"
        "    let cans = state.last_cans();\n"
        + body + "\n    state.validate_reaction(&event)?;\n    Ok(event)\n}\n",
        encoding="utf-8",
    )
    (DEST / "SOURCE.json").write_text(json.dumps({
        "repository": "https://github.com/shinkuan/Mortal_v4",
        "revision": REVISION,
        "upstream": "https://github.com/Equim-chan/Mortal",
        "sha256_before_mobile_changes": provenance,
    }, indent=2) + "\n", encoding="utf-8")
    print(f"Vendored {len(provenance)} files into {DEST}")


if __name__ == "__main__":
    main()

