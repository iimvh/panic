//! Pure logic, no IO and no terminal: the server's events and data turned into what the views show.
//! Ported from ui-v2's `src/lib/core`, with its test cases, so both front-ends read the API the same way.

pub mod conversations;
pub mod diff;
pub mod format;
pub mod markdown;
pub mod reply;
pub mod session;
pub mod sse;
pub mod summary;
pub mod trace;
