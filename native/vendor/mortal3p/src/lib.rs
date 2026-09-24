//! Python-free rules subset; see SOURCE.json and MODIFICATIONS.md.
mod array;
mod macros;
mod rankings;
mod vec_ops;
pub mod algo;
pub mod chi_type;
pub mod consts;
pub mod hand;
pub mod mjai;
pub mod state;
pub mod tile;
pub mod mobile_action;
pub use consts::NUM_PLAYERS;
#[cfg(test)]
mod sanma_compat_tests;
