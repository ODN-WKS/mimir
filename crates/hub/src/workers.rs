//! Bounded worker pools: slow work (a model probe, a Claude call, a web
//! lookup, speech) runs here and comes back to the hub as a message. A
//! full queue says "busy" at once; the hub never waits for a worker.

use std::sync::mpsc::{Receiver, RecvTimeoutError, Sender, SyncSender, TrySendError, sync_channel};
use std::sync::{Arc, Mutex};
use std::time::Duration;

/// The pools the hub runs, with their threads and queue lengths.
pub struct Sizes;

impl Sizes {
    /// Ollama probe, model load and unload: one at a time.
    pub const MODEL_MANAGER: (usize, usize) = (1, 8);
    /// Claude and model answers.
    pub const TALK_WORKER: (usize, usize) = (1, 16);
    /// Web lookups: four at once.
    pub const LOOKUP_POOL: (usize, usize) = (4, 32);
    /// Text to speech and speech to text.
    pub const VOICE_WORKER: (usize, usize) = (1, 8);
}

/// A job the pool had no room for.
#[derive(Debug)]
pub struct Busy<J>(pub J);

/// A pool of `threads` threads with a queue of `queue` jobs.
pub struct Pool<J> {
    name: &'static str,
    tx: SyncSender<J>,
}

impl<J: Send + 'static> Pool<J> {
    /// Starts the pool: each job's result goes to `hub` as a message made
    /// by `work`.
    pub fn spawn<M: Send + 'static>(
        name: &'static str,
        (threads, queue): (usize, usize),
        hub: Sender<M>,
        work: impl Fn(J) -> M + Send + Sync + 'static,
    ) -> Pool<J> {
        let (tx, rx) = sync_channel::<J>(queue);
        let rx = Arc::new(Mutex::new(rx));
        let work = Arc::new(work);
        for i in 0..threads.max(1) {
            let (rx, hub, work) = (rx.clone(), hub.clone(), work.clone());
            let _ = std::thread::Builder::new()
                .name(format!("{name}-{i}"))
                .spawn(move || worker(&rx, &hub, &*work));
        }
        Pool { name, tx }
    }

    /// Queues a job; `Busy` back at once when the queue is full.
    pub fn submit(&self, job: J) -> Result<(), Busy<J>> {
        self.tx.try_send(job).map_err(|e| match e {
            TrySendError::Full(j) | TrySendError::Disconnected(j) => Busy(j),
        })
    }

    pub fn name(&self) -> &'static str {
        self.name
    }
}

fn worker<J, M>(rx: &Mutex<Receiver<J>>, hub: &Sender<M>, work: &dyn Fn(J) -> M) {
    loop {
        // One idle worker waits on the queue at a time; the others wait
        // for it to take a job. The long timeout is only so a dropped pool
        // is noticed.
        let job = {
            let q = rx.lock().unwrap_or_else(|e| e.into_inner());
            q.recv_timeout(Duration::from_secs(3600))
        };
        match job {
            Ok(j) => {
                if hub.send(work(j)).is_err() {
                    return;
                }
            }
            Err(RecvTimeoutError::Timeout) => {}
            Err(RecvTimeoutError::Disconnected) => return,
        }
    }
}
