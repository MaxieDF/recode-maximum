package io.github.homchom.recode.event

import io.github.homchom.recode.lifecycle.ModuleBuilder
import io.github.homchom.recode.lifecycle.RModule
import io.github.homchom.recode.lifecycle.module
import kotlinx.coroutines.flow.Flow

// TODO: rework post-flavors

fun <T> DependentListenable(delegate: Listenable<T>, dependencyBuilder: ModuleBuilder) =
    DependentListenable(delegate, module(dependencyBuilder))

fun <T, R> DependentResultListenable(
    delegate: ResultListenable<T, R>,
    dependencyBuilder: ModuleBuilder
): DependentResultListenable<T, R> {
    return DependentResultListenable(delegate, module(dependencyBuilder))
}

fun <T, R : Any> DependentEvent(delegate: CustomEvent<T, R>, dependencyBuilder: ModuleBuilder) =
    DependentEvent(delegate, module(dependencyBuilder))

fun <T, R : Any, I> DependentBufferedEvent(
    delegate: BufferedCustomEvent<T, R, I>,
    dependencyBuilder: ModuleBuilder
): DependentBufferedEvent<T, R, I> {
    return DependentBufferedEvent(delegate, module(dependencyBuilder))
}

/**
 * A [Listenable] with a [dependency] that is respected by listening modules.
 */
class DependentListenable<T>(
    private val delegate: Listenable<T>,
    private val dependency: RModule
) : Listenable<T> {
    override fun getNotificationsFrom(module: RModule) =
        delegate.getNotificationsDependent(module, dependency)
}

/**
 * @see DependentListenable
 * @see ResultListenable
 */
class DependentResultListenable<T, R>(
    private val delegate: ResultListenable<T, R>,
    private val dependency: RModule
) : ResultListenable<T, R> {
    override val previous get() = delegate.previous

    override fun getNotificationsFrom(module: RModule) =
        delegate.getNotificationsDependent(module, dependency)
}

/**
 * @see DependentListenable
 * @see CustomEvent
 */
class DependentEvent<T, R : Any>(
    private val delegate: CustomEvent<T, R>,
    private val dependency: RModule
) : CustomEvent<T, R> {
    override val previous get() = delegate.previous

    override fun getNotificationsFrom(module: RModule) =
        delegate.getNotificationsDependent(module, dependency)

    override fun run(context: T) = delegate.run(context)
}

/**
 * @see DependentListenable
 * @see BufferedCustomEvent
 */
class DependentBufferedEvent<T, R : Any, I>(
    private val delegate: BufferedCustomEvent<T, R, I>,
    private val dependency: RModule
) : BufferedCustomEvent<T, R, I> {
    override val previous get() = delegate.previous

    override fun getNotificationsFrom(module: RModule) =
        delegate.getNotificationsDependent(module, dependency)

    override fun run(input: I) = delegate.run(input)

    override fun stabilize() = delegate.stabilize()
}

private fun <T> Listenable<T>.getNotificationsDependent(module: RModule, dependency: RModule): Flow<T> {
    module.depend(dependency)
    return getNotificationsFrom(module)
}