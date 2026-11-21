//! Idle budgets (QA 2026-11-19): with finished tasks on screen and nothing
//! going on, the window asks for few repaints a second and the service
//! uses almost no processor. A busy loop shows up here as a failure.

mod common;
use common::*;
use std::time::{Duration, Instant};

/// A processor-time reading of a process, in clock ticks (utime + stime).
fn cpu_ticks(pid: i32) -> Option<u64> {
    let stat = std::fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
    // The name in brackets may hold spaces; the numbers follow its end.
    let rest = &stat[stat.rfind(')')? + 2..];
    let f: Vec<&str> = rest.split_whitespace().collect();
    Some(f.get(11)?.parse::<u64>().ok()? + f.get(12)?.parse::<u64>().ok()?)
}

/// Two finished tasks in the list, settled; returns the window and home.
fn idle_window() -> Option<(TestHome, egui_kittest::Harness<'static, station_ui::App>)> {
    let home = TestHome::new()?;
    home.play("run-1.jsonl", "near-limit.jsonl");
    home.play("run-2.jsonl", "near-limit.jsonl");
    let mut h = home.open();
    start_task(&mut h, "Tidy up notes.txt.");
    assert!(
        wait_for(&mut h, |h| shows(h, "notes.txt is sorted")),
        "the first task didn't finish\n{}",
        screen_text(&h)
    );
    // Let every animation end.
    for _ in 0..80 {
        h.step();
        std::thread::sleep(Duration::from_millis(20));
    }
    Some((home, h))
}

/// Idle, the window never asks to be redrawn sooner than 100 ms (at most
/// 10 repaints a second), over five seconds.
#[test]
fn an_idle_window_stays_under_ten_repaints_a_second() {
    let Some((_home, mut h)) = idle_window() else {
        return;
    };
    let start = Instant::now();
    let (mut frames, mut eager) = (0, 0);
    while start.elapsed() < Duration::from_secs(5) {
        h.step();
        frames += 1;
        std::thread::sleep(Duration::from_millis(20));
        let d = h.output().viewport_output[&eframe::egui::ViewportId::ROOT].repaint_delay;
        if d < Duration::from_millis(100) {
            eager += 1;
        }
    }
    // A stray frame (a blink, a late reply) is allowed; a busy loop asks every time.
    assert!(
        eager * 20 <= frames,
        "idle for 5 s, the window asked for a repaint sooner than 100 ms {eager} times in {frames} frames\n{}",
        screen_text(&h)
    );
    close(h);
}

/// Idle, the service spends under 5% of one processor over six seconds:
/// no busy polling.
#[test]
fn an_idle_service_uses_almost_no_processor() {
    let Some((home, h)) = idle_window() else {
        return;
    };
    let pid = home.service_pid().expect("the window started no service");
    let ticks_per_s = 100.0; // Linux USER_HZ
    let before = cpu_ticks(pid).expect("service not running");
    let start = Instant::now();
    while start.elapsed() < Duration::from_secs(6) {
        std::thread::sleep(Duration::from_millis(100));
    }
    let used = (cpu_ticks(pid).expect("service ended") - before) as f64 / ticks_per_s;
    let share = used / start.elapsed().as_secs_f64();
    assert!(
        share < 0.05,
        "the idle service used {:.1}% of a processor ({used:.2} s in 6 s)",
        share * 100.0
    );
    close(h);
}
