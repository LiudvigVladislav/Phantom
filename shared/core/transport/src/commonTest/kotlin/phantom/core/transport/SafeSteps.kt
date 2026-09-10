// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

/**
 * A step recorder that is safe to read while other threads are writing.
 *
 * The fixtures in this round drive real concurrency: a teardown parked on
 * one dispatcher thread, a shutdown on another, a recovery timer on a
 * third. They recorded what happened in a `Collections.synchronizedList`,
 * which makes each `add` atomic and nothing else - every `count { }`,
 * `none { }` and `toList()` iterates, and iterating one of those without
 * holding its monitor is exactly the case its own documentation warns
 * about. A `ConcurrentModificationException` or a stale read there would
 * surface as a flaky fixture, and a flaky fixture in a mutation campaign
 * is indistinguishable from a mutation nobody observed.
 *
 * Every read here holds the same monitor as every write. The method names
 * match the collection ones deliberately, so the fixtures read the same
 * as before.
 */
internal class SafeList<T> {
    private val items = mutableListOf<T>()

    fun add(value: T): Boolean = synchronized(items) { items.add(value) }

    fun clear() = synchronized(items) { items.clear() }

    fun count(predicate: (T) -> Boolean): Int =
        synchronized(items) { items.count(predicate) }

    fun none(predicate: (T) -> Boolean): Boolean =
        synchronized(items) { items.none(predicate) }

    fun any(predicate: (T) -> Boolean): Boolean =
        synchronized(items) { items.any(predicate) }

    fun contains(value: T): Boolean = synchronized(items) { items.contains(value) }

    fun indexOf(value: T): Int = synchronized(items) { items.indexOf(value) }

    fun isEmpty(): Boolean = synchronized(items) { items.isEmpty() }

    fun toList(): List<T> = synchronized(items) { items.toList() }

    override fun toString(): String = toList().toString()
}

/** The common case: a list of step names. */
internal typealias SafeSteps = SafeList<String>
