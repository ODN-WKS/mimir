//! Mímir's hub: one thread owns the chat world and handles messages, each
//! within 50 ms (the [`watchdog`]); slow work runs on bounded worker pools
//! ([`workers`]) and comes back as messages; clients get bounded queues
//! that ask for a resync when they overflow ([`client`]).
//!
//! Workers: StoreWriter (`mimir_store::Writer`), ModelManager, TalkWorker,
//! LookupPool (4 threads), VoiceWorker, and a ClientWriter per client.

pub mod client;
pub mod hub;
pub mod oneshot;
pub mod watchdog;
pub mod workers;

pub use hub::{Backends, ChatHub, Handle, HubEvent, Msg, Stats, World, run, start};
