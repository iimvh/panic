//! The layers' direction (README): core uses nothing else; api only core; state only core and api; the ui
//! never calls the backend itself.

use std::fs;
use std::path::Path;

fn sources(dir: &Path, out: &mut Vec<(String, String)>) {
    for e in fs::read_dir(dir).unwrap().flatten() {
        let p = e.path();
        if p.is_dir() {
            sources(&p, out);
        } else if p.extension().is_some_and(|x| x == "rs") {
            out.push((p.display().to_string(), fs::read_to_string(&p).unwrap()));
        }
    }
}

fn check(layer: &str, forbidden: &[&str]) -> Vec<String> {
    let mut files = vec![];
    sources(&Path::new(env!("CARGO_MANIFEST_DIR")).join("src").join(layer), &mut files);
    let mut problems = vec![];
    for (path, text) in files {
        for f in forbidden {
            if text.contains(f) {
                problems.push(format!("{path} uses {f}"));
            }
        }
    }
    problems
}

#[test]
fn layers_point_one_way() {
    let mut problems = vec![];
    problems.extend(check("core", &["crate::api", "crate::state", "crate::ui", "ratatui", "ureq", "std::thread", "std::net"]));
    problems.extend(check("api", &["crate::state", "crate::ui", "ratatui"]));
    problems.extend(check("state", &["crate::ui", "ratatui"]));
    problems.extend(check("ui", &["Backend", "HttpBackend", "ureq", "std::thread"]));
    assert!(problems.is_empty(), "{problems:#?}");
}
