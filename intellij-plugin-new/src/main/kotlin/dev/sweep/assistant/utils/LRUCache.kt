package dev.sweep.assistant.utils

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * A thread-safe LRU cache with TTL-based eviction and configurable max size.
 *
 * Implementation note: The removeLRU() method currently has O(n) time complexity
 * because it needs to find the key for the tail entry by searching through the
 * ConcurrentHashMap entries. This could be optimized by maintaining a reverse
 * mapping from CacheEntry to key, but the current implementation prioritizes
 * simplicity and memory efficiency over this edge case performance.
 *
 * @param K the type of keys maintained by this cache
 * @param V the type of mapped values
 * @param maxSize the maximum number of entries to keep in the cache
 * @param ttlMs the time-to-live for cache entries in milliseconds
 */
class LRUCache<K, V>(
    private val maxSize: Int,
    private val ttlMs: Long,
) {
    private data class CacheEntry<V>(
        val value: V,
        val timestamp: Long,
        var prev: CacheEntry<V>? = null,
        var next: CacheEntry<V>? = null,
    )

    private val cache = ConcurrentHashMap<K, CacheEntry<V>>()
    private val lock = ReentrantReadWriteLock()

    // Doubly linked list for LRU ordering
    private var head: CacheEntry<V>? = null
    private var tail: CacheEntry<V>? = null

    /**
     * Retrieves a value from the cache if it exists and hasn't expired.
     * Updates the entry's position to mark it as recently used.
     */
    fun get(key: K): V? =
        lock.write {
            val entry = cache[key] ?: return null

            // Check if entry has expired
            if (isExpired(entry)) {
                removeEntry(key, entry)
                return null
            }

            // Move to head (most recently used)
            moveToHead(entry)
            return entry.value
        }

    /**
     * Stores a key-value pair in the cache.
     * If the cache exceeds maxSize, removes the least recently used entry.
     */
    fun put(
        key: K,
        value: V,
    ) = lock.write {
        val existingEntry = cache[key]

        if (existingEntry != null) {
            // Update existing entry
            val newEntry = CacheEntry(value, System.currentTimeMillis())
            cache[key] = newEntry

            // Replace in linked list
            replaceEntry(existingEntry, newEntry)
            moveToHead(newEntry)
        } else {
            // Add new entry
            val newEntry = CacheEntry(value, System.currentTimeMillis())
            cache[key] = newEntry
            addToHead(newEntry)

            // Check size limit
            if (cache.size > maxSize) {
                removeLRU()
            }
        }

        // Clean up expired entries periodically
        if (cache.size % 10 == 0) {
            cleanupExpired()
        }
    }

    /**
     * Removes a specific key from the cache.
     */
    fun remove(key: K): V? =
        lock.write {
            val entry = cache.remove(key) ?: return null
            removeFromList(entry)
            return entry.value
        }

    /**
     * Clears all entries from the cache.
     */
    fun clear() =
        lock.write {
            cache.clear()
            head = null
            tail = null
        }

    /**
     * Returns the current size of the cache.
     */
    fun size(): Int =
        lock.read {
            cache.size
        }

    /**
     * Checks if the cache contains a specific key (and the entry hasn't expired).
     */
    fun containsKey(key: K): Boolean =
        lock.read {
            val entry = cache[key] ?: return false
            return !isExpired(entry)
        }

    /**
     * Returns a snapshot of all non-expired keys in the cache.
     */
    fun keys(): Set<K> =
        lock.read {
            cache.entries
                .filter { !isExpired(it.value) }
                .map { it.key }
                .toSet()
        }

    private fun isExpired(entry: CacheEntry<V>): Boolean = System.currentTimeMillis() - entry.timestamp > ttlMs

    private fun removeEntry(
        key: K,
        entry: CacheEntry<V>,
    ) {
        cache.remove(key)
        removeFromList(entry)
    }

    private fun moveToHead(entry: CacheEntry<V>) {
        removeFromList(entry)
        addToHead(entry)
    }

    private fun addToHead(entry: CacheEntry<V>) {
        entry.prev = null
        entry.next = head

        head?.prev = entry
        head = entry

        if (tail == null) {
            tail = entry
        }
    }

    private fun removeFromList(entry: CacheEntry<V>) {
        if (entry.prev != null) {
            entry.prev!!.next = entry.next
        } else {
            head = entry.next
        }

        if (entry.next != null) {
            entry.next!!.prev = entry.prev
        } else {
            tail = entry.prev
        }

        entry.prev = null
        entry.next = null
    }

    private fun replaceEntry(
        oldEntry: CacheEntry<V>,
        newEntry: CacheEntry<V>,
    ) {
        newEntry.prev = oldEntry.prev
        newEntry.next = oldEntry.next

        oldEntry.prev?.next = newEntry
        oldEntry.next?.prev = newEntry

        if (head == oldEntry) head = newEntry
        if (tail == oldEntry) tail = newEntry
    }

    private fun removeLRU() {
        val lru = tail ?: return

        // Find the key for this entry
        val keyToRemove = cache.entries.find { it.value == lru }?.key
        keyToRemove?.let { removeEntry(it, lru) }
    }

    private fun cleanupExpired() {
        val currentTime = System.currentTimeMillis()
        val expiredKeys =
            cache.entries
                .filter { currentTime - it.value.timestamp > ttlMs }
                .map { it.key }

        expiredKeys.forEach { key ->
            cache[key]?.let { entry ->
                removeEntry(key, entry)
            }
        }
    }
}
