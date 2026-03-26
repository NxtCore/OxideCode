use std::sync::Arc;
use tokio::sync::watch;
use tokio::time::{Duration, sleep};
use tokio_util::sync::CancellationToken;

/// Manages debounced, cancellable request lifecycles.
///
/// Each new trigger cancels the previous in-flight request and restarts
/// the debounce timer. This is the core mechanism for keeping autocomplete
/// and NES responsive without flooding the provider.
pub struct Debouncer {
    delay: Duration,
    cancel_tx: watch::Sender<Option<CancellationToken>>,
}

impl Debouncer {
    pub fn new(delay_ms: u64) -> (Self, watch::Receiver<Option<CancellationToken>>) {
        let (tx, rx) = watch::channel(None);
        (
            Self {
                delay: Duration::from_millis(delay_ms),
                cancel_tx: tx,
            },
            rx,
        )
    }

    /// Cancel the previous request and return a new `CancellationToken`
    /// that will become active after the debounce delay.
    ///
    /// The caller should `await` the returned future to know when to fire.
    pub async fn trigger(&self) -> Option<CancellationToken> {
        if let Some(prev) = self.cancel_tx.borrow().as_ref() {
            prev.cancel();
        }

        let token = CancellationToken::new();
        let _ = self.cancel_tx.send(Some(token.clone()));

        let delay = self.delay;
        let token_clone = token.clone();

        tokio::spawn(async move {
            tokio::select! {
                _ = sleep(delay) => {}
                _ = token_clone.cancelled() => {}
            }
        });

        sleep(self.delay).await;

        if token.is_cancelled() {
            None
        } else {
            Some(token)
        }
    }
}

/// A shared, Arc-wrapped debouncer — cheaply cloned across tasks.
pub type SharedDebouncer = Arc<Debouncer>;
