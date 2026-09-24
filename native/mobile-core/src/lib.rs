//! Android-independent ownership of the pinned Mortal rules and observations.
//!
//! No inference server, Python interpreter, or stochastic action sampler is used.
mod jni_api;

use anyhow::{Context, Result, ensure};
use riichi::consts::{ACTION_SPACE, obs_shape};
use riichi::mjai::{Event, EventWithCanAct};
use riichi::state::PlayerState;
use serde::Serialize;

pub const MODEL_VERSION: u32 = 4;
pub const SOURCE_REVISION: &str = "e11e17452cc49f2a3cd8e26286130bb4448d3285";

#[derive(Clone, Serialize)]
pub struct Encoding {
    pub obs: Vec<f32>,
    pub mask: Vec<bool>,
}

#[derive(Serialize)]
pub struct Snapshot {
    pub can_act: bool,
    pub can_riichi: bool,
    pub kan_select: bool,
    pub shanten: i8,
    pub at_furiten: bool,
    pub mask_bits: u64,
    pub kan_mask_bits: u64,
    pub channels: usize,
    pub action_space: usize,
}

#[derive(Clone, Serialize)]
pub struct ScoredAction {
    pub index: usize,
    pub score: f32,
    pub event: Event,
}

#[derive(Serialize)]
pub struct Advice {
    pub recommended: ScoredAction,
    pub alternatives: Vec<ScoredAction>,
    pub shanten: i8,
    pub at_furiten: bool,
    pub legal_mask: u64,
    pub kan_action: Option<usize>,
    pub agari_guard_applied: bool,
}

/// One ordered MJAI stream. The caller owns synchronization and queue ordering.
#[derive(Clone)]
pub struct Session {
    state: PlayerState,
    in_game: bool,
    in_round: bool,
    normal: Option<Encoding>,
    kan: Option<Encoding>,
}

fn mask_bits(mask: &[bool]) -> u64 {
    mask.iter().enumerate().fold(0, |bits, (i, &legal)| {
        bits | if legal { 1_u64 << i } else { 0 }
    })
}

fn encode(state: &PlayerState, kan: bool) -> Result<Encoding> {
    let (obs, mask) = state.encode_obs(MODEL_VERSION, kan);
    ensure!(obs.dim() == obs_shape(MODEL_VERSION), "encoder observation shape changed");
    ensure!(mask.len() == ACTION_SPACE, "encoder action space changed");
    ensure!(mask.iter().any(|&legal| legal), "encoder produced no legal actions");
    ensure!(obs.iter().all(|v| v.is_finite()), "encoder produced a non-finite observation");
    Ok(Encoding { obs: obs.into_iter().collect(), mask: mask.into_iter().collect() })
}

fn validate_scores(scores: &[f32], encoding: &Encoding) -> Result<()> {
    ensure!(scores.len() == encoding.mask.len(), "model output has wrong action count");
    ensure!(scores.iter().zip(&encoding.mask).all(|(&score, &legal)| {
        if legal { score.is_finite() } else { score == f32::NEG_INFINITY }
    }), "model output violates finite legal scores or negative-infinity masking");
    Ok(())
}

/// Matches torch.argmax: first index wins a tie.
fn argmax(scores: &[f32], mask: &[bool]) -> Result<usize> {
    let mut best = None;
    for (i, (&score, &legal)) in scores.iter().zip(mask).enumerate() {
        if legal && best.is_none_or(|j| score > scores[j]) {
            best = Some(i);
        }
    }
    best.context("no legal action")
}

impl Session {
    pub fn new(player_id: u8, player_count: u8) -> Result<Self> {
        ensure!(player_count == 4, "Three-player source compatibility has not been validated in this build");
        ensure!(player_id < player_count, "player id is outside the table");
        Ok(Self { state: PlayerState::new(player_id), in_game: false, in_round: false, normal: None, kan: None })
    }

    /// Updates a cloned state before committing, so a bad event cannot partially
    /// change the active game. Protocol loss still requires caller resynchronization.
    pub fn accept(&mut self, line: &str) -> Result<Snapshot> {
        ensure!(line.len() <= 131_072, "MJAI event too large");
        let data: EventWithCanAct = serde_json::from_str(line).context("invalid MJAI event")?;
        self.normal = None;
        self.kan = None;
        match &data.event {
            Event::StartGame { .. } => {
                self.state = PlayerState::new(self.state.player_id());
                self.in_game = true;
                self.in_round = false;
            }
            Event::StartKyoku { tehais, bakaze, dora_marker, .. } => {
                ensure!(self.in_game, "start_kyoku arrived before start_game");
                ensure!(!bakaze.is_unknown() && !dora_marker.is_unknown(), "round has unknown wind or dora");
                ensure!(tehais[self.state.player_id() as usize].iter().all(|t| !t.is_unknown()), "local starting hand contains unknown tiles");
            }
            Event::EndGame => ensure!(self.in_game, "end_game arrived before start_game"),
            Event::None => {}
            _ => ensure!(self.in_game && self.in_round, "game event arrived outside an active round"),
        }
        let mut next = self.state.clone();
        let cans = next.update(&data.event)?;
        let may_act = data.can_act != Some(false) && cans.can_act();
        // Normal and kan encodings use the same state, as in MortalBatchAgent.
        let normal = may_act.then(|| encode(&next, false)).transpose()?;
        let kan = (may_act && (cans.can_ankan || cans.can_kakan)).then(|| encode(&next, true)).transpose()?;
        self.state = next;
        self.normal = normal;
        self.kan = kan;
        match data.event {
            Event::StartKyoku { .. } => self.in_round = true,
            Event::EndKyoku => self.in_round = false,
            Event::EndGame => { self.in_game = false; self.in_round = false; }
            _ => {}
        }
        Ok(self.snapshot())
    }

    pub fn snapshot(&self) -> Snapshot {
        Snapshot {
            can_act: self.normal.is_some(),
            can_riichi: self.normal.is_some() && self.state.last_cans().can_riichi,
            kan_select: self.kan.is_some(),
            shanten: self.state.shanten(),
            at_furiten: self.state.at_furiten(),
            mask_bits: self.normal.as_ref().map_or(0, |e| mask_bits(&e.mask)),
            kan_mask_bits: self.kan.as_ref().map_or(0, |e| mask_bits(&e.mask)),
            channels: obs_shape(MODEL_VERSION).0,
            action_space: ACTION_SPACE,
        }
    }

    pub fn encoding(&self, kan: bool) -> Result<&Encoding> {
        if kan { &self.kan } else { &self.normal }.as_ref().context("no current inference observation")
    }

    pub fn resolve(&self, scores: &[f32], kan_scores: &[f32]) -> Result<Advice> {
        let encoding = self.encoding(false)?;
        validate_scores(scores, encoding)?;
        let kan_action = if let Some(kan) = &self.kan {
            validate_scores(kan_scores, kan)?;
            Some(argmax(kan_scores, &kan.mask)?)
        } else {
            ensure!(kan_scores.is_empty(), "unexpected kan output");
            None
        };
        let original = argmax(scores, &encoding.mask)?;
        // Preserve BaseEngine.enable_rule_based_agari_guard=True and its tie rule.
        let guard = original == 43 && !self.state.rule_based_agari();
        let selected = if guard {
            (0..scores.len()).filter(|&i| encoding.mask[i] && i != 43)
                .max_by(|&a, &b| scores[a].total_cmp(&scores[b]))
                .context("agari guard has no legal alternative")?
        } else { original };
        let mut candidates = Vec::new();
        for (index, &legal) in encoding.mask.iter().enumerate() {
            if legal {
                candidates.push(ScoredAction {
                    index,
                    score: scores[index],
                    event: riichi::mobile_action::decode(&self.state, index, kan_action)?,
                });
            }
        }
        let recommended = candidates.iter().find(|a| a.index == selected).context("selected action is not legal")?.clone();
        candidates.sort_by(|a, b| b.score.total_cmp(&a.score).then(a.index.cmp(&b.index)));
        Ok(Advice {
            recommended,
            alternatives: candidates,
            shanten: self.state.shanten(),
            at_furiten: self.state.at_furiten(),
            legal_mask: mask_bits(&encoding.mask),
            kan_action,
            agari_guard_applied: guard,
        })
    }

    pub fn fork_reach(&self) -> Result<Self> {
        ensure!(self.normal.is_some() && self.state.last_cans().can_riichi, "riichi is not currently legal");
        let mut fork = self.clone();
        fork.accept(&serde_json::to_string(&Event::Reach { actor: self.state.player_id() })?)?;
        Ok(fork)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const TRACE: &str = include_str!("../../fixtures/smoke_4p.jsonl");

    fn ready() -> Session {
        let mut session = Session::new(0, 4).unwrap();
        for event in TRACE.lines().filter(|s| !s.trim().is_empty()) {
            session.accept(event).unwrap();
        }
        session
    }

    #[test]
    fn replay_uses_real_encoder_and_legal_decoder() {
        let session = ready();
        let encoding = session.encoding(false).unwrap();
        assert_eq!(encoding.obs.len(), 1012 * 34);
        let scores: Vec<_> = encoding.mask.iter().enumerate().map(|(i, &m)| if m { -(i as f32) } else { f32::NEG_INFINITY }).collect();
        let advice = session.resolve(&scores, &[]).unwrap();
        assert!(matches!(advice.recommended.event, Event::Dahai { .. }));
        assert_eq!(advice.alternatives.len(), encoding.mask.iter().filter(|&&v| v).count());
    }

    #[test]
    fn replay_without_inference_does_not_leave_stale_observations() {
        let mut session = ready();
        session.accept(r#"{"type":"none","can_act":false}"#).unwrap();
        assert!(!session.snapshot().can_act);
        assert!(session.encoding(false).is_err());
    }

    #[test]
    fn rejects_missing_lifecycle_and_invalid_scores() {
        let mut session = Session::new(0, 4).unwrap();
        assert!(session.accept(r#"{"type":"tsumo","actor":0,"pai":"1m"}"#).is_err());
        let session = ready();
        assert!(session.resolve(&vec![0.0; ACTION_SPACE], &[]).is_err());
    }

    #[test]
    fn torch_tie_rule_is_first_legal_index() {
        assert_eq!(argmax(&[0.0, 1.0, 1.0], &[false, true, true]).unwrap(), 1);
    }
}
