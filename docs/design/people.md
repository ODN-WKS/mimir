# People: one PC, several phones, each person's chats apart

Written for R13 (QA2 finding 8). In plain words: a phone belongs to a person.
The PC remembers whose, and every list, stream and change is filtered by that
on the PC, never by anything the phone says.

## Data model

- **Person.** The owner is always the person `owner`. Anyone else has an id
  (12 letters and digits, made on the PC), a first name, and a switch
  "Can use your online plan" (off until the owner turns it on).
  Kept in `link/people.json` beside `phones.json`.
- **Phone.** `phones.json` entries gain `person` (default `owner`). The person
  is set once, at the PC, in "Whose phone is this?": **Mine** (owner) or
  **Someone else's** plus a first name (same name = same person, so Ana's phone
  and tablet share). A phone paired before this change has no `person` and so
  is the owner's (the serde default; test `sec13`).
- **Chats.** `TalkStore` is keyed by (person, chat id). The owner's files stay
  where they were (`talk/<id>.json`, so nothing moves on upgrade); another
  person's are in `talk/people/<person>/`. A chat id means a chat only for its
  own person: the same id under another person is another chat, so asking by
  someone else's id is simply "not found".
- **Per-person settings** (`people-settings.json`, next to `settings.json`):
  who answers (free or online), the Claude model, ground rules. The PC
  window's own settings are the owner's. The default is free for everyone.
- **Not per person:** projects, tasks, needs-you cards, updates, usage, the
  PC's own settings, the list of phones. These are the owner's.

## How a call is filtered

1. The link tells the service which person a connection is, from the PC's own
   record of the phone's key (`LinkEvent::Connected.person`). The window is
   the owner.
2. **Person gate** (`Service::person_gate`, `people::other_may`), before
   anything else sees a call: someone other than the owner may only make
   Hello, Sync, Talk (always as a plain chat, never filed as project work),
   TalkStop, TalkTurn, ChatPut, ChatsSince, SetGroundRules, SetEngines,
   Models, WebSearch, Lookup and Listen. The lists a phone reads on opening
   (Projects, Inbox, Updates, Ideas) are answered empty; everything else is
   refused with one plain line. Window-only calls (pairing, Phones, online
   switch, rename, remove) are refused for every phone as before.
3. Chat calls use the person: `ChatPut`, `ChatsSince`, the answer written
   for a `Talk` and its saved turns all go to `(person, chat)`.
4. **Events.** The general broadcast gives someone other than the owner only
   Welcome, Snapshot, Ready, replies and Labs test notices
   (`Event::for_other_people`): never tasks, needs-you, updates, usage,
   problems or the truth. A chat's pieces, answer and changes are sent by
   `broadcast_chat` to the devices of that chat's person only.
5. **Answers.** Each answer id is tied to the person who asked; `TalkStop`
   and `TalkTurn` on another person's answer do nothing / say "unknown".
6. **Online answers** use the owner's plan: for someone else they run only
   while the owner's switch for that person is on, checked each time an
   answer starts; turning it off puts them back on the free model at once.
7. **The Labs check** chat (`labs-check…`) is answered but never kept, for
   anyone.
8. **Removing.** Removing someone's last phone, or "Remove person", deletes
   their chats (memory and files), their settings and stops their answers.
   Pairing the same phone again starts afresh: the old connection is closed
   and a new person has no history. The phone clears what it kept of another
   person when its Welcome names a different person (feature `people`).

## Protocol

Smallest addition, behind the feature name `people`: `Welcome.person`
(empty for the window and for older PCs), `ConfirmPair.person`, and window-only
calls `PersonOnline`, `RenamePerson`, `RemovePerson`. An older phone on a new
PC is the owner's by the record (safe default). A new phone on an older PC sees
no `people` feature and behaves as today.

## Shared chats later (not now)

A shared chat would be one chat with several person ids instead of one: the
store key becomes the chat id with a list of people, `since_for` and
`get_for` match any of them, and `broadcast_chat` goes to each of them.
Nothing else here has to change; the filters already ask "is this person in
the chat" in one place.

## Proofs

`crates/ui/tests/phone_people.rs`, checks `sec6` to `sec16`, each with a patch in
`checks/proofs/` that removes its filter and must make it fail.
