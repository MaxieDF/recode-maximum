@file:JvmName("DF")
@file:JvmMultifileClass

package io.github.homchom.recode.multiplayer.state

import io.github.homchom.recode.mc
import io.github.homchom.recode.multiplayer.*
import io.github.homchom.recode.ui.equalsUnstyled
import io.github.homchom.recode.ui.matchesUnstyled
import io.github.homchom.recode.util.*
import kotlinx.coroutines.Deferred
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks

val ServerData?.ipMatchesDF get(): Boolean {
    val regex = Regex("""(?:\w+\.)?mcdiamondfire\.com(?::\d+)?""")
    return this?.ip?.matches(regex) ?: false
}

sealed interface DFState {
    val permissions: Deferred<PermissionGroup>

    val node: Node
    val session: SupportSession?

    /**
     * The player's current permissions, suspending if permissions have not yet been initially detected.
     */
    suspend fun permissions() = permissions.await()

    /**
     * Returns a new [DFState] derived from this one and [state], including calculated [PlotMode] state.
     */
    fun withState(state: LocateState) = when (state) {
        is LocateState.AtSpawn -> AtSpawn(state.node, permissions, session)
        is LocateState.OnPlot -> {
            val mode = when (state.mode) {
                PlotMode.Play -> PlotMode.Play

                PlotMode.Build -> PlotMode.Build

                PlotMode.Dev.ID -> mc.player!!.let { player ->
                    val devPos = player.blockPosition().mutable()
                    // search for dirt, not grass, because some plots have custom grounds
                    do {
                        devPos.move(Direction.DOWN)
                    } while (mc.level!!.getBlockState(devPos).block != Blocks.DIRT)

                    val buildCorner = devPos.move(-10, 1, -10).immutable()

                    val referenceBookCopy = player.inventory.getItem(17).copy()

                    PlotMode.Dev(buildCorner, referenceBookCopy)
                }
            }

            OnPlot(state.node, mode, state.plot, state.status, permissions, session)
        }
    }

    fun withSession(session: SupportSession?): DFState

    data class AtSpawn(
        override val node: Node,
        override val permissions: Deferred<PermissionGroup>,
        override val session: SupportSession?
    ) : DFState {
        override fun withSession(session: SupportSession?) = copy(session = session)

        override fun equals(other: Any?) = super.equals(other)
        override fun hashCode() = super.hashCode()
    }

    data class OnPlot(
        override val node: Node,
        val mode: PlotMode,
        val plot: Plot,
        val status: String?,
        override val permissions: Deferred<PermissionGroup>,
        override val session: SupportSession?
    ) : DFState {
        override fun withSession(session: SupportSession?) = copy(session = session)

        override fun equals(other: Any?) = super.equals(other)
        override fun hashCode() = super.hashCode()
    }
}

fun DFState?.isOnPlot(plot: Plot) = this is DFState.OnPlot && this.plot == plot

fun DFState?.isInMode(mode: PlotMode.ID) = this is DFState.OnPlot && this.mode.id == mode

@JvmInline
value class Node(private val id: String) {
    val displayName get() = when {
        id.startsWith("node") -> "Node ${id.drop(4)}"
        id == "beta" -> "Node Beta"
        else -> id.replaceFirstChar(Char::titlecase)
    }

    override fun toString() = displayName
}

fun nodeByName(name: String): Node {
    val node = name.removePrefix("Node ")
    val id = node.toIntOrNull()?.run { "node$node" } ?: node.replaceFirstChar(Char::lowercase)
    return Node(id)
}

data class Plot(
    val name: String,
    val owner: String,
    @get:JvmName("getId") val id: UInt
)

private val playModeRegex =
    Regex("""$MAIN_ARROW_CHAR Joined game: $PLOT_NAME_PATTERN by $USERNAME_PATTERN\.""")

sealed interface PlotMode {
    val id: ID

    sealed interface ID : Matcher<Component, Unit> {
        val descriptor: String

        val capitalizedDescriptor get() = descriptor.replaceFirstChar(Char::titlecase)

        companion object : GroupMatcher<Component, Unit, ID> by MatcherList(*ID.entries) {
            val entries get() = arrayOf(Play, Build, Dev)
        }
    }

    data object Play : PlotMode, ID {
        override val id get() = this

        override val descriptor = "playing"

        override fun match(input: Component) =
            playModeRegex.matchesUnstyled(input).unitOrNull()
    }

    data object Build : PlotMode, ID {
        override val id get() = this

        override val descriptor = "building"

        override fun match(input: Component) =
            input.equalsUnstyled("$MAIN_ARROW_CHAR You are now in build mode.").unitOrNull()
    }

    data class Dev(val buildCorner: BlockPos, val referenceBookCopy: ItemStack) : PlotMode {
        override val id get() = ID

        companion object ID : PlotMode.ID {
            override val descriptor = "coding"

            override fun match(input: Component) =
                input.equalsUnstyled("$MAIN_ARROW_CHAR You are now in dev mode.").unitOrNull()
        }
    }
}

enum class SupportSession : Matcher<Component, Unit> {
    Requested {
        override fun match(input: Component) = input.equalsUnstyled(
            "You have requested code support.\nIf you wish to leave the queue, use /support cancel."
        ).unitOrNull()
    },
    Helping {
        override fun match(input: Component): Unit? {
            val regex = Regex("\\[SUPPORT] ${mc.player!!.username} entered a session with " +
                    "$USERNAME_PATTERN\\. $SUPPORT_ARROW_CHAR Queue cleared!")
            return regex.matchesUnstyled(input).unitOrNull()
        }
    };

    companion object : GroupMatcher<Component, Unit, SupportSession> by enumMatcher()
}

sealed interface LocateState {
    val node: Node

    data class AtSpawn(override val node: Node) : LocateState

    data class OnPlot(
        override val node: Node,
        val plot: Plot,
        val mode: PlotMode.ID,
        val status: String?
    ) : LocateState
}

@Deprecated("Only used for legacy state", ReplaceWith("node.displayName"))
val DFState.nodeDisplayName @JvmName("getNodeDisplayName") get() = node.displayName