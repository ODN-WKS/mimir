//! Validates the router fixture file: every line must be valid JSON with the
//! expected fields, and every route value must be one of the allowed ones.
//! The Android side reads the same file to test Router.need() against each input.

use serde_json::Value;

const ALLOWED_ROUTES: &[&str] = &["search", "weather", "encyclopedia", "page", "none"];

#[test]
fn router_fixtures_are_valid_and_complete() {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/router.jsonl");
    let text =
        std::fs::read_to_string(&path).unwrap_or_else(|_| panic!("missing {}", path.display()));
    let mut count = 0;
    // Required issue IDs that must each have at least one fixture.
    let mut seen_phe05 = false;
    let mut seen_phe06 = false;
    let mut seen_phe10 = false;
    let mut seen_phe13 = false;
    let mut seen_phe15 = false;
    for (i, line) in text.lines().enumerate() {
        if line.trim().is_empty() {
            continue;
        }
        let v: Value = serde_json::from_str(line)
            .unwrap_or_else(|e| panic!("line {}: bad JSON: {e}\n  {line}", i + 1));
        // "input" must be a non-empty string.
        let input = v["input"]
            .as_str()
            .unwrap_or_else(|| panic!("line {}: missing or non-string 'input'", i + 1));
        assert!(
            !input.is_empty(),
            "line {}: 'input' must not be empty",
            i + 1
        );
        // "route" must be one of the allowed values.
        let route = v["route"]
            .as_str()
            .unwrap_or_else(|| panic!("line {}: missing or non-string 'route'", i + 1));
        assert!(
            ALLOWED_ROUTES.contains(&route),
            "line {}: route {:?} is not one of {:?}",
            i + 1,
            route,
            ALLOWED_ROUTES
        );
        // "flags" must be an array.
        assert!(
            v["flags"].is_array(),
            "line {}: 'flags' must be a JSON array",
            i + 1
        );
        let flags: Vec<&str> = v["flags"]
            .as_array()
            .unwrap()
            .iter()
            .filter_map(|f| f.as_str())
            .collect();
        // Each flag with a colon must have a non-empty value part.
        for flag in &flags {
            if let Some((key, val)) = flag.split_once(':') {
                assert!(
                    !key.is_empty() && !val.is_empty(),
                    "line {}: flag {:?} has empty key or value",
                    i + 1,
                    flag
                );
            }
        }
        // Track issue coverage.
        if flags.contains(&"phe05") {
            seen_phe05 = true;
        }
        if flags.contains(&"phe06") {
            seen_phe06 = true;
        }
        if flags.contains(&"phe10") {
            seen_phe10 = true;
        }
        if flags.contains(&"phe13") {
            seen_phe13 = true;
        }
        if flags.contains(&"phe15") {
            seen_phe15 = true;
        }
        count += 1;
    }
    assert!(count > 0, "router.jsonl has no fixture lines");
    assert!(
        seen_phe05,
        "router.jsonl missing a phe05 fixture (personal news → none)"
    );
    assert!(
        seen_phe06,
        "router.jsonl missing a phe06 fixture (non-query weather → none)"
    );
    assert!(
        seen_phe10,
        "router.jsonl missing a phe10 fixture (page-reader pattern)"
    );
    assert!(
        seen_phe13,
        "router.jsonl missing a phe13 fixture (City, State weather place)"
    );
    assert!(
        seen_phe15,
        "router.jsonl missing a phe15 fixture (Grokipedia only source)"
    );
}
