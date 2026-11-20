//! Panic's terminal front-end. Layers, each using only those before it (checked by `tests/layers.rs`):
//!
//! - `core`: pure logic over the API's data (ported from ui-v2's `src/lib/core` with its test cases);
//! - `api`: the `web` plugin's JSON + SSE API behind the `Backend` trait;
//! - `state`: conversations and runs, the only layer that calls the backend;
//! - `ui`: drawing and keys (ratatui), never calls the backend.

pub mod api;
pub mod core;
pub mod state;
