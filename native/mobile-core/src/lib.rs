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
    pub fn accept_batch(&mut self, lines: &[String]) -> Result<Snapshot> {
        match self { Self::Yonma(s) => s.accept_batch(lines), Self::Sanma(s) => s.accept_batch(lines) }
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

    fn smoke_events(players: u8) -> Vec<String> {
        let trace = if players == 4 { TRACE } else { include_str!("../../fixtures/smoke_3p.jsonl") };
        trace.lines().map(str::to_owned).collect()
    }

    fn assert_same_encoding(left: &Session, right: &Session) {
        assert_eq!(left.encoding(false).unwrap().mask, right.encoding(false).unwrap().mask);
        assert_eq!(left.encoding(false).unwrap().obs, right.encoding(false).unwrap().obs);
    }

    #[test]
    fn batch_includes_new_dora_before_encoding_the_pending_draw() {
        for players in [4, 3] {
            let events = smoke_events(players);
            let mut batch = Session::new(0, players).unwrap();
            let mut reference = Session::new(0, players).unwrap();
            for event in &events[..2] {
                batch.accept(event).unwrap();
                reference.accept(event).unwrap();
            }
            let dora = r#"{"type":"dora","dora_marker":"4p","can_act":false}"#.to_owned();
            batch.accept_batch(&[events[2].clone(), dora.clone()]).unwrap();
            reference.accept(&dora).unwrap();
            reference.accept(&events[2]).unwrap();
            assert_same_encoding(&batch, &reference);

            // An announcement in a later source action cannot resurrect a
            // candidate from the previous batch.
            let snapshot = batch.accept_batch(&[r#"{"type":"dora","dora_marker":"1s"}"#.to_owned()]).unwrap();
            assert!(!snapshot.can_act);
        }
    }

    #[test]
    fn batch_preserves_ron_window_while_applying_reach_payment() {
        for players in [4, 3] {
            let mut events: Vec<serde_json::Value> = smoke_events(players).iter().map(|s| serde_json::from_str(s).unwrap()).collect();
            events[1]["oya"] = serde_json::json!(1);
            events[1]["tehais"][0] = serde_json::json!(["E", "E", "E", "1p", "2p", "3p", "4p", "5p", "6p", "7s", "8s", "9s", "C"]);
            let mut session = Session::new(0, players).unwrap();
            for event in &events[..2] { session.accept(&event.to_string()).unwrap(); }
            session.accept(r#"{"type":"tsumo","actor":1,"pai":"?","can_act":false}"#).unwrap();
            session.accept(r#"{"type":"reach","actor":1,"can_act":false}"#).unwrap();
            let snapshot = session.accept_batch(&[
                r#"{"type":"dahai","actor":1,"pai":"C","tsumogiri":true,"can_act":true}"#.to_owned(),
                r#"{"type":"reach_accepted","actor":1,"can_act":false}"#.to_owned(),
            ]).unwrap();
            let encoding = session.encoding(false).unwrap();
            let win = if players == 4 { 43 } else { 41 };
            assert!(encoding.mask[win]);
            assert!(!snapshot.at_furiten);
            let (score, cap, deposits) = if players == 4 { (24000.0, 100000.0, 24) } else { (34000.0, 105000.0, 20) };
            assert_eq!(encoding.obs[9 * 34], score / cap);
            assert_eq!(encoding.obs[deposits * 34], 0.1);
            // Passing on the actual next event still applies same-cycle furiten.
            let next = session.accept(r#"{"type":"tsumo","actor":1,"pai":"?","can_act":false}"#).unwrap();
            assert!(next.at_furiten);
        }
    }

    #[test]
    fn batch_keeps_chankan_context_through_a_dora_announcement() {
        for players in [4, 3] {
            let mut events: Vec<serde_json::Value> = smoke_events(players).iter().map(|s| serde_json::from_str(s).unwrap()).collect();
            events[1]["oya"] = serde_json::json!(2);
            events[1]["dora_marker"] = serde_json::json!("2p");
            events[1]["tehais"][0] = serde_json::json!(["E", "E", "E", "1p", "2p", "3p", "4p", "5p", "6p", "7s", "N", "N", "F"]);
            let mut prefix: Vec<String> = events[..2].iter().map(|e| e.to_string()).collect();
            prefix.extend([
                r#"{"type":"tsumo","actor":2,"pai":"?","can_act":false}"#,
                r#"{"type":"dahai","actor":2,"pai":"9s","tsumogiri":true,"can_act":false}"#,
                r#"{"type":"pon","actor":1,"target":2,"pai":"9s","consumed":["9s","9s"],"can_act":false}"#,
                r#"{"type":"dahai","actor":1,"pai":"P","tsumogiri":false,"can_act":false}"#,
                r#"{"type":"tsumo","actor":0,"pai":"8s","can_act":false}"#,
                r#"{"type":"dahai","actor":0,"pai":"F","tsumogiri":false,"can_act":false}"#,
                r#"{"type":"tsumo","actor":1,"pai":"?","can_act":false}"#,
            ].map(str::to_owned));
            let mut batch = Session::new(0, players).unwrap();
            batch.accept_batch(&prefix).unwrap();
            let mut reference = batch.clone();
            let kakan = r#"{"type":"kakan","actor":1,"pai":"9s","consumed":["9s","9s","9s"],"can_act":true}"#.to_owned();
            let dora = r#"{"type":"dora","dora_marker":"3p","can_act":false}"#.to_owned();
            let snapshot = batch.accept_batch(&[kakan.clone(), dora.clone()]).unwrap();
            reference.accept(&dora).unwrap();
            reference.accept(&kakan).unwrap();
            assert!(snapshot.can_act && !snapshot.at_furiten);
            assert_same_encoding(&batch, &reference);
        }
    }

    #[test]
    fn batch_failure_is_atomic_and_suppressed_replay_stays_suppressed() {
        let mut session = ready();
        let before = session.clone();
        let bad = vec![
            r#"{"type":"dora","dora_marker":"4p"}"#.to_owned(),
            r#"{"type":"dahai","actor":0,"pai":"9p","tsumogiri":false}"#.to_owned(),
        ];
        assert!(session.accept_batch(&bad).is_err());
        assert_same_encoding(&session, &before);

        let mut replay = Session::new(0, 4).unwrap();
        let mut events = smoke_events(4);
        let mut draw: serde_json::Value = serde_json::from_str(&events[2]).unwrap();
        draw["can_act"] = serde_json::json!(false);
        events[2] = draw.to_string();
        events.push(r#"{"type":"dora","dora_marker":"4p","can_act":true}"#.to_owned());
        assert!(!replay.accept_batch(&events).unwrap().can_act);
    }
}
