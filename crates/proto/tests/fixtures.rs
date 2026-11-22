//! Writes one example of every event a phone reads and every call it makes
//! to `tests/fixtures/`, in the exact JSON the link carries. The phone
//! app's unit test (`WireTest.kt`) decodes the events and compares its own
//! calls with these, so the two sides can't drift unnoticed. The files are
//! committed: a change to the shapes shows in the diff.

use mimir_proto::*;

fn header() -> TaskHeader {
    TaskHeader {
        id: "1794-1-1".into(),
        title: "Make Allow respond faster".into(),
        title_by_owner: false,
        project: "/home/me/Projects/Garden-Planner".into(),
        origin: None,
        from_phone: None,
        copy_branch: None,
        session_id: Some("s".into()),
        status: Status::NeedsYou,
        mode: Mode::Ask,
        model: Some("auto".into()),
        auto_choice: None,
        effort: None,
        plan: vec![],
        plan_doc: None,
        files_changed: vec![],
        cost_usd: 0.0,
        weekly: None,
        context_used: 0,
        context_window: 0,
        created_unix: 1_794_000_000,
        brief: None,
        checkpoints: vec![],
        redo: false,
        question_dismissed: None,
        archived: false,
        incognito: false,
        entry_count: 3,
        running: true,
        busy: true,
        asks: vec![serde_json::json!({
            "task": "1794-1-1",
            "tool_name": "Edit",
            "tool_input": {"file_path": "crates/core/src/remote.rs", "old_string": "a", "new_string": "b"},
            "permission_suggestions": []
        })],
        queued: None,
        ran_out: None,
        placed: None,
        placed_why: None,
        active_unix: None,
        live_elsewhere: false,
    }
}

fn turn(id: &str, user: bool, text: &str, ran: Option<Ran>, version: u32, seq: u64) -> ChatTurn {
    ChatTurn {
        id: id.into(),
        user,
        text: text.into(),
        sources: if user {
            vec![]
        } else {
            vec![ChatSource {
                site: "open-meteo.com".into(),
                url: "https://open-meteo.com/".into(),
                words: "Oslo: 4 °C, light rain".into(),
                results: false,
                via: "your PC".into(),
            }]
        },
        ran,
        model_words: ran.map(|_| "Qwen 3.6 · Your PC".into()),
        at: 1_794_000_000_000,
        version,
        seq,
        gone: false,
        switch_note: None,
        pictures: vec![],
        files: vec![],
    }
}

fn truth() -> Truth {
    Truth {
        run_id: "r1".into(),
        rev: 7,
        as_of: 1_794_000_500,
        pc: TruthPc {
            name: "Your PC".into(),
            level: PcLevel::Background,
            line: "1 task working".into(),
        },
        tasks: vec![TruthTask {
            id: "1794-1-1".into(),
            project: "/home/me/Projects/Garden-Planner".into(),
            title: "Make Allow respond faster".into(),
            state: TaskState::Working,
            background: false,
            since: Some(1_794_000_100),
            from_phone: true,
        }],
        counts: TruthCounts {
            working: 1,
            needs_you: 0,
        },
        projects: vec![TruthProject {
            path: "/home/me/Projects/Garden-Planner".into(),
            name: "Garden-Planner".into(),
            last_activity: Some(1_794_000_100),
            working: 1,
            needs_you: 0,
            checks: None,
        }],
        engine: TruthEngine {
            chats_where: "Your PC".into(),
            chats_model_words: "Chats run on your PC's graphics card · about 40 words a second"
                .into(),
            answering: false,
            paid_on: true,
        },
        usage: Some(TruthUsage {
            weekly: Some(WeeklyUse {
                used: 62.0,
                resets_at: 1_794_400_000,
            }),
            five_hour: None,
            as_of: Some(1_794_000_400),
        }),
    }
}

#[test]
fn write_the_examples() {
    let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures");
    std::fs::create_dir_all(&dir).unwrap();
    let step = Step {
        tool_use_id: "t1".into(),
        tool: "Bash".into(),
        what: "Ran a command: cargo build".into(),
        place: Place::ThisPc,
        started_ms: 0,
        took_ms: Some(1200),
        ok: Some(true),
        refused: false,
        stopped: false,
        detail: StepDetail::default(),
    };
    let events = vec![
        Event::Welcome {
            proto: PROTO,
            pc_name: "Gaming PC".into(),
            pc_version: "0.17".into(),
            min_app: String::new(),
            run_id: "r1".into(),
            features: vec!["search".into(), "lookup".into()],
            person: String::new(),
        },
        Event::Snapshot {
            run_id: "r1".into(),
        },
        Event::Ready,
        Event::Task {
            header: Box::new(header()),
        },
        Event::Removed {
            task: "1794-1-1".into(),
        },
        Event::Entries {
            task: "1794-1-1".into(),
            from: 0,
            entries: vec![
                Entry::You("Make Allow faster".into()),
                Entry::Step(step),
                Entry::Claude("Done: 0.2 s now.".into()),
                Entry::Note("You stopped this task.".into()),
            ],
        },
        Event::Live {
            task: "1794-1-1".into(),
            text: "Looking at".into(),
        },
        Event::Problem {
            text: "Claude Code isn't signed in.".into(),
        },
        Event::Reply {
            call_id: 7,
            result: Ok(serde_json::json!("1794-1-2")),
        },
        Event::Reply {
            call_id: 8,
            result: Err("A phone can't do that.".into()),
        },
        Event::TestNotice {
            name: "Breaker".into(),
            what: "Break test".into(),
            until: "7:15 PM".into(),
        },
        Event::TestNoticeOff,
        Event::Updates {
            cards: vec![
                UpdateCard {
                    id: "u1794000100-ab12".into(),
                    project: "/home/me/Projects/Garden-Planner".into(),
                    text: "Allow now reaches Claude in 0.2 s.".into(),
                    time: 1_794_000_100,
                    task: Some("1794-1-1".into()),
                    audio: Some("u1794000100-ab12.ogg".into()),
                    audio_note: None,
                },
                UpdateCard {
                    id: "u1794000000-cd34".into(),
                    project: String::new(),
                    text: "Six local models tested.".into(),
                    time: 1_794_000_000,
                    task: None,
                    audio: None,
                    audio_note: Some("Voice not downloaded on the PC".into()),
                },
            ],
        },
        Event::NeedsYou {
            items: vec![
                NeedsYouItem {
                    ask_id: "perm:1794-1-1:0a1b2c3d4e5f".into(),
                    kind: NeedsKind::Permission,
                    project: "/home/me/Projects/Garden-Planner".into(),
                    task: Some("1794-1-1".into()),
                    title: "Make Allow respond faster".into(),
                    text: "Change a file: crates/core/src/remote.rs".into(),
                    preview: vec!["-a".into(), "+b".into()],
                    choices: vec![],
                    serious: true,
                    created: 1_794_000_000,
                    link: None,
                },
                NeedsYouItem {
                    ask_id: "q:1794-1-2:7".into(),
                    kind: NeedsKind::Question,
                    project: "/home/me/Projects/Garden-Planner".into(),
                    task: Some("1794-1-2".into()),
                    title: "Release planning".into(),
                    text: "Release this as 0.16, or keep it for the next batch?".into(),
                    preview: vec![],
                    choices: vec!["Release as 0.16".into(), "Keep for later".into()],
                    serious: true,
                    created: 1_794_000_100,
                    link: None,
                },
                NeedsYouItem {
                    ask_id: "ask:1a2b3c4d5e6f".into(),
                    kind: NeedsKind::CheckFix,
                    project: "/home/me/Projects/Budgetapp".into(),
                    task: None,
                    title: "Check a fix".into(),
                    text: "Open Budgetapp from the app menu: does it start?".into(),
                    preview: vec![],
                    choices: vec!["It works".into(), "Still broken".into()],
                    serious: false,
                    created: 1_794_000_200,
                    link: Some("https://example.com/notes".into()),
                },
                NeedsYouItem {
                    ask_id: "check:abcdef123456".into(),
                    kind: NeedsKind::CheckFailing,
                    project: "/home/me/Projects/Tune-Sorter".into(),
                    task: None,
                    title: "A core check started failing".into(),
                    text: "The core check \"Calls on cell signal connect.\" started failing at bbbb222.".into(),
                    preview: vec![],
                    choices: vec!["Ask to fix it".into()],
                    serious: true,
                    created: 1_794_000_300,
                    link: None,
                },
            ],
        },
        Event::Reply {
            call_id: 9,
            result: Ok(serde_json::to_value(Filed::Idea {
                project: "/home/me/Projects/Recipe-Box".into(),
                idea: "idea:0a0b0c0d0e0f".into(),
            })
            .unwrap()),
        },
        Event::Reply {
            call_id: 10,
            result: Ok(serde_json::to_value(Filed::Inbox {
                inbox_id: "inbox:0a0b0c0d0e0f".into(),
                candidates: vec!["/home/me/Projects/Garden-Planner".into(), "/home/me/p".into()],
            })
            .unwrap()),
        },
        Event::Reply {
            call_id: 11,
            result: Ok(serde_json::to_value(Filed::Status {
                reply: vec!["1 thing needs you.".into(), "Everything else is passing or idle.".into()],
            })
            .unwrap()),
        },
        Event::Reply {
            call_id: 12,
            result: Ok(serde_json::to_value(StatusPage {
                projects: vec![StatusFacts {
                    project: "/home/me/Projects/Tune-Sorter".into(),
                    name: "Tune-Sorter".into(),
                    version: Some("1.53".into()),
                    checks: Some(CheckFacts {
                        passing: 4,
                        total: 5,
                        failing: vec![("CF-3".into(), "Calls on cell signal connect.".into())],
                        unreliable: vec![],
                        could_not_run: vec![],
                        features: vec![
                            ("CF-1".into(), "Messages arrive in order.".into(), true),
                            ("CF-3".into(), "Calls on cell signal connect.".into(), false),
                        ],
                        commit: Some("bbbb222".into()),
                        when: Some(1_794_000_000),
                        failing_since: Some(1_793_990_000),
                    }),
                    tasks_running: 2,
                    needs_you: 1,
                    uncommitted: Some(3),
                    unpushed: Some(1),
                    last_activity: Some(1_794_000_000),
                    line: "1 of 5 failing: calls on cell signal connect · needs you: 1".into(),
                }],
                weekly: Some(WeeklyUse { used: 0.62, resets_at: 1_794_400_000 }),
                five_hour: None,
                reply: vec!["1 thing needs you.".into()],
                data_time: 1_794_000_500,
                pc_line: None,
            })
            .unwrap()),
        },
        Event::TalkPiece {
            chat_id: "c-1".into(),
            turn_id: "t-1.a".into(),
            n: 0,
            text: "Oslo".into(),
            thinking: false,
        },
        Event::TalkPiece {
            chat_id: "c-1".into(),
            turn_id: "t-1.a".into(),
            n: 1,
            text: "The owner asks about".into(),
            thinking: true,
        },
        Event::TalkDone {
            chat_id: "c-1".into(),
            turn_id: "t-1.a".into(),
            result: Ok(turn("t-1.a", false, "Oslo is the capital of Norway.", Some(Ran::Pc), 1, 41)),
        },
        Event::TalkDone {
            chat_id: "c-1".into(),
            turn_id: "t-2.a".into(),
            result: Err("Your PC's model stopped.".into()),
        },
        Event::ChatChanged {
            chat: ChatRecord {
                id: "c-1".into(),
                name: None,
                at: 1_794_000_000_000,
                seq: 41,
                turns: vec![
                    turn("t-1", true, "What's the capital of Norway?", None, 1, 40),
                    turn("t-1.a", false, "Oslo is the capital of Norway.", Some(Ran::Pc), 1, 41),
                ],
                deleted: None,
            },
        },
        Event::ChatChanged {
            chat: ChatRecord {
                id: "c-2".into(),
                name: None,
                at: 1_794_000_000_000,
                seq: 42,
                turns: vec![],
                deleted: Some(1_794_000_100_000),
            },
        },
        Event::Truth {
            truth: Box::new(truth()),
        },
        Event::Reply {
            call_id: 20,
            result: Ok(serde_json::to_value(Filed::Talk {
                answer: "t-1.a".into(),
                ran: Ran::Pc,
                model_words: "Qwen 3.6 · Your PC".into(),
                waiting: Some("Waking your PC's model…".into()),
                files_read: vec![FileNote {
                    name: "Lease.pdf".into(),
                    note: "Read pages 1 to 40 of 120".into(),
                }],
            })
            .unwrap()),
        },
        Event::Reply {
            call_id: 21,
            result: Ok(serde_json::to_value(Filed::OnPhone {
                why: "Your PC is busy; this phone answers instead.".into(),
            })
            .unwrap()),
        },
        Event::Reply {
            call_id: 22,
            result: Ok(serde_json::to_value(TalkState::Answering {
                text: "Oslo".into(),
            })
            .unwrap()),
        },
        Event::Reply {
            call_id: 23,
            result: Ok(serde_json::to_value(TalkState::Done {
                turn: turn("t-1.a", false, "Oslo is the capital of Norway.", Some(Ran::Pc), 1, 41),
            })
            .unwrap()),
        },
        Event::Reply {
            call_id: 24,
            result: Ok(serde_json::to_value(TalkState::Failed {
                why: "Stopped.".into(),
            })
            .unwrap()),
        },
        Event::Reply {
            call_id: 25,
            result: Ok(serde_json::to_value(TalkState::Unknown).unwrap()),
        },
        Event::Reply {
            call_id: 26,
            result: Ok(serde_json::to_value(ChatsPage {
                seq: 42,
                chats: vec![ChatRecord {
                    id: "c-1".into(),
                    name: Some("Norway".into()),
                    at: 1_794_000_000_000,
                    seq: 41,
                    turns: vec![turn("t-1.a", false, "Oslo.", Some(Ran::Paid), 2, 41)],
                    deleted: None,
                }],
                more: false,
            })
            .unwrap()),
        },
        Event::Reply {
            call_id: 27,
            result: Ok(serde_json::to_value(ChatPutDone {
                seq: 43,
                deleted: false,
            })
            .unwrap()),
        },
        Event::Reply {
            call_id: 28,
            result: Ok(serde_json::to_value(GroundRules {
                text: "Be brief.".into(),
                at: 1_794_000_000_000,
            })
            .unwrap()),
        },
        Event::ModelsChanged,
    ];
    let lines: Vec<String> = events
        .into_iter()
        .enumerate()
        .map(|(i, event)| {
            assert!(event.for_phone(), "{event:?} isn't a phone event");
            serde_json::to_string(&EventMsg {
                seq: i as u64 + 1,
                event,
            })
            .unwrap()
        })
        .collect();
    std::fs::write(dir.join("events.jsonl"), lines.join("\n") + "\n").unwrap();

    let calls = vec![
        Call::Hello {
            proto: PROTO,
            app_version: "0.17".into(),
            client_name: "Samsung EX-100".into(),
            env: vec![],
            features: vec![],
        },
        Call::Hello {
            proto: PROTO,
            app_version: "0.22".into(),
            client_name: "Samsung EX-100".into(),
            env: vec![],
            features: vec![
                "search".into(),
                "lookup".into(),
                "talk".into(),
                "chat_sync".into(),
                "truth".into(),
                "models_changed".into(),
            ],
        },
        Call::Sync { since: 12 },
        Call::Projects,
        Call::NewTask {
            ground_rules: None,
            project: "/home/me/p".into(),
            text: "Fix it".into(),
            pictures: vec![],
            files: vec![],
            mode: Mode::Ask,
            model: None,
            effort: None,
            separate_copy: false,
            msg_id: Some("m-1".into()),
        },
        Call::Send {
            task: "t".into(),
            text: "And this".into(),
            pictures: vec![],
            files: vec![],
            msg_id: Some("m-2".into()),
        },
        Call::Stop { task: "t".into() },
        Call::Chat {
            ground_rules: None,
            msg_id: "m-3".into(),
            text: "Recipes: idea - a dark mode switch".into(),
        },
        Call::AnswerItem {
            ask_id: "perm:t:0a1b".into(),
            answer: ItemAnswer::Allow,
        },
        Call::AnswerItem {
            ask_id: "perm:t:0a1b".into(),
            answer: ItemAnswer::AllowFileThisTask,
        },
        Call::AnswerItem {
            ask_id: "q:t:7".into(),
            answer: ItemAnswer::Option(1),
        },
        Call::AnswerItem {
            ask_id: "q:t:7".into(),
            answer: ItemAnswer::Text("Release it".into()),
        },
        Call::AnswerItem {
            ask_id: "ask:1".into(),
            answer: ItemAnswer::Works,
        },
        Call::AnswerItem {
            ask_id: "check:1".into(),
            answer: ItemAnswer::AskClaudeToFix,
        },
        Call::Status,
        Call::Inbox,
        Call::Ideas {
            project: "/home/me/p".into(),
        },
        Call::FileInbox {
            inbox_id: "inbox:1".into(),
            project: "/home/me/p".into(),
        },
        Call::MoveTask {
            task: "t".into(),
            project: "/home/me/p".into(),
        },
        Call::MoveTo {
            task: "t".into(),
            project: "/home/me/p".into(),
        },
        Call::UndoIdea {
            project: "/home/me/p".into(),
            idea: "idea:1".into(),
        },
        Call::Updates,
        Call::GetFile {
            name: "u1794000000-ab12.ogg".into(),
        },
        Call::Speak {
            task: "t".into(),
            entry: 3,
        },
        Call::Listen {
            wav_base64: "UklGRg==".into(),
        },
        Call::Models,
        Call::SetFreeChoice {
            switch_to_free: Some(true),
            free_model: None,
        },
        Call::ContinueOnClaude { task: "t".into() },
        Call::NewIncognito {
            ground_rules: None,
            project: "/home/me/p".into(),
            text: "Read the tax letter".into(),
            model: "ollama:qwen3.6:35b-a3b-coding".into(),
            mode: Mode::Ask,
        },
        Call::EndIncognito { task: "t".into() },
        Call::SetWeeklyCheck { on: false },
        Call::PhoneSettings,
        Call::SetPcName {
            name: "Desk PC".into(),
        },
        Call::SetRelays { on: false },
        Call::AlwaysAllowed { task: "t".into() },
        Call::StopAllowing {
            task: "t".into(),
            file: "/home/me/p/notes.txt".into(),
        },
        Call::WebSearch {
            query: "weather Portland".into(),
            max: 5,
        },
        Call::Lookup {
            url: "https://api.open-meteo.com/v1/forecast?latitude=39.73&longitude=-121.84".into(),
        },
        Call::Talk {
            chat_id: "c-1".into(),
            turn_id: "t-1".into(),
            turns: vec![turn(
                "t-1",
                true,
                "What's the capital of Norway?",
                None,
                1,
                0,
            )],
            lookup: None,
            sources: vec![],
            effort: Some("low".into()),
            ground_rules: Some("Be brief.".into()),
            task: None,
            chat_only: false,
        },
        Call::Talk {
            chat_id: "c-1".into(),
            turn_id: "t-3".into(),
            turns: vec![turn("t-3", true, "And when was it released?", None, 1, 0)],
            lookup: Some("[Information the app fetched from open-meteo.com]".into()),
            sources: vec![ChatSource {
                site: "open-meteo.com".into(),
                url: "https://open-meteo.com/".into(),
                words: String::new(),
                results: false,
                via: String::new(),
            }],
            effort: None,
            ground_rules: None,
            task: Some("1794-1-1".into()),
            chat_only: false,
        },
        Call::Talk {
            chat_id: "c-1".into(),
            turn_id: "t-4".into(),
            turns: vec![turn(
                "t-4",
                true,
                "Is Recipes a good name for a recipe app?",
                None,
                1,
                0,
            )],
            lookup: None,
            sources: vec![],
            effort: None,
            ground_rules: None,
            task: None,
            chat_only: true,
        },
        Call::Talk {
            chat_id: "c-1".into(),
            turn_id: "t-5".into(),
            turns: vec![{
                let mut t = turn("t-5", true, "What does this show?", None, 1, 0);
                t.pictures = vec![Picture {
                    media_type: "image/jpeg".into(),
                    base64: "/9j/4AAQ".into(),
                }];
                t
            }],
            lookup: None,
            sources: vec![],
            effort: None,
            ground_rules: None,
            task: None,
            chat_only: true,
        },
        Call::Talk {
            chat_id: "c-1".into(),
            turn_id: "t-6".into(),
            turns: vec![{
                let mut t = turn("t-6", true, "What is this about?", None, 1, 0);
                t.files = vec![SentFile {
                    name: "Lease.pdf".into(),
                    media_type: "application/pdf".into(),
                    base64: "JVBERi0x".into(),
                }];
                t
            }],
            lookup: None,
            sources: vec![],
            effort: None,
            ground_rules: None,
            task: None,
            chat_only: true,
        },
        Call::TalkStop {
            chat_id: "c-1".into(),
            turn_id: "t-1".into(),
        },
        Call::TalkTurn {
            chat_id: "c-1".into(),
            turn_id: "t-1".into(),
        },
        Call::ChatPut {
            chat_id: "c-1".into(),
            name: None,
            turns: vec![
                turn("t-1", true, "What's the capital of Norway?", None, 1, 0),
                turn("t-1.a", false, "Oslo.", Some(Ran::Phone), 2, 0),
                // Cut away by an edit of an earlier message: a tombstone.
                ChatTurn {
                    gone: true,
                    switch_note: None,
                    pictures: vec![],
                    ..turn("t-0", true, "", None, 3, 0)
                },
            ],
            deleted: false,
        },
        Call::ChatPut {
            chat_id: "c-2".into(),
            name: None,
            turns: vec![],
            deleted: true,
        },
        Call::SetEngines {
            chats: Some("paid".into()),
            project: None,
            chat_model: None,
        },
        Call::SetEngines {
            chats: None,
            project: None,
            chat_model: Some("sonnet".into()),
        },
        Call::ChatsSince { seq: 40 },
        Call::SetGroundRules {
            text: "Be brief.".into(),
            at: 1_794_000_000_000,
        },
    ];
    let lines: Vec<String> = calls
        .into_iter()
        .map(|c| {
            assert!(c.phone_may(), "{c:?} isn't a phone call");
            serde_json::to_string(&c).unwrap()
        })
        .collect();
    std::fs::write(dir.join("calls.jsonl"), lines.join("\n") + "\n").unwrap();
    // A task's messages with pictures and files ("task_files"): kept apart
    // so the phone's older call list stays as it was.
    let with_files: Vec<String> = vec![
        Call::NewTask {
            ground_rules: None,
            project: "/home/me/p".into(),
            text: "Use these".into(),
            pictures: vec![Picture {
                media_type: "image/jpeg".into(),
                base64: "/9j/4AAQ".into(),
            }],
            files: vec![SentFile {
                name: "Notes.pdf".into(),
                media_type: "application/pdf".into(),
                base64: "JVBERi0x".into(),
            }],
            mode: Mode::Ask,
            model: None,
            effort: None,
            separate_copy: false,
            msg_id: Some("m-4".into()),
        },
        Call::Send {
            task: "t".into(),
            text: "See the picture and the file".into(),
            pictures: vec![Picture {
                media_type: "image/png".into(),
                base64: "iVBORw0K".into(),
            }],
            files: vec![SentFile {
                name: "Budget.xlsx".into(),
                media_type: String::new(),
                base64: "UEsDBBQ=".into(),
            }],
            msg_id: Some("m-5".into()),
        },
    ]
    .iter()
    .map(|c: &Call| serde_json::to_string(c).unwrap())
    .collect();
    std::fs::write(
        dir.join("task_files_calls.jsonl"),
        with_files.join("\n") + "\n",
    )
    .unwrap();
}
