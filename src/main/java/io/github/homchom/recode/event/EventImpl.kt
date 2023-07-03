package io.github.homchom.recode.event

import com.google.common.cache.CacheBuilder
import io.github.homchom.recode.RecodeDispatcher
import io.github.homchom.recode.lifecycle.ModuleDetail
import io.github.homchom.recode.lifecycle.RModule
import io.github.homchom.recode.lifecycle.module
import io.github.homchom.recode.runOnMinecraftThread
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.event.Event
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.toJavaDuration

/**
 * Creates a [CustomEvent], providing results by transforming context with [resultCapture].
 */
fun <T, R : Any> createEvent(resultCapture: (T) -> R): CustomEvent<T, R> = FlowEvent(resultCapture)

/**
 * Creates a [CustomEvent] with a Unit result.
 */
fun <T> createEvent() = createEvent<T, Unit> {}

/**
 * Creates a [BufferedCustomEvent] that runs asynchronously and caches the result. [BufferedCustomEvent.stabilize]
 * should be called once during each critical section of this event's execution; if this is done, the result will
 * be cached roughly on [stableInterval].
 *
 * @param keySelector A function that transforms [I] into a hashable key type [K] for the buffer.
 * @param contextGenerator A function that transforms [I] into the context type [T] when needed.
 * @param cacheDuration How long event contexts should stay in the cache after being written before eviction.
 */
fun <T, R : Any, I, K : Any> createBufferedEvent(
    resultCapture: (T) -> R,
    stableInterval: Duration,
    keySelector: (I) -> K,
    contextGenerator: (I) -> T,
    cacheDuration: Duration = 1.seconds
): BufferedCustomEvent<T, R, I> {
    val delegate = createEvent(resultCapture)
    return BufferedFlowEvent(delegate, stableInterval, keySelector, contextGenerator, cacheDuration)
}

/**
 * Wraps an existing Fabric [Event] into a [Listenable], using [transform] to map recode listeners to its
 * specification.
 */
fun <T, L> wrapFabricEvent(
    event: Event<L>,
    transform: (EventInvoker<T>) -> L
): WrappedEvent<T, L> {
    return EventWrapper(event, transform)
}

private class FlowEvent<T, R : Any>(private val resultCapture: (T) -> R) : CustomEvent<T, R> {
    private val flow = MutableSharedFlow<T>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override val previous = MutableStateFlow<R?>(null)

    override fun getNotificationsFrom(module: RModule) = flow

    override fun run(context: T) = runOnMinecraftThread {
        flow.checkEmit(context)
        RecodeDispatcher.expedite() // allow for validation and other state mutation
        resultCapture(context).also { previous.checkEmit(it) }
    }
}

private class BufferedFlowEvent<T, R : Any, I, K : Any>(
    delegate: CustomEvent<T, R>,
    stableInterval: Duration,
    private val keySelector: (I) -> K,
    private val contextGenerator: (I) -> T,
    cacheDuration: Duration = 1.seconds
) : BufferedCustomEvent<T, R, I> {
    private val module = module(ModuleDetail.Exposed)
    private val delegate = DependentEvent(delegate, module)

    // TODO: use Caffeine (official successor) or other alternative?
    private val buffer = CacheBuilder.newBuilder()
        .expireAfterAccess(cacheDuration.toJavaDuration())
        .build<K, R>()

    private val stabilizer = object {
        var passes = 1
            private set

        var passIndex = -1
        var runIndex = 0

        private var prevStamp = 0L

        fun stamp() {
            val now = System.currentTimeMillis()
            if (++passIndex == passes) {
                val elapsed = (now - prevStamp).toInt().coerceAtLeast(1)
                passes = stableInterval.toInt(DurationUnit.MILLISECONDS) / elapsed
                passIndex = 0
            }
            runIndex = passIndex
            prevStamp = now
        }
    }

    override val previous = MutableStateFlow<R?>(null)

    override fun getNotificationsFrom(module: RModule) = delegate.getNotificationsFrom(module)

    override fun run(input: I): R {
        val key = keySelector(input)
        val bufferResult = buffer.getIfPresent(key)
        val result = if (bufferResult == null) {
            delegate.run(contextGenerator(input)).also { buffer.put(key, it) }
        } else {
            if (++stabilizer.runIndex >= stabilizer.passes) {
                module.launch {
                    buffer.put(key, delegate.run(contextGenerator(input)))
                }
                stabilizer.runIndex = 0
            }
            bufferResult
        }
        previous.checkEmit(result)
        return result
    }

    override fun stabilize() = stabilizer.stamp()
}

private fun <E> MutableSharedFlow<E>.checkEmit(value: E) =
    check(tryEmit(value)) { "FlowEvent collectors should not suspend" }

private class EventWrapper<T, L>(
    private val fabricEvent: Event<L>,
    transform: ((T) -> Unit) -> L
) : WrappedEvent<T, L> {
    private val async = createEvent<T>()

    override val invoker: L get() = fabricEvent.invoker()

    init {
        fabricEvent.register(transform(async::run))
    }

    override fun getNotificationsFrom(module: RModule) = async.getNotificationsFrom(module)
}