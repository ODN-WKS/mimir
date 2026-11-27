//! Right-click menus everywhere in the PC window (redesign R6): a menu
//! with the common things wherever the owner right-clicks. Each check
//! right-clicks a place, reads the menu's rows in order, and does what a
//! row says.

mod common;
use common::*;
use eframe::egui::{self, accesskit::Role};
use egui_kittest::Harness;
use egui_kittest::kittest::{NodeT, Queryable};
use mimir_core::proto::{Call, ChatTurn, Ran};
use mimir_core::store::Store;
use mimir_core::task::{Entry, Status, Task};
use station_ui::{App, labels};

fn home() -> Option<TestHome> {
    let home = TestHome::new()?;
    let project = home.project.display().to_string();
    home.write_settings(serde_json::json!({
        "projects": [project],
        "last_project": project,
        "remote_projects": [],
        "phone_relays": false,
        "details": true,
    }));
    Some(home)
}

fn turn(id: &str, user: bool, text: &str) -> ChatTurn {
    ChatTurn {
        id: id.into(),
        user,
        text: text.into(),
        sources: vec![],
        ran: (!user).then_some(Ran::Pc),
        model_words: None,
        at: 1_794_000_000_000 + if user { 0 } else { 1000 },
        version: 1,
        seq: 0,
        gone: false,
        switch_note: None,
        pictures: vec![],
        files: vec![],
    }
}

/// A chat with one question and its answer, open in the window.
fn open_chat(home: &TestHome, h: &mut Harness<'static, App>, question: &str, answer: &str) {
    let mut st = home.service();
    st.call_wait(Call::ChatPut {
        chat_id: "c1".into(),
        name: None,
        turns: vec![turn("c1.q", true, question), turn("c1.a", false, answer)],
        deleted: false,
    })
    .unwrap();
    let row = format!("Chat: {question}");
    assert!(
        wait_for(h, |h| has_button(h, &row)),
        "the chat didn't show\n{}",
        screen_text(h)
    );
    click(h, &row);
    assert!(wait_for(h, |h| shows(h, answer)), "{}", screen_text(h));
    frames(h);
}

fn frames(h: &mut Harness<'static, App>) {
    for _ in 0..10 {
        h.step();
    }
}

/// Where the menu open now is.
fn popup_rect(h: &Harness<'static, App>) -> egui::Rect {
    h.ctx
        .memory(|m| {
            m.areas()
                .visible_layer_ids()
                .into_iter()
                .filter(|l| l.order == egui::Order::Foreground)
                .filter_map(|l| m.area_rect(l.id))
                .last()
        })
        .expect("no menu is open")
}

/// A menu row: inside the menu and nearly as wide as it (a button that
/// sits under the menu is not one).
fn in_menu(popup: egui::Rect, row: egui::Rect) -> bool {
    popup.expand(1.0).contains_rect(row) && row.width() >= popup.width() - 14.0
}

/// The rows of the menu open now, top to bottom.
fn menu_rows(h: &Harness<'static, App>) -> Vec<String> {
    let popup = popup_rect(h);
    let mut rows: Vec<(f32, String)> = h
        .query_all_by_role(Role::Button)
        .filter(|n| in_menu(popup, n.rect()))
        .filter_map(|n| {
            n.accesskit_node()
                .label()
                .map(|l| (n.rect().top(), l.to_string()))
        })
        .collect();
    rows.sort_by(|a, b| a.0.total_cmp(&b.0));
    rows.into_iter().map(|(_, l)| l).collect()
}

fn assert_rows(h: &Harness<'static, App>, what: &str, want: &[&str]) {
    let got = menu_rows(h);
    assert_eq!(
        got,
        want.iter().map(|s| s.to_string()).collect::<Vec<_>>(),
        "{what}: wrong rows\n{}",
        screen_text(h)
    );
}

/// What a click on a menu row copied (read from that frame's output).
fn click_row(h: &mut Harness<'static, App>, words: &str) -> Vec<String> {
    let popup = popup_rect(h);
    let row = |h: &Harness<'static, App>| {
        h.query_all_by_role_and_label(Role::Button, words)
            .find(|n| in_menu(popup, n.rect()))
            .unwrap_or_else(|| panic!("no row {words:?}\n{}", screen_text(h)))
            .rect()
    };
    h.hover_at(row(h).center());
    frames(h);
    h.query_all_by_role_and_label(Role::Button, words)
        .find(|n| in_menu(popup, n.rect()))
        .unwrap()
        .click();
    // The copy may come a frame later (Cut and Copy in a box are done at
    // the start of the next frame).
    let mut all = vec![];
    for _ in 0..10 {
        h.step();
        all.extend(copied(h));
    }
    all
}

fn copied(h: &Harness<'static, App>) -> Vec<String> {
    h.output()
        .platform_output
        .commands
        .iter()
        .filter_map(|c| match c {
            egui::OutputCommand::CopyText(t) => Some(t.clone()),
            _ => None,
        })
        .collect()
}

fn right_click(h: &mut Harness<'static, App>, pos: egui::Pos2) {
    h.hover_at(pos);
    frames(h);
    for pressed in [true, false] {
        h.event(egui::Event::PointerButton {
            pos,
            button: egui::PointerButton::Secondary,
            pressed,
            modifiers: egui::Modifiers::NONE,
        });
        h.step();
    }
    frames(h);
}

fn right_click_label(h: &mut Harness<'static, App>, text: &str) {
    let at = h
        .query_all_by_label(text)
        .last()
        .unwrap_or_else(|| panic!("no {text:?} on screen\n{}", screen_text(h)))
        .rect()
        .center();
    right_click(h, at);
}

fn close_menu(h: &mut Harness<'static, App>) {
    h.key_press(egui::Key::Escape);
    frames(h);
}

const ANSWER_MENU: &[&str] = &[
    "Copy",
    "Copy whole message",
    "Select all",
    "Quote in my reply",
    "Listen",
    "Try again",
];

#[test]
fn a_message_menu_has_the_common_things_and_copy_takes_the_message() {
    let Some(home) = home() else { return };
    let mut h = home.open();
    open_chat(
        &home,
        &mut h,
        "How do I grow tomatoes",
        "Water them in the evening.",
    );
    // Claude's answer: the full list, "Try again" last.
    right_click_label(&mut h, "Water them in the evening.");
    assert_rows(&h, "answer", ANSWER_MENU);
    assert_eq!(
        click_row(&mut h, "Copy"),
        vec!["Water them in the evening."]
    );
    // The owner's message: "Edit" first, then the same, without "Try again".
    right_click_label(&mut h, "How do I grow tomatoes");
    let mut mine = vec!["Edit"];
    mine.extend_from_slice(&ANSWER_MENU[..5]);
    assert_rows(&h, "owner's message", &mine);
    assert_eq!(
        click_row(&mut h, "Copy whole message"),
        vec!["How do I grow tomatoes"]
    );
    // Quote puts "> text" into the message box.
    right_click_label(&mut h, "How do I grow tomatoes");
    click_row(&mut h, "Quote in my reply");
    assert!(
        wait_for(&mut h, |h| shows(h, "> How do I grow tomatoes")),
        "the quote isn't in the box\n{}",
        screen_text(&h)
    );
    close(h);
}

#[test]
fn select_all_marks_a_message_and_ctrl_c_takes_all_of_it() {
    let Some(home) = home() else { return };
    let mut h = home.open();
    open_chat(
        &home,
        &mut h,
        "What is a tomato",
        "A red fruit that many call a vegetable.",
    );
    right_click_label(&mut h, "A red fruit that many call a vegetable.");
    click_row(&mut h, "Select all");
    h.event(egui::Event::Copy);
    h.step();
    assert_eq!(
        copied(&h),
        vec!["A red fruit that many call a vegetable."],
        "Ctrl C after Select all"
    );
    // Ctrl+A with the pointer over a message does the same.
    close_menu(&mut h);
    let at = h
        .query_all_by_label("What is a tomato")
        .last()
        .unwrap()
        .rect()
        .center();
    h.hover_at(at);
    frames(&mut h);
    h.key_press_modifiers(egui::Modifiers::COMMAND, egui::Key::A);
    frames(&mut h);
    h.event(egui::Event::Copy);
    h.step();
    assert_eq!(
        copied(&h),
        vec!["What is a tomato"],
        "Ctrl A over a message"
    );
    close(h);
}

#[test]
fn copy_takes_only_the_chosen_words() {
    let Some(home) = home() else { return };
    let mut h = home.open();
    open_chat(&home, &mut h, "Tell me about gardens in spring", "Sure.");
    let r = h
        .query_all_by_label("Tell me about gardens in spring")
        .last()
        .unwrap()
        .rect();
    // Drag across the first part of the owner's message.
    let (a, b) = (
        egui::pos2(r.left() + 1.0, r.center().y),
        egui::pos2(r.left() + r.width() * 0.45, r.center().y),
    );
    h.hover_at(a);
    frames(&mut h);
    h.event(egui::Event::PointerButton {
        pos: a,
        button: egui::PointerButton::Primary,
        pressed: true,
        modifiers: egui::Modifiers::NONE,
    });
    h.step();
    h.hover_at(b);
    frames(&mut h);
    h.event(egui::Event::PointerButton {
        pos: b,
        button: egui::PointerButton::Primary,
        pressed: false,
        modifiers: egui::Modifiers::NONE,
    });
    frames(&mut h);
    right_click(&mut h, b);
    let got = click_row(&mut h, "Copy");
    assert_eq!(got.len(), 1, "{got:?}");
    assert!(
        !got[0].is_empty()
            && got[0].len() < "Tell me about gardens in spring".len()
            && "Tell me about gardens in spring".contains(got[0].trim()),
        "Copy took {:?}, not the chosen words",
        got[0]
    );
    close(h);
}

#[test]
fn the_typing_box_menu_cuts_copies_pastes_and_selects_all() {
    let Some(home) = home() else { return };
    let mut h = home.open();
    click(&mut h, labels::NEW_CHAT);
    frames(&mut h);
    type_message(&mut h, "hello world");
    let at = h
        .query_all_by_role(Role::MultilineTextInput)
        .next()
        .unwrap()
        .rect()
        .center();
    right_click(&mut h, at);
    assert_rows(
        &h,
        "typing box",
        &[
            "Cut",
            "Copy",
            "Paste",
            "Paste as plain text",
            "Select all",
            "Undo",
            "Redo",
        ],
    );
    // Select all, then Cut takes everything and puts it on the clipboard.
    click_row(&mut h, "Select all");
    frames(&mut h);
    right_click(&mut h, at);
    let got = click_row(&mut h, "Cut");
    assert!(
        got.contains(&"hello world".to_string()),
        "Cut copied {got:?}"
    );
    frames(&mut h);
    assert!(
        !shows(&h, "hello world"),
        "Cut left the words\n{}",
        screen_text(&h)
    );
    // Paste puts the clipboard's words in.
    h.ctx.data_mut(|d| {
        d.insert_temp(
            egui::Id::unique("test-clipboard"),
            "pasted words".to_string(),
        )
    });
    right_click(&mut h, at);
    click_row(&mut h, "Paste");
    assert!(
        wait_for(&mut h, |h| shows(h, "pasted words")),
        "Paste put nothing in\n{}",
        screen_text(&h)
    );
    // Undo takes the paste back.
    right_click(&mut h, at);
    click_row(&mut h, "Undo");
    frames(&mut h);
    assert!(
        !shows(&h, "pasted words"),
        "Undo kept the paste\n{}",
        screen_text(&h)
    );
    close(h);
}

#[test]
fn a_chat_in_the_left_menu_opens_copies_and_deletes() {
    let Some(home) = home() else { return };
    let mut h = home.open();
    let mut st = home.service();
    st.call_wait(Call::ChatPut {
        chat_id: "c2".into(),
        name: None,
        turns: vec![
            turn("c2.q", true, "Plan the garden"),
            turn("c2.a", false, "Start with beds."),
        ],
        deleted: false,
    })
    .unwrap();
    assert!(wait_for(&mut h, |h| has_button(h, "Chat: Plan the garden")));
    let at = h
        .get_by_role_and_label(Role::Button, "Chat: Plan the garden")
        .rect()
        .center();
    right_click(&mut h, at);
    assert_rows(
        &h,
        "chat",
        &[
            "Open",
            "Copy the whole chat",
            "Rename chat…",
            "Delete chat…",
        ],
    );
    assert_eq!(
        click_row(&mut h, "Copy the whole chat"),
        vec!["You:\nPlan the garden\n\nAnswer:\nStart with beds."]
    );
    right_click(&mut h, at);
    click_row(&mut h, "Open");
    assert!(wait_for(&mut h, |h| shows(h, "Start with beds.")));
    // Delete asks first, and the chat stays until it is confirmed.
    right_click(&mut h, at);
    click_row(&mut h, "Delete chat…");
    assert!(
        wait_for(&mut h, |h| has_button(h, "Delete the chat")),
        "no question before deleting\n{}",
        screen_text(&h)
    );
    assert!(has_button(&h, "Chat: Plan the garden"));
    click(&mut h, "Keep it");
    close(h);
}

#[test]
fn the_delete_key_asks_to_delete_the_open_chat() {
    let Some(home) = home() else { return };
    let mut h = home.open();
    open_chat(&home, &mut h, "Old question", "Old answer.");
    h.key_press(egui::Key::Delete);
    assert!(
        wait_for(&mut h, |h| has_button(h, "Delete the chat")),
        "Delete did not ask\n{}",
        screen_text(&h)
    );
    assert!(has_button(&h, "Chat: Old question"));
    close(h);
}

/// A finished task with a changed file, in the home's store.
fn task_with_file(home: &TestHome) -> String {
    let project = home.project.clone();
    std::fs::write(project.join("notes.txt"), "hi").unwrap();
    let store = Store::new(home.root.join(".local/share/mimir/tasks")).unwrap();
    let mut t = Task::new(
        "t1".into(),
        "Sort the receipts".into(),
        project.display().to_string(),
        0,
    );
    t.entries = vec![
        Entry::You("Sort the receipts".into()),
        Entry::Claude("Done.".into()),
    ];
    t.status = Status::Done;
    t.files_changed = vec!["notes.txt".into()];
    store.save(&t).unwrap();
    home.tripwire();
    let name = project.file_name().unwrap().to_string_lossy().to_string();
    labels::task_row("Sort the receipts", &name)
}

#[test]
fn a_task_and_a_project_in_the_left_menu_have_their_menus() {
    let Some(home) = home() else { return };
    let row = task_with_file(&home);
    let project = home.project.clone();
    let mut h = home.open();
    assert!(wait_for(&mut h, |h| shows(h, &row)));
    let at = h.get_by_label(&row).rect().center();
    right_click(&mut h, at);
    assert_rows(
        &h,
        "task",
        &[
            "Open",
            "Rename…",
            "Pin to top",
            labels::MOVE_TO,
            "Copy the whole chat",
            "Open its folder",
            "Archive",
            "Delete task…",
        ],
    );
    let got = click_row(&mut h, "Copy the whole chat");
    assert!(
        got.len() == 1 && got[0].starts_with("# Sort the receipts"),
        "{got:?}"
    );
    // Delete asks first.
    right_click(&mut h, at);
    click_row(&mut h, "Delete task…");
    assert!(
        wait_for(&mut h, |h| has_button(h, "Delete")),
        "{}",
        screen_text(&h)
    );
    close_menu(&mut h);
    // The project: its menu, ending in the red Remove that keeps the files.
    let name = project.file_name().unwrap().to_string_lossy().to_string();
    let folder = labels::folder_row(&name);
    let at = h.get_by_label(&folder).rect().center();
    right_click(&mut h, at);
    let rows = menu_rows(&h);
    assert_eq!(
        rows.first().map(String::as_str),
        Some("New task here"),
        "{rows:?}"
    );
    assert_eq!(
        rows.last().map(String::as_str),
        Some(labels::REMOVE_FROM_LIST),
        "{rows:?}"
    );
    for want in ["Open the folder", "Rename…", labels::MERGE_INTO] {
        assert!(rows.iter().any(|r| r == want), "no {want:?} in {rows:?}");
    }
    click_row(&mut h, labels::REMOVE_FROM_LIST);
    assert!(
        wait_for(&mut h, |h| shows(h, "Its files stay as they are")),
        "the confirm doesn't say the files stay\n{}",
        screen_text(&h)
    );
    close(h);
}

#[test]
fn the_empty_middle_offers_new_chat_new_task_search_and_paste() {
    let Some(home) = home() else { return };
    let mut h = home.open();
    open_chat(&home, &mut h, "Short question", "Short answer.");
    let screen = h.ctx.content_rect();
    // Well below the messages, inside the middle.
    let at = egui::pos2(screen.center().x, screen.top() + screen.height() * 0.45);
    right_click(&mut h, at);
    assert_rows(
        &h,
        "empty space",
        &[
            "New chat",
            "New task",
            "Search chats",
            "Paste into the message box",
        ],
    );
    click_row(&mut h, "New chat");
    assert!(
        wait_for(&mut h, |h| !shows(h, "Short answer.")),
        "New chat kept the old conversation\n{}",
        screen_text(&h)
    );
    close(h);
}

#[test]
fn a_changed_file_in_the_right_panel_has_its_menu() {
    let Some(home) = home() else { return };
    let row = task_with_file(&home);
    let mut h = home.open();
    assert!(wait_for(&mut h, |h| shows(h, &row)));
    click(&mut h, &row);
    assert!(
        wait_for(&mut h, |h| shows(h, "notes.txt")),
        "{}",
        screen_text(&h)
    );
    let at = h
        .query_all_by_label("notes.txt")
        .last()
        .unwrap()
        .rect()
        .center();
    right_click(&mut h, at);
    assert_rows(
        &h,
        "changed file",
        &["Open file", "Open folder", "Copy path"],
    );
    let got = click_row(&mut h, "Copy path");
    assert_eq!(got.len(), 1, "{got:?}");
    assert!(got[0].ends_with("/notes.txt"), "{got:?}");
    close(h);
}

/// R5d: a chat started on the PC (a task in the Chats folder) is under
/// CHATS, never under PROJECTS, and has the chat menu.
#[test]
fn a_pc_chat_is_under_chats_not_projects_and_has_the_chat_menu() {
    let Some(home) = home() else { return };
    let store = Store::new(home.root.join(".local/share/mimir/tasks")).unwrap();
    let chats = mimir_core::paths::chats_dir();
    std::fs::create_dir_all(&chats).unwrap();
    let mut t = Task::new(
        "pc1".into(),
        "Recipe box ideas".into(),
        chats.display().to_string(),
        0,
    );
    t.entries = vec![
        Entry::You("Recipe box ideas".into()),
        Entry::Claude("Try index cards.".into()),
    ];
    t.status = Status::Done;
    store.save(&t).unwrap();
    home.tripwire();
    let row = labels::task_row("Recipe box ideas", "Chats");
    let mut h = home.open();
    assert!(wait_for(&mut h, |h| shows(h, &row)), "{}", screen_text(&h));
    let text = screen_text(&h);
    let at_chats = text.find("Chats").expect("no CHATS heading");
    let at_projects = text.find("Projects").expect("no Projects heading");
    let at_row = text.find(&row).unwrap();
    assert!(
        at_chats < at_row && at_row < at_projects,
        "the PC chat is not between Chats and Projects\n{text}"
    );
    // Projects holds only the real project: the Chats folder isn't one.
    assert!(
        !text[at_projects..].contains(&row),
        "still listed as a project\n{text}"
    );
    let at = h.get_by_label(&row).rect().center();
    right_click(&mut h, at);
    assert_rows(
        &h,
        "pc chat",
        &["Open", "Copy the whole chat", "Delete chat…"],
    );
    close_menu(&mut h);
    close(h);
}

#[test]
fn a_free_model_chat_has_a_plain_model_name_and_no_free_tag() {
    let Some(home) = home() else { return };
    let store = Store::new(home.root.join(".local/share/mimir/tasks")).unwrap();
    let chats = mimir_core::paths::chats_dir();
    std::fs::create_dir_all(&chats).unwrap();
    let mut t = Task::new(
        "pc2".into(),
        "Garden planner".into(),
        chats.display().to_string(),
        0,
    );
    t.model = Some("ollama:qwen3.6:35b-a3b-coding".into());
    t.entries = vec![
        Entry::You("Garden planner".into()),
        Entry::Claude("Start small.".into()),
    ];
    t.status = Status::Done;
    store.save(&t).unwrap();
    home.tripwire();
    let mut h = home.open();
    assert!(
        wait_for(&mut h, |h| shows(h, "Start small.")),
        "{}",
        screen_text(&h)
    );
    frames(&mut h);
    let text = screen_text(&h);
    assert!(!text.contains("Free model"), "{text}");
    assert!(!text.contains("(free)"), "{text}");
    close(h);
}

const BOX_MENU: &[&str] = &[
    "Cut",
    "Copy",
    "Paste",
    "Paste as plain text",
    "Select all",
    "Undo",
    "Redo",
];

#[test]
fn the_settings_search_and_labs_report_boxes_have_the_typing_menu() {
    let Some(home) = home() else { return };
    let mut h = home.open();
    click(&mut h, "Settings");
    frames(&mut h);
    let at = h
        .query_by_label("Search Settings")
        .expect("no settings search")
        .rect()
        .center();
    right_click(&mut h, at);
    assert_rows(&h, "settings search", BOX_MENU);
    close_menu(&mut h);
    click(&mut h, "Labs");
    assert!(
        wait_for(&mut h, |h| shows(h, "Report a problem")),
        "Labs didn't open\n{}",
        screen_text(&h)
    );
    let at = h
        .query_all_by_role(Role::MultilineTextInput)
        .next()
        .expect("no report box")
        .rect()
        .center();
    right_click(&mut h, at);
    assert_rows(&h, "labs report", BOX_MENU);
    close(h);
}
