//! A client's (window's or phone's) event queue: bounded, so a stuck client
//! never grows memory (PCS-21, PCR-08). When it fills, events are dropped
//! and the client is told to resync (fetch the whole picture again) as soon
//! as there is room.

use std::sync::mpsc::{Receiver, RecvTimeoutError, SyncSender, TrySendError, sync_channel};
use std::time::Duration;

/// Events a client's writer takes in.
#[derive(Debug, Clone, PartialEq)]
pub enum Out<T> {
    Event(T),
    /// Events were dropped: fetch everything again.
    Resync,
}

/// What became of a pushed event.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Pushed {
    Sent,
    /// Dropped; a resync is on its way.
    Dropped,
    /// The client is gone.
    Gone,
}

/// The hub's end of a client's queue.
pub struct ClientQueue<T> {
    tx: SyncSender<Out<T>>,
    overflowed: bool,
}

/// The default queue length.
pub const QUEUE: usize = 1024;

/// A queue of `cap` events.
pub fn client_queue<T>(cap: usize) -> (ClientQueue<T>, Receiver<Out<T>>) {
    let (tx, rx) = sync_channel(cap.max(2));
    (
        ClientQueue {
            tx,
            overflowed: false,
        },
        rx,
    )
}

impl<T> ClientQueue<T> {
    /// Pushes an event; never waits.
    pub fn push(&mut self, ev: T) -> Pushed {
        let mut resync_sent = false;
        if self.overflowed {
            match self.tx.try_send(Out::Resync) {
                Ok(()) => {
                    self.overflowed = false;
                    resync_sent = true;
                }
                Err(TrySendError::Full(_)) => return Pushed::Dropped,
                Err(TrySendError::Disconnected(_)) => return Pushed::Gone,
            }
        }
        match self.tx.try_send(Out::Event(ev)) {
            Ok(()) => Pushed::Sent,
            // Just after a resync the client fetches everything when it
            // reads it, this event included.
            Err(TrySendError::Full(_)) if resync_sent => Pushed::Dropped,
            Err(TrySendError::Full(_)) => {
                self.overflowed = true;
                Pushed::Dropped
            }
            Err(TrySendError::Disconnected(_)) => Pushed::Gone,
        }
    }

    /// Whether a resync is still owed.
    pub fn owes_resync(&self) -> bool {
        self.overflowed
    }
}

/// The client's writer thread: hands each event to `write` until it says
/// false or the hub drops the queue.
pub fn spawn_writer<T: Send + 'static>(
    name: &str,
    rx: Receiver<Out<T>>,
    mut write: impl FnMut(Out<T>) -> bool + Send + 'static,
) -> std::io::Result<std::thread::JoinHandle<()>> {
    std::thread::Builder::new()
        .name(format!("client-writer-{name}"))
        .spawn(move || {
            loop {
                match rx.recv_timeout(Duration::from_secs(3600)) {
                    Ok(o) => {
                        if !write(o) {
                            return;
                        }
                    }
                    Err(RecvTimeoutError::Timeout) => {}
                    Err(RecvTimeoutError::Disconnected) => return,
                }
            }
        })
}
