use lru::LruCache;
use std::num::NonZeroUsize;
use std::sync::Mutex;

/// Thread-safe LRU completion cache.
/// Keyed by `CompletionContext::cache_key()`, stores the full completion string.
pub struct CompletionCache {
    inner: Mutex<LruCache<u64, String>>,
}

impl CompletionCache {
    pub fn new(capacity: NonZeroUsize) -> Self {
        Self {
            inner: Mutex::new(LruCache::new(capacity)),
        }
    }

    pub fn get(&self, key: u64) -> Option<String> {
        self.inner.lock().ok()?.get(&key).cloned()
    }

    pub fn insert(&self, key: u64, value: String) {
        if let Ok(mut cache) = self.inner.lock() {
            cache.put(key, value);
        }
    }
}
