package io.github.homchom.recode

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.github.homchom.recode.event.listenEachFrom
import io.github.homchom.recode.feature.automation.AutoCommands
import io.github.homchom.recode.feature.visual.FBuiltInResourcePacks
import io.github.homchom.recode.feature.visual.FCodeSearch
import io.github.homchom.recode.feature.visual.FSignRenderDistance
import io.github.homchom.recode.game.QuitGameEvent
import io.github.homchom.recode.mod.commands.CommandHandler
import io.github.homchom.recode.mod.config.Config
import io.github.homchom.recode.mod.config.internal.ConfigFile
import io.github.homchom.recode.mod.config.internal.ConfigInstruction
import io.github.homchom.recode.mod.config.internal.gson.ConfigSerializer
import io.github.homchom.recode.mod.config.internal.gson.types.*
import io.github.homchom.recode.mod.config.internal.gson.types.list.StringListSerializer
import io.github.homchom.recode.mod.config.structure.ConfigManager
import io.github.homchom.recode.mod.config.types.*
import io.github.homchom.recode.mod.config.types.list.StringListSetting
import io.github.homchom.recode.mod.events.LegacyEventHandler
import io.github.homchom.recode.sys.hypercube.codeaction.ActionDump
import io.github.homchom.recode.sys.hypercube.templates.TemplateStorageHandler
import io.github.homchom.recode.sys.networking.websocket.SocketHandler
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.ModContainer
import net.fabricmc.loader.api.metadata.ModMetadata
import net.fabricmc.loader.api.metadata.ModOrigin
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.nio.file.Path
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random

private val logger = LoggerFactory.getLogger(MOD_ID).apply { isEnabledForLevel(Level.DEBUG) }

object Recode : ModContainer {
    val version: String get() = metadata.version.friendlyString

    @Deprecated(
        "Use kotlin.random.Random instead",
        ReplaceWith("Random", "kotlin.random.Random")
    )
    val random get() = Random

    private val container by lazy { FabricLoader.getInstance().getModContainer(MOD_ID).get() }

    private var isInitialized = false

    // initialize features TODO: replace with FeatureGroups during config refactor
    init {
        // Automation
        AutoCommands

        // Chat and Social

        // Visual
        FBuiltInResourcePacks
        FCodeSearch
        FSignRenderDistance
    }

    /**
     * Initializes the mod. This should only be called once, from an entrypoint.
     */
    fun initialize() {
        check(!isInitialized) { "$MOD_NAME has already been initialized" }
        logInfo("initializing...")

        LegacyRecode.onInitialize()

        @OptIn(DelicateCoroutinesApi::class)
        QuitGameEvent.listenEachFrom(GlobalScope) { close() }

        isInitialized = true
        logInfo("initialized successfully")
    }

    private fun close() {
        logInfo("closing...")

        ConfigFile.getInstance().save()
        TemplateStorageHandler.getInstance().save()

        logInfo("closed successfully")
    }

    override fun getMetadata(): ModMetadata = container.metadata
    override fun getRootPaths(): List<Path> = container.rootPaths
    override fun getOrigin(): ModOrigin = container.origin
    override fun getContainingMod(): Optional<ModContainer> = container.containingMod
    override fun getContainedMods(): Collection<ModContainer> = container.containedMods

    @Deprecated("Use getRootPaths instead")
    @Suppress("Deprecation")
    override fun getRootPath(): Path = container.rootPath

    @Deprecated("Use getRootPaths instead")
    @Suppress("Deprecation")
    override fun getPath(file: String?): Path = container.getPath(file)
}

@Deprecated("Use top-level or Kotlin equivalents")
object LegacyRecode {
    @JvmField
    val GSON: Gson = GsonBuilder()
        .registerTypeAdapter(ConfigInstruction::class.java, ConfigSerializer())
        .registerTypeAdapter(BooleanSetting::class.java, BooleanSerializer())
        .registerTypeAdapter(IntegerSetting::class.java, IntegerSerializer())
        .registerTypeAdapter(DoubleSetting::class.java, DoubleSerializer())
        .registerTypeAdapter(FloatSetting::class.java, FloatSerializer())
        .registerTypeAdapter(LongSetting::class.java, LongSerializer())
        .registerTypeAdapter(StringSetting::class.java, StringSerializer())
        .registerTypeAdapter(StringListSetting::class.java, StringListSerializer())
        .registerTypeAdapter(EnumSetting::class.java, EnumSerializer())
        .registerTypeAdapter(DynamicStringSetting::class.java, DynamicStringSerializer())
        .registerTypeAdapter(SoundSetting::class.java, SoundSerializer())
        .setPrettyPrinting()
        .create()

    @JvmField
    val executor: ExecutorService = Executors.newCachedThreadPool()

    // TODO: replace with something... better
    @JvmField
    var signText = arrayOf<String>() // stores the text of the code sign corresponding to the currently open chest

    fun onInitialize() {
        //System.setProperty("https.protocols", "TLSv1,TLSv1.1,TLSv1.2,TLSv1.3");

        // Initialize legacy code
        val initializer = CodeInitializer()
        initializer.add(ConfigFile())
        initializer.add(ConfigManager.getInstance())
        initializer.add(TemplateStorageHandler())
        initializer.add(ActionDump())
        initializer.add(LegacyEventHandler())

        // Initializes only if the given condition is met. (this case: config value)
        initializer.addIf(SocketHandler(), Config.getBoolean("itemApi"))

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, registryAccess ->
            CommandHandler.load(dispatcher, registryAccess)
        }
    }

    @JvmStatic
    fun info(message: String) = logInfo("[$MOD_NAME] $message")

    @JvmStatic
    fun error(message: String) = logError("[$MOD_NAME] $message")
}

fun logInfo(message: String) = logger.info("[$MOD_NAME] $message")

@JvmOverloads
fun logError(message: String, mentionBugReport: Boolean = false) {
    val bugString = if (mentionBugReport) {
        "\nIf you believe this is a bug, you can report it here: https://github.com/homchom/recode/issues)"
    } else ""
    logger.error("[$MOD_NAME] $message$bugString")
}

fun logDebug(message: String) {
    if (debug) logger.info("[$MOD_NAME debug] $message")
}