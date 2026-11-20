//! Session ids: the server's conversation ids. A new one is 24 hex characters, as ui-v2 makes them.

use std::collections::hash_map::RandomState;
use std::hash::{BuildHasher, Hasher};
use std::time::{SystemTime, UNIX_EPOCH};

/// 12 random bytes as hex. `RandomState` is seeded from the OS per instance, so no extra crate is needed.
pub fn new_id() -> String {
    let mut out = String::with_capacity(24);
    for i in 0..2u64 {
        let mut h = RandomState::new().build_hasher();
        h.write_u64(i);
        h.write_u128(SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0));
        let v = h.finish();
        let hex = format!("{v:016x}");
        out.push_str(if i == 0 { &hex } else { &hex[..8] });
    }
    out
}

/// True for an id the server accepts: `^[A-Za-z0-9_-]{1,100}$`.
pub fn valid_id(id: &str) -> bool {
    (1..=100).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ids_are_24_hex_and_differ() {
        let (a, b) = (new_id(), new_id());
        assert_eq!(a.len(), 24);
        assert!(a.bytes().all(|c| c.is_ascii_hexdigit()));
        assert_ne!(a, b);
        assert!(valid_id(&a));
        assert!(!valid_id("a b"));
        assert!(!valid_id(""));
    }
}
