package dev.sweep.assistant.utils

import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

open class ConcurrentOrderedHashMap<K, V> : MutableMap<K, V> {
    private val lock = ReentrantReadWriteLock()
    private val map = LinkedHashMap<K, V>()

    override val size: Int
        get() = lock.read { map.size }

    override fun isEmpty(): Boolean = lock.read { map.isEmpty() }

    override fun containsKey(key: K): Boolean = lock.read { map.containsKey(key) }

    override fun containsValue(value: V): Boolean = lock.read { map.containsValue(value) }

    override fun get(key: K): V? = lock.read { map[key] }

    override fun clear() = lock.write { map.clear() }

    override fun put(
        key: K,
        value: V,
    ): V? = lock.write { map.put(key, value) }

    override fun putAll(from: Map<out K, V>) = lock.write { map.putAll(from) }

    override fun remove(key: K): V? = lock.write { map.remove(key) }

    // These methods below return only a snapshot. Any updates made after we fetch entries won't be reflected in them.
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = lock.read { LinkedHashMap(map).entries }

    override val keys: MutableSet<K>
        get() = lock.read { LinkedHashMap(map).keys }

    override val values: MutableCollection<V>
        get() = lock.read { LinkedHashMap(map).values }

    fun copy(): MutableMap<K, V> = lock.read { LinkedHashMap(map) }

    open fun putToStart(
        key: K,
        value: V,
    ): V? =
        lock.write {
            val oldValue = map.remove(key)
            val tempMap = LinkedHashMap<K, V>()
            tempMap[key] = value
            tempMap.putAll(map)
            map.clear()
            map.putAll(tempMap)
            oldValue
        }

    val lastKey: K?
        get() = keys.lastOrNull()

    val lastValue: V?
        get() = values.lastOrNull()
}
