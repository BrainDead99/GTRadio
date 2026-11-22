package com.sc.gtradio.media.core

import java.util.Random

/**
 * Randomized Dual-Head Queue (RDHQ) based on sanandreasradio.com:
 * Picks at random from the first 2 elements (index 0 or index 1),
 * removes the chosen element, and appends it to the end of the queue.
 * Guarantees at least (n - 2) unique items play before any item repeats,
 * while continuously mutating the playlist order over time.
 */
class RandomizedDualHeadQueue<T>(
    initialItems: Collection<T> = emptyList(),
    private val rng: Random = Random()
) {
    private val queue = ArrayList<T>(initialItems)

    init {
        if (queue.size > 1) {
            queue.shuffle(rng)
        }
    }

    @Synchronized
    fun isEmpty(): Boolean = queue.isEmpty()

    @Synchronized
    fun isNotEmpty(): Boolean = queue.isNotEmpty()

    @Synchronized
    fun size(): Int = queue.size

    @Synchronized
    fun reset(newItems: Collection<T>) {
        queue.clear()
        queue.addAll(newItems)
        if (queue.size > 1) {
            queue.shuffle(rng)
        }
    }

    @Synchronized
    fun next(): T {
        if (queue.isEmpty()) {
            throw NoSuchElementException("RandomizedDualHeadQueue is empty")
        }
        if (queue.size == 1) {
            return queue[0]
        }
        val pickIndex = rng.nextInt(2) // 0 (heads) or 1 (tails)
        val chosen = queue.removeAt(pickIndex)
        queue.add(chosen)
        return chosen
    }
}
