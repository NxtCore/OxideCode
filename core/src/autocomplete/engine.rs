use std::num::NonZeroUsize;
use std::sync::Arc;
use futures_util::StreamExt;
use tokio_util::sync::CancellationToken;
use tracing::{debug, info, warn};

use crate::config::AutocompleteConfig;
use crate::providers::ProviderDyn;
use super::cache::CompletionCache;
use super::CompletionContext;

/// The high-level completion engine exposed to IDE bindings.
///
/// Holds a reference to the active provider and the LRU cache.
/// Each IDE binding constructs one `CompletionEngine` and keeps it alive
/// for the session. The engine itself is `Send + Sync` and can be shared
/// via `Arc`.
pub struct CompletionEngine {
    provider: Arc<dyn ProviderDyn>,
    cache: CompletionCache,
    config: AutocompleteConfig,
}

impl CompletionEngine {
    pub fn new(provider: Arc<dyn ProviderDyn>, config: AutocompleteConfig) -> Self {
        let cap = NonZeroUsize::new(config.cache_size.max(1)).unwrap();
        Self {
            provider,
            cache: CompletionCache::new(cap),
            config,
        }
    }

    /// Get a completion for the given context.
    ///
    /// Returns the full completion string once the stream is exhausted,
    /// or `None` if cancelled or the model returned nothing useful.
    ///
    /// `on_token` is called for each streamed token so the IDE can update
    /// ghost text incrementally without waiting for the full response.
    ///
    /// On a cache hit the result is returned immediately without any network
    /// round-trip.
    pub async fn complete(
        &self,
        ctx: CompletionContext,
        cancel: CancellationToken,
        on_token: impl Fn(String) + Send + 'static,
    ) -> Option<String> {
        let key = ctx.cache_key();

        info!(
            filepath = %ctx.filepath,
            language = %ctx.language,
            prefix_len = ctx.prefix.len(),
            suffix_len = ctx.suffix.len(),
            cache_key = key,
            "completion requested"
        );

        if let Some(cached) = self.cache.get(key) {
            debug!("completion cache hit");
            on_token(cached.clone());
            return Some(cached);
        }

        debug!("completion cache miss, querying provider");
        let mut stream = self.provider.complete_dyn(ctx, cancel.clone());
        let mut full = String::new();

        loop {
            tokio::select! {
                item = stream.next() => {
                    match item {
                        Some(Ok(token)) if !token.is_empty() => {
                            full.push_str(&token);
                            on_token(token);
                        }
                        Some(Ok(_)) => {}
                        Some(Err(e)) => {
                            warn!("completion stream error: {e}");
                            break;
                        }
                        None => break,
                    }
                }
                _ = cancel.cancelled() => {
                    debug!("completion cancelled");
                    return None;
                }
            }
        }

        if !full.is_empty() {
            info!(len = full.len(), "completion finished, caching result");
            self.cache.insert(key, full.clone());
            Some(full)
        } else {
            info!("completion produced empty result");
            None
        }
    }

    pub fn debounce_ms(&self) -> u64 {
        self.config.debounce_ms
    }
}
