//! The words of the live sign: what Claude is doing, in plain words, and for how long.

use mimir_proto::{doing_words, worked_for};

#[test]
fn a_running_step_gets_plain_words_without_a_path() {
    assert_eq!(
        doing_words("Bash", "Ran ls -la /home/x"),
        "Running a command"
    );
    assert_eq!(doing_words("Read", "Read main.rs"), "Reading main.rs");
    assert_eq!(doing_words("Read", ""), "Reading a file");
    assert_eq!(doing_words("Write", "Wrote notes.md"), "Writing notes.md");
    assert_eq!(doing_words("Edit", "Changed lib.rs"), "Changing lib.rs");
    assert_eq!(
        doing_words("Grep", "Searched foo"),
        "Looking through the files"
    );
    assert_eq!(doing_words("Weird", "x"), "Working on it");
}

#[test]
fn time_worked_reads_short() {
    assert_eq!(worked_for(0), "0 s");
    assert_eq!(worked_for(59), "59 s");
    assert_eq!(worked_for(60), "1 min");
    assert_eq!(worked_for(80), "1 min 20 s");
    assert_eq!(worked_for(900), "15 min");
}
