//! One reply to one request: the hub (or a worker) answers through a
//! [`Reply`]; the asker holds the [`Pending`] and waits off the hub's
//! thread, or polls it. Answering never blocks.

use std::sync::mpsc::{Receiver, RecvTimeoutError, SyncSender, sync_channel};
use std::time::Duration;

pub struct Reply<T>(SyncSender<T>);
pub struct Pending<T>(Receiver<T>);

/// A reply and the place it arrives.
pub fn oneshot<T>() -> (Reply<T>, Pending<T>) {
    let (tx, rx) = sync_channel(1);
    (Reply(tx), Pending(rx))
}

impl<T> Reply<T> {
    /// Answers (dropped when nobody waits any more).
    pub fn send(self, v: T) {
        let _ = self.0.try_send(v);
    }
}

/// Why no answer came.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NoAnswer {
    /// Not within the time given.
    Late,
    /// The hub or worker dropped the request.
    Dropped,
}

impl<T> Pending<T> {
    /// Waits up to `wait` (never call this on the hub's thread).
    pub fn wait(self, wait: Duration) -> Result<T, NoAnswer> {
        self.0.recv_timeout(wait).map_err(|e| match e {
            RecvTimeoutError::Timeout => NoAnswer::Late,
            RecvTimeoutError::Disconnected => NoAnswer::Dropped,
        })
    }

    /// The answer if it has come (a window's "pending" state polls this).
    pub fn take(&self) -> Option<Result<T, NoAnswer>> {
        match self.0.recv_timeout(Duration::ZERO) {
            Ok(v) => Some(Ok(v)),
            Err(RecvTimeoutError::Timeout) => None,
            Err(RecvTimeoutError::Disconnected) => Some(Err(NoAnswer::Dropped)),
        }
    }
}
