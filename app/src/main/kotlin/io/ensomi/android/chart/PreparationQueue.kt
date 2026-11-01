package io.ensomi.android.chart

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** One background song at a time. Pausing signals native cancellation immediately, then waits for cleanup. */
class PreparationQueue<K>(
    scope: CoroutineScope,
    private val prepare: suspend (K) -> Boolean,
    private val stop: (K) -> Unit,
) {
    private val lock = Any()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var songs = emptyList<K>()
    private val attempted = mutableSetOf<K>()
    private var active: K? = null
    private var allowed = false

    init {
        scope.launch {
            for (signal in wake) {
                while (true) {
                    val next = synchronized(lock) {
                        if (allowed) songs.firstOrNull { it !in attempted }?.also { active = it } else null
                    } ?: break
                    val done = prepare(next)
                    synchronized(lock) {
                        if (done) attempted.add(next)
                        active = null
                    }
                }
            }
        }
    }

    fun update(next: List<K>) {
        val cancel = synchronized(lock) {
            songs = next
            attempted.retainAll(next.toSet())
            active?.takeIf { it !in next }
        }
        cancel?.let(stop)
        wake.trySend(Unit)
    }

    fun allow(value: Boolean) {
        val cancel = synchronized(lock) {
            allowed = value
            if (!value) active else null
        }
        cancel?.let(stop)
        wake.trySend(Unit)
    }
}
