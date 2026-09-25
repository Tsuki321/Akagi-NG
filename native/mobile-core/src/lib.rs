//! Android-independent ownership of the pinned Mortal rules and observations.
//!
//! No inference server, Python interpreter, or stochastic action sampler is used.
mod jni_api;

use anyhow::{Result, bail};
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
    pub event: serde_json::Value,
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

mod yonma {
    use riichi as rules;
    const PLAYERS: u8 = 4;
    const AGARI_INDEX: usize = 43;
    include!("session_impl.rs");
}
mod sanma {
    use riichi3p as rules;
    const PLAYERS: u8 = 3;
    const AGARI_INDEX: usize = 41;
    include!("session_impl.rs");
}

#[derive(Clone)]
pub enum Session {
    Yonma(yonma::Session),
    Sanma(sanma::Session),
}

impl Session {
    pub fn new(player_id: u8, players: u8) -> Result<Self> {
        match players {
            4 => Ok(Self::Yonma(yonma::Session::new(player_id, players)?)),
            3 => Ok(Self::Sanma(sanma::Session::new(player_id, players)?)),
            _ => bail!("only three and four player tables are supported"),
        }
    }
    pub fn accept(&mut self, line: &str) -> Result<Snapshot> {
        match self { Self::Yonma(s) => s.accept(line), Self::Sanma(s) => s.accept(line) }
    }
    pub fn snapshot(&self) -> Snapshot {
        match self { Self::Yonma(s) => s.snapshot(), Self::Sanma(s) => s.snapshot() }
    }
    pub fn encoding(&self, kan: bool) -> Result<&Encoding> {
        match self { Self::Yonma(s) => s.encoding(kan), Self::Sanma(s) => s.encoding(kan) }
    }
    pub fn resolve(&self, scores: &[f32], kan_scores: &[f32]) -> Result<Advice> {
        match self { Self::Yonma(s) => s.resolve(scores, kan_scores), Self::Sanma(s) => s.resolve(scores, kan_scores) }
    }
    pub fn fork_reach(&self) -> Result<Self> {
        match self {
            Self::Yonma(s) => Ok(Self::Yonma(s.fork_reach()?)),
            Self::Sanma(s) => Ok(Self::Sanma(s.fork_reach()?)),
        }
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
        assert!(advice.recommended.event["type"] == "dahai");
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
        assert!(session.resolve(&vec![0.0; 46], &[]).is_err());
    }

    #[test]
    fn torch_tie_rule_is_first_legal_index() {
        assert_eq!(yonma::argmax(&[0.0, 1.0, 1.0], &[false, true, true]).unwrap(), 1);
    }

    #[test]
    fn legacy_sanma_layout_matches_measured_desktop_channels() {
        let mut session = Session::new(0, 3).unwrap();
        for line in include_str!("../../fixtures/smoke_3p.jsonl").lines() {
            session.accept(line).unwrap();
        }
        let encoding = session.encoding(false).unwrap();
        assert_eq!(encoding.obs.len(), 775 * 34);
        assert_eq!(encoding.mask.len(), 44);
        assert!(encoding.mask[40]); // Kita, frozen desktop action slot.
        assert!(!encoding.mask[1..8].iter().any(|&legal| legal));
        assert_eq!(encoding.obs[7 * 34], 35000.0 / 105000.0);
        assert_eq!(encoding.obs[8 * 34], 35000.0 / 40000.0);
        assert_eq!(encoding.obs[518 * 34], 54.0 / 69.0);
        assert_eq!(encoding.obs[608 * 34 + 1], 1.0); // Exhausted 2m.
        assert_eq!(encoding.obs[647 * 34], 1.0); // Kita feature after daiminkan.
    }

    #[test]
    fn riichi_fork_does_not_advance_live_state_and_suppressed_replay_can_resume() {
        let mut events: Vec<serde_json::Value> = TRACE.lines().map(|line| serde_json::from_str(line).unwrap()).collect();
        events[1]["tehais"][0] = serde_json::json!(["1m", "2m", "3m", "4m", "5m", "6m", "2p", "3p", "4p", "6s", "7s", "8s", "E"]);
        events[2]["pai"] = serde_json::json!("9p");
        let mut live = Session::new(0, 4).unwrap();
        let mut replay = Session::new(0, 4).unwrap();
        for (index, event) in events.iter().enumerate() {
            live.accept(&event.to_string()).unwrap();
            let mut replay_event = event.clone();
            replay_event["can_act"] = serde_json::json!(index == events.len() - 1);
            replay.accept(&replay_event.to_string()).unwrap();
        }
        assert_eq!(live.encoding(false).unwrap().obs, replay.encoding(false).unwrap().obs);
        assert_eq!(live.encoding(false).unwrap().mask, replay.encoding(false).unwrap().mask);
        assert!(live.snapshot().can_riichi);
        let before = live.encoding(false).unwrap().clone();
        let fork = live.fork_reach().unwrap();
        assert!(fork.snapshot().can_act);
        assert!(!fork.snapshot().can_riichi);
        assert_eq!(before.obs, live.encoding(false).unwrap().obs);
        assert_eq!(before.mask, live.encoding(false).unwrap().mask);
    }
}
