//! Host parity driver. Run only in GitHub Actions, then compare with the real
//! bundled CPython library and PyTorch inference in export_models.py.
use akagi_mortal::Session;
use anyhow::{Context, Result};
use serde_json::json;
use std::io::{BufRead, Write};

fn main() -> Result<()> {
    let args: Vec<_> = std::env::args().collect();
    let path = args.get(1).context("usage: mortal-fixtures TRACE.jsonl [PLAYER_ID]")?;
    let player: u8 = args.get(2).map_or("0", String::as_str).parse()?;
    let players: u8 = args.get(3).map_or("4", String::as_str).parse()?;
    let mut session = Session::new(player, players)?;
    let input = std::io::BufReader::new(std::fs::File::open(path)?);
    let mut output = std::io::BufWriter::new(std::io::stdout().lock());
    for (line_no, line) in input.lines().enumerate() {
        let line = line?;
        if line.trim().is_empty() { continue; }
        let snapshot = session.accept(&line).with_context(|| format!("trace line {}", line_no + 1))?;
        if !snapshot.can_act { continue; }
        let normal = session.encoding(false)?;
        let kan = snapshot.kan_select.then(|| session.encoding(true)).transpose()?;
        let scores: Vec<_> = normal.mask.iter().enumerate().map(|(i, &legal)| if legal { -(i as f32) } else { f32::NEG_INFINITY }).collect();
        let kan_scores: Vec<_> = kan.map_or_else(Vec::new, |k| k.mask.iter().enumerate().map(|(i, &legal)| if legal { -(i as f32) } else { f32::NEG_INFINITY }).collect());
        let advice = session.resolve(&scores, &kan_scores)?;
        let reach = if snapshot.can_riichi {
            let fork = session.fork_reach()?;
            Some(fork.encoding(false)?.clone())
        } else { None };
        writeln!(output, "{}", json!({
            "line": line_no,
            "snapshot": snapshot,
            "normal": normal,
            "kan": kan,
            "actions": advice.alternatives,
            "reach": reach,
        }))?;
    }
    Ok(())
}
