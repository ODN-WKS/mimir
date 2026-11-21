//! Debug builds only: a picture of the window and its frame times, for
//! checking looks and smoothness on a hidden display.
//!   MIMIR_SHOT=<file.png>[,<seconds>]   picture after that long, then close
//!   MIMIR_SHOT_SIZE=1440x900             the window's size
//!   MIMIR_SHOT_FOLD=1                   meanwhile, open and close the first folder
//!   MIMIR_SHOT_OPEN=task-menu           the open task's ⋯ menu, opened once
//!   MIMIR_SHOT_OPEN=from-phone          the first task from the phone, once listed
//!   MIMIR_SHOT_OPEN=task                the newest task, once listed
//!   MIMIR_SHOT_OPEN=chat                the newest general chat, once listed
//!   MIMIR_SHOT_OPEN=new-chat            the box for a new chat
//!   MIMIR_SHOT_OPEN=message-menu        a made-up chat, some words of the owner's
//!                                       message chosen with the pointer, then the
//!                                       message's right-click menu
//!   MIMIR_SHOT_OPEN=long-chat          a made-up chat with long answers
//!   MIMIR_SHOT_OPEN=left-menu           the right-click menu of the first chat in the side list
//!   MIMIR_SHOT_IDLE=1                   no extra frames (a window nobody touches)
//! Frame times go to <file.png>.frames.txt.

use eframe::egui;

#[derive(Clone, Default)]
struct State {
    asked: bool,
    sized: bool,
    frames: Vec<f32>,
    last_fold: f64,
}

/// Debug builds only, at the start of every frame. MIMIR_LOG_SCREEN=<file>:
/// the size egui lays out for and the window's own size, for the first 20 s.
pub(crate) fn log_frame(ctx: &egui::Context) {
    if !cfg!(debug_assertions) {
        return;
    }
    // MIMIR_LOG_SCREEN=<file>: the size egui lays out for, and the window's
    // own size, for the first 40 frames.
    if let Some(f) = std::env::var_os("MIMIR_LOG_SCREEN") {
        use std::io::Write;
        let (t, sr, ppp, inner, outer, mon) = ctx.input(|i| {
            let v = i.viewport();
            (
                i.time,
                i.content_rect(),
                i.pixels_per_point,
                v.inner_rect,
                v.outer_rect,
                v.monitor_size,
            )
        });
        if t < 20.0
            && let Ok(mut file) = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(f)
        {
            let _ = writeln!(
                file,
                "t={t:.2} content={sr:?} ppp={ppp} inner={inner:?} outer={outer:?} monitor={mon:?}"
            );
        }
    }
}

/// Called once a frame. Returns true when the first folder should be
/// opened or closed now.
pub(crate) fn tick(ctx: &egui::Context) -> bool {
    if !cfg!(debug_assertions) {
        return false;
    }
    let Some(spec) = std::env::var_os("MIMIR_SHOT") else {
        return false;
    };
    let spec = spec.to_string_lossy().to_string();
    let (file, secs) = match spec.rsplit_once(',') {
        Some((f, s)) => (f.to_string(), s.parse().unwrap_or(3.0)),
        None => (spec, 3.0),
    };
    let id = egui::Id::unique("debug-shot");
    let mut st: State = ctx.data(|d| d.get_temp(id)).unwrap_or_default();
    let (now, dt) = ctx.input(|i| (i.time, i.unstable_dt));
    // MIMIR_SHOT_IDLE=1: no extra frames, as a window nobody touches: only
    // the picture's own time is asked for.
    if std::env::var_os("MIMIR_SHOT_IDLE").is_some() {
        if !ctx.data(|d| d.get_temp::<bool>(egui::Id::unique("shot-idle")).is_some()) {
            ctx.data_mut(|d| d.insert_temp(egui::Id::unique("shot-idle"), true));
            ctx.request_repaint_after(std::time::Duration::from_secs_f64(secs));
        }
    } else {
        ctx.request_repaint();
    }
    // MIMIR_SHOT_SIZE=1440x900 sets the window's size first.
    if !st.sized {
        st.sized = true;
        if let Some((w, h)) = std::env::var("MIMIR_SHOT_SIZE")
            .ok()
            .and_then(|v| v.split_once('x').map(|(w, h)| (w.parse(), h.parse())))
            && let (Ok(w), Ok(h)) = (w, h)
        {
            ctx.send_viewport_cmd(egui::ViewportCommand::InnerSize(egui::vec2(w, h)));
            // A display of that size: the window fills it (some desktops
            // keep a new window's first size).
            ctx.send_viewport_cmd(egui::ViewportCommand::Maximized(true));
        }
    }
    let mut fold = false;
    if now > 1.0 && !st.asked {
        st.frames.push(dt * 1000.0);
        if std::env::var_os("MIMIR_SHOT_FOLD").is_some() && now - st.last_fold > 0.5 {
            st.last_fold = now;
            fold = true;
        }
    }
    if now >= secs && !st.asked {
        st.asked = true;
        let mut f = st.frames.clone();
        let ctx2 = ctx.clone();
        ctx.send_viewport_cmd(egui::ViewportCommand::Screenshot(
            egui::ScreenshotCallback::new(move |img| {
                let bytes: Vec<u8> = img
                    .pixels
                    .iter()
                    .flat_map(|c| c.to_srgba_unmultiplied())
                    .collect();
                let _ = image::save_buffer(
                    &file,
                    &bytes,
                    img.size[0] as u32,
                    img.size[1] as u32,
                    image::ExtendedColorType::Rgba8,
                );
                f.sort_by(|a, b| a.total_cmp(b));
                let at = |q: f32| {
                    f.get(((f.len() as f32 - 1.0) * q) as usize)
                        .copied()
                        .unwrap_or(0.0)
                };
                let _ = std::fs::write(
                    format!("{file}.frames.txt"),
                    format!(
                        "frames {}\nmedian_ms {:.2}\np90_ms {:.2}\np99_ms {:.2}\nmax_ms {:.2}\n",
                        f.len(),
                        at(0.5),
                        at(0.9),
                        at(0.99),
                        at(1.0)
                    ),
                );
                ctx2.send_viewport_cmd(egui::ViewportCommand::Close);
                ctx2.request_repaint();
            }),
        ));
    }
    ctx.data_mut(|d| d.insert_temp(id, st));
    fold
}

/// Debug builds only: opens the menu of `button` once, a second after the
/// window starts, when MIMIR_SHOT_OPEN names it.
pub(crate) fn open_menu(ctx: &egui::Context, name: &str, button: &egui::Response) {
    if !cfg!(debug_assertions) || std::env::var("MIMIR_SHOT_OPEN").ok().as_deref() != Some(name) {
        return;
    }
    let done = egui::Id::unique(("debug-shot-menu", name));
    if ctx.input(|i| i.time) < 1.5 || ctx.data(|d| d.get_temp::<bool>(done).is_some()) {
        return;
    }
    ctx.data_mut(|d| d.insert_temp(done, true));
    egui::Popup::open_id(ctx, egui::Popup::default_response_id(button));
}

/// Debug builds only: true once, after the window has run three seconds,
/// when MIMIR_SHOT_OPEN names `name`.
pub(crate) fn once(ctx: &egui::Context, name: &str) -> bool {
    if !cfg!(debug_assertions) || std::env::var("MIMIR_SHOT_OPEN").ok().as_deref() != Some(name) {
        return false;
    }
    let done = egui::Id::unique(("debug-shot-once", name));
    if ctx.input(|i| i.time) < 3.0 || ctx.data(|d| d.get_temp::<bool>(done).is_some()) {
        return false;
    }
    ctx.data_mut(|d| d.insert_temp(done, true));
    true
}

/// Debug builds only: remembers where a message is drawn, for `inject`.
pub(crate) fn note_message(ctx: &egui::Context, answer: bool, rect: egui::Rect) {
    if cfg!(debug_assertions) && std::env::var_os("MIMIR_SHOT_OPEN").is_some() {
        ctx.data_mut(|d| d.insert_temp(egui::Id::unique(("shot-message", answer)), rect));
    }
}

/// Debug builds only: remembers where the first chat row is drawn.
pub(crate) fn note_chat_row(ctx: &egui::Context, rect: egui::Rect) {
    if cfg!(debug_assertions) && std::env::var_os("MIMIR_SHOT_OPEN").is_some() {
        let id = egui::Id::unique("shot-chat-row");
        if ctx.data(|d| d.get_temp::<egui::Rect>(id)).is_none() {
            ctx.data_mut(|d| d.insert_temp(id, rect));
        }
    }
}

/// Debug builds only, before each frame: moves the pointer the way a hand
/// would (see MIMIR_SHOT_OPEN=message-menu and left-menu above).
pub(crate) fn inject(ctx: &egui::Context, raw: &mut egui::RawInput) {
    if !cfg!(debug_assertions) {
        return;
    }
    let Ok(what) = std::env::var("MIMIR_SHOT_OPEN") else {
        return;
    };
    let t = raw.time.unwrap_or(0.0);
    let step = |n: u32| {
        let id = egui::Id::unique(("shot-step", n));
        ctx.data_mut(|d| {
            d.get_temp::<bool>(id)
                .is_none()
                .then(|| d.insert_temp(id, true))
        })
        .is_some()
    };
    let button = |pos: egui::Pos2, b, pressed| egui::Event::PointerButton {
        pos,
        button: b,
        pressed,
        modifiers: egui::Modifiers::NONE,
    };
    let (p, s) = (egui::PointerButton::Primary, egui::PointerButton::Secondary);
    match what.as_str() {
        "message-menu" => {
            let Some(r) =
                ctx.data(|d| d.get_temp::<egui::Rect>(egui::Id::unique(("shot-message", false))))
            else {
                return;
            };
            let a = egui::pos2(r.left() + 12.0, r.center().y);
            let b = egui::pos2(r.left() + r.width() * 0.5, r.center().y);
            if t > 6.0 && step(1) {
                raw.events.push(egui::Event::PointerMoved(a));
            } else if t > 6.3 && step(2) {
                raw.events.push(button(a, p, true));
            } else if t > 6.6 && step(3) {
                raw.events.push(egui::Event::PointerMoved(b));
            } else if t > 6.9 && step(4) {
                raw.events.push(button(b, p, false));
            } else if t > 7.6 && step(5) {
                raw.events.push(button(b, s, true));
            } else if t > 7.8 && step(6) {
                raw.events.push(button(b, s, false));
            }
        }
        "left-menu" => {
            let Some(r) = ctx.data(|d| d.get_temp::<egui::Rect>(egui::Id::unique("shot-chat-row")))
            else {
                return;
            };
            let a = r.center();
            if t > 5.0 && step(1) {
                raw.events.push(egui::Event::PointerMoved(a));
            } else if t > 5.5 && step(2) {
                raw.events.push(button(a, s, true));
            } else if t > 5.7 && step(3) {
                raw.events.push(button(a, s, false));
            }
        }
        _ => {}
    }
}
