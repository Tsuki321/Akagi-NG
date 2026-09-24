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
import subprocess
from pathlib import Path

REVISION = "e11e17452cc49f2a3cd8e26286130bb4448d3285"
SANMA_REVISION = "8d149e3bbbc380b5b5f1c1d60f51f2d029812414"
ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / "native/vendor/mortal"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkout", type=Path)
    parser.add_argument("--sanma", action="store_true", help="Import the separate sanma rules candidate")
    args = parser.parse_args()
    destination = ROOT / "native/vendor/mortal3p" if args.sanma else DEST
    src = args.checkout / "libriichi/src"
    destination.mkdir(parents=True, exist_ok=True)
    provenance: dict[str, str] = {}
    for path in sorted(src.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(src)
        if relative.parts[0] not in {
            "algo", "array.rs", "chi_type.rs", "consts.rs", "hand.rs", "macros.rs",
            "rankings.rs", "state", "tile.rs", "vec_ops.rs", "mjai", "sanma_compat_tests.rs",
        } or relative.as_posix() == "mjai/bot.rs":
            continue
        provenance[relative.as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
        target = destination / "src" / relative
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
    shutil.copyfile(args.checkout / "LICENSE", destination / "LICENSE")
    (destination / "src/lib.rs").write_text(
        "//! Python-free rules subset; see SOURCE.json and MODIFICATIONS.md.\n"
        "mod array;\nmod macros;\nmod rankings;\nmod vec_ops;\n"
        "pub mod algo;\npub mod chi_type;\npub mod consts;\npub mod hand;\n"
        "pub mod mjai;\npub mod state;\npub mod tile;\npub mod mobile_action;\n"
        + ("pub use consts::NUM_PLAYERS;\n#[cfg(test)]\nmod sanma_compat_tests;\n" if args.sanma else ""),
        encoding="utf-8",
    )

    # Extract, instead of reimplementing, the exact upstream action map.
    original = (src / "agent/mortal.rs").read_text(encoding="utf-8")
    start = original.index("        let event = match action {")
    end = original.index("\n        let mut meta =", start)
    body = original[start:end]
    body = body.replace("if let Some(kan_idx) = kan_select_idx", "if let Some(kan_action) = kan_action")
    body = body.replace("must_tile!(self.actions[kan_idx])", "must_tile!(kan_action)")
    (destination / "src/mobile_action.rs").write_text(
        "//! Action mapping extracted from upstream agent/mortal.rs; source license applies.\n"
        "use crate::mjai::Event;\nuse crate::state::PlayerState;\n"
        "use crate::{must_tile, tu8};\nuse anyhow::{Context, Result, ensure};\n\n"
        + ("use crate::consts::*;\n" if args.sanma else "")
        +
        "pub fn decode(state: &PlayerState, action: usize, kan_action: Option<usize>) -> Result<Event> {\n"
        "    ensure!(action < crate::consts::ACTION_SPACE, \"action out of range\");\n"
        "    let actor = state.player_id();\n    let akas_in_hand = state.akas_in_hand();\n"
        "    let cans = state.last_cans();\n"
        + body + "\n    state.validate_reaction(&event)?;\n    Ok(event)\n}\n",
        encoding="utf-8",
    )
    (destination / "SOURCE.json").write_text(json.dumps({
        "repository": "https://github.com/Rezetyan/MahjongAITraining" if args.sanma else "https://github.com/shinkuan/Mortal_v4",
        "revision": SANMA_REVISION if args.sanma else REVISION,
        "upstream": "https://github.com/Equim-chan/Mortal",
        "sha256_before_mobile_changes": provenance,
    }, indent=2) + "\n", encoding="utf-8")
    if args.sanma:
        # Only exact, committed source edits are applied; this never builds code.
        subprocess.run(["git", "apply", str(ROOT / "scripts/mobile/sanma_legacy.patch")], cwd=ROOT, check=True)
    print(f"Vendored {len(provenance)} files into {destination}")


if __name__ == "__main__":
    main()
