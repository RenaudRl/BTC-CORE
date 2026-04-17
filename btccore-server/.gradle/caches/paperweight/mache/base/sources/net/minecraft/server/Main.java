package net.minecraft.server;

import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.datafixers.DataFixer;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.Lifecycle;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.util.PathConverter;
import net.minecraft.CrashReport;
import net.minecraft.DefaultUncaughtExceptionHandler;
import net.minecraft.SharedConstants;
import net.minecraft.SuppressForbidden;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtException;
import net.minecraft.nbt.ReportedNbtException;
import net.minecraft.network.chat.Component;
import net.minecraft.obfuscate.DontObfuscate;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.server.dedicated.DedicatedServerSettings;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.profiling.jfr.Environment;
import net.minecraft.util.profiling.jfr.JvmProfiler;
import net.minecraft.util.worldupdate.WorldUpgrader;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelDataAndDimensions;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.storage.WorldData;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class Main {
    private static final Logger LOGGER = LogUtils.getLogger();

    @SuppressForbidden(reason = "System.out needed before bootstrap")
    @DontObfuscate
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        OptionParser optionParser = new OptionParser();
        OptionSpec<Void> optionSpec = optionParser.accepts("nogui");
        OptionSpec<Void> optionSpec1 = optionParser.accepts("initSettings", "Initializes 'server.properties' and 'eula.txt', then quits");
        OptionSpec<Void> optionSpec2 = optionParser.accepts("demo");
        OptionSpec<Void> optionSpec3 = optionParser.accepts("bonusChest");
        OptionSpec<Void> optionSpec4 = optionParser.accepts("forceUpgrade");
        OptionSpec<Void> optionSpec5 = optionParser.accepts("eraseCache");
        OptionSpec<Void> optionSpec6 = optionParser.accepts("recreateRegionFiles");
        OptionSpec<Void> optionSpec7 = optionParser.accepts("safeMode", "Loads level with vanilla datapack only");
        OptionSpec<Void> optionSpec8 = optionParser.accepts("help").forHelp();
        OptionSpec<String> optionSpec9 = optionParser.accepts("universe").withRequiredArg().defaultsTo(".");
        OptionSpec<String> optionSpec10 = optionParser.accepts("world").withRequiredArg();
        OptionSpec<Integer> optionSpec11 = optionParser.accepts("port").withRequiredArg().ofType(Integer.class).defaultsTo(-1);
        OptionSpec<String> optionSpec12 = optionParser.accepts("serverId").withRequiredArg();
        OptionSpec<Void> optionSpec13 = optionParser.accepts("jfrProfile");
        OptionSpec<Path> optionSpec14 = optionParser.accepts("pidFile").withRequiredArg().withValuesConvertedBy(new PathConverter());
        OptionSpec<String> optionSpec15 = optionParser.nonOptions();

        try {
            OptionSet optionSet = optionParser.parse(args);
            if (optionSet.has(optionSpec8)) {
                optionParser.printHelpOn(System.err);
                return;
            }

            Path path = optionSet.valueOf(optionSpec14);
            if (path != null) {
                writePidFile(path);
            }

            CrashReport.preload();
            if (optionSet.has(optionSpec13)) {
                JvmProfiler.INSTANCE.start(Environment.SERVER);
            }

            Bootstrap.bootStrap();
            Bootstrap.validate();
            Util.startTimerHackThread();
            Path path1 = Paths.get("server.properties");
            DedicatedServerSettings dedicatedServerSettings = new DedicatedServerSettings(path1);
            dedicatedServerSettings.forceSave();
            RegionFileVersion.configure(dedicatedServerSettings.getProperties().regionFileComression);
            Path path2 = Paths.get("eula.txt");
            Eula eula = new Eula(path2);
            if (optionSet.has(optionSpec1)) {
                LOGGER.info("Initialized '{}' and '{}'", path1.toAbsolutePath(), path2.toAbsolutePath());
                return;
            }

            if (!eula.hasAgreedToEULA()) {
                LOGGER.info("You need to agree to the EULA in order to run the server. Go to eula.txt for more info.");
                return;
            }

            File file = new File(optionSet.valueOf(optionSpec9));
            Services services = Services.create(new YggdrasilAuthenticationService(Proxy.NO_PROXY), file);
            String string = Optional.ofNullable(optionSet.valueOf(optionSpec10)).orElse(dedicatedServerSettings.getProperties().levelName);
            LevelStorageSource levelStorageSource = LevelStorageSource.createDefault(file.toPath());
            LevelStorageSource.LevelStorageAccess levelStorageAccess = levelStorageSource.validateAndCreateAccess(string);
            Dynamic<?> dataTag;
            if (levelStorageAccess.hasWorldData()) {
                LevelSummary summary;
                try {
                    dataTag = levelStorageAccess.getDataTag();
                    summary = levelStorageAccess.getSummary(dataTag);
                } catch (NbtException | ReportedNbtException | IOException var41) {
                    LevelStorageSource.LevelDirectory levelDirectory = levelStorageAccess.getLevelDirectory();
                    LOGGER.warn("Failed to load world data from {}", levelDirectory.dataFile(), var41);
                    LOGGER.info("Attempting to use fallback");

                    try {
                        dataTag = levelStorageAccess.getDataTagFallback();
                        summary = levelStorageAccess.getSummary(dataTag);
                    } catch (NbtException | ReportedNbtException | IOException var40) {
                        LOGGER.error("Failed to load world data from {}", levelDirectory.oldDataFile(), var40);
                        LOGGER.error(
                            "Failed to load world data from {} and {}. World files may be corrupted. Shutting down.",
                            levelDirectory.dataFile(),
                            levelDirectory.oldDataFile()
                        );
                        return;
                    }

                    levelStorageAccess.restoreLevelDataFromOld();
                }

                if (summary.requiresManualConversion()) {
                    LOGGER.info("This world must be opened in an older version (like 1.6.4) to be safely converted");
                    return;
                }

                if (!summary.isCompatible()) {
                    LOGGER.info("This world was created by an incompatible version.");
                    return;
                }
            } else {
                dataTag = null;
            }

            Dynamic<?> dynamic = dataTag;
            boolean hasOptionSpec = optionSet.has(optionSpec7);
            if (hasOptionSpec) {
                LOGGER.warn("Safe mode active, only vanilla datapack will be loaded");
            }

            PackRepository packRepository = ServerPacksSource.createPackRepository(levelStorageAccess);

            WorldStem worldStem;
            try {
                WorldLoader.InitConfig initConfig = loadOrCreateConfig(dedicatedServerSettings.getProperties(), dynamic, hasOptionSpec, packRepository);
                worldStem = Util.<WorldStem>blockUntilDone(
                        executor -> WorldLoader.load(
                            initConfig,
                            context -> {
                                Registry<LevelStem> registry = context.datapackDimensions().lookupOrThrow(Registries.LEVEL_STEM);
                                if (dynamic != null) {
                                    LevelDataAndDimensions levelDataAndDimensions = LevelStorageSource.getLevelDataAndDimensions(
                                        dynamic, context.dataConfiguration(), registry, context.datapackWorldgen()
                                    );
                                    return new WorldLoader.DataLoadOutput<>(
                                        levelDataAndDimensions.worldData(), levelDataAndDimensions.dimensions().dimensionsRegistryAccess()
                                    );
                                } else {
                                    LOGGER.info("No existing world data, creating new world");
                                    return createNewWorldData(
                                        dedicatedServerSettings, context, registry, optionSet.has(optionSpec2), optionSet.has(optionSpec3)
                                    );
                                }
                            },
                            WorldStem::new,
                            Util.backgroundExecutor(),
                            executor
                        )
                    )
                    .get();
            } catch (Exception var39) {
                LOGGER.warn(
                    "Failed to load datapacks, can't proceed with server load. You can either fix your datapacks or reset to vanilla with --safeMode",
                    (Throwable)var39
                );
                return;
            }

            RegistryAccess.Frozen frozen = worldStem.registries().compositeAccess();
            WorldData worldData = worldStem.worldData();
            boolean hasOptionSpec1 = optionSet.has(optionSpec6);
            if (optionSet.has(optionSpec4) || hasOptionSpec1) {
                forceUpgrade(levelStorageAccess, worldData, DataFixers.getDataFixer(), optionSet.has(optionSpec5), () -> true, frozen, hasOptionSpec1);
            }

            levelStorageAccess.saveDataTag(frozen, worldData);
            final DedicatedServer dedicatedServer = MinecraftServer.spin(
                thread1 -> {
                    DedicatedServer dedicatedServer1 = new DedicatedServer(
                        thread1, levelStorageAccess, packRepository, worldStem, dedicatedServerSettings, DataFixers.getDataFixer(), services
                    );
                    dedicatedServer1.setPort(optionSet.valueOf(optionSpec11));
                    dedicatedServer1.setDemo(optionSet.has(optionSpec2));
                    dedicatedServer1.setId(optionSet.valueOf(optionSpec12));
                    boolean flag = !optionSet.has(optionSpec) && !optionSet.valuesOf(optionSpec15).contains("nogui");
                    if (flag && !GraphicsEnvironment.isHeadless()) {
                        dedicatedServer1.showGui();
                    }

                    return dedicatedServer1;
                }
            );
            Thread thread = new Thread("Server Shutdown Thread") {
                @Override
                public void run() {
                    dedicatedServer.halt(true);
                }
            };
            thread.setUncaughtExceptionHandler(new DefaultUncaughtExceptionHandler(LOGGER));
            Runtime.getRuntime().addShutdownHook(thread);
        } catch (Throwable var42) {
            LOGGER.error(LogUtils.FATAL_MARKER, "Failed to start the minecraft server", var42);
        }
    }

    private static WorldLoader.DataLoadOutput<WorldData> createNewWorldData(
        DedicatedServerSettings settings, WorldLoader.DataLoadContext context, Registry<LevelStem> stemRegistry, boolean demo, boolean generateBonusChest
    ) {
        LevelSettings levelSettings;
        WorldOptions worldOptions;
        WorldDimensions worldDimensions;
        if (demo) {
            levelSettings = MinecraftServer.DEMO_SETTINGS;
            worldOptions = WorldOptions.DEMO_OPTIONS;
            worldDimensions = WorldPresets.createNormalWorldDimensions(context.datapackWorldgen());
        } else {
            DedicatedServerProperties properties = settings.getProperties();
            levelSettings = new LevelSettings(
                properties.levelName,
                properties.gameMode.get(),
                properties.hardcore,
                properties.difficulty.get(),
                false,
                new GameRules(context.dataConfiguration().enabledFeatures()),
                context.dataConfiguration()
            );
            worldOptions = generateBonusChest ? properties.worldOptions.withBonusChest(true) : properties.worldOptions;
            worldDimensions = properties.createDimensions(context.datapackWorldgen());
        }

        WorldDimensions.Complete complete = worldDimensions.bake(stemRegistry);
        Lifecycle lifecycle = complete.lifecycle().add(context.datapackWorldgen().allRegistriesLifecycle());
        return new WorldLoader.DataLoadOutput<>(
            new PrimaryLevelData(levelSettings, worldOptions, complete.specialWorldProperty(), lifecycle), complete.dimensionsRegistryAccess()
        );
    }

    private static void writePidFile(Path path) {
        try {
            long l = ProcessHandle.current().pid();
            Files.writeString(path, Long.toString(l));
        } catch (IOException var3) {
            throw new UncheckedIOException(var3);
        }
    }

    private static WorldLoader.InitConfig loadOrCreateConfig(
        DedicatedServerProperties dedicatedServerProperties, @Nullable Dynamic<?> dynamic, boolean safeMode, PackRepository packRepository
    ) {
        boolean flag;
        WorldDataConfiguration worldDataConfiguration;
        if (dynamic != null) {
            WorldDataConfiguration dataConfig = LevelStorageSource.readDataConfig(dynamic);
            flag = false;
            worldDataConfiguration = dataConfig;
        } else {
            flag = true;
            worldDataConfiguration = new WorldDataConfiguration(dedicatedServerProperties.initialDataPackConfiguration, FeatureFlags.DEFAULT_FLAGS);
        }

        WorldLoader.PackConfig packConfig = new WorldLoader.PackConfig(packRepository, worldDataConfiguration, safeMode, flag);
        return new WorldLoader.InitConfig(packConfig, Commands.CommandSelection.DEDICATED, dedicatedServerProperties.functionPermissions);
    }

    public static void forceUpgrade(
        LevelStorageSource.LevelStorageAccess levelStorage,
        WorldData worldData,
        DataFixer dataFixer,
        boolean eraseCache,
        BooleanSupplier running,
        RegistryAccess registryAccess,
        boolean recreateRegionFiles
    ) {
        LOGGER.info("Forcing world upgrade!");

        try (WorldUpgrader worldUpgrader = new WorldUpgrader(levelStorage, dataFixer, worldData, registryAccess, eraseCache, recreateRegionFiles)) {
            Component component = null;

            while (!worldUpgrader.isFinished()) {
                Component status = worldUpgrader.getStatus();
                if (component != status) {
                    component = status;
                    LOGGER.info(worldUpgrader.getStatus().getString());
                }

                int totalChunks = worldUpgrader.getTotalChunks();
                if (totalChunks > 0) {
                    int i = worldUpgrader.getConverted() + worldUpgrader.getSkipped();
                    LOGGER.info("{}% completed ({} / {} chunks)...", Mth.floor((float)i / totalChunks * 100.0F), i, totalChunks);
                }

                if (!running.getAsBoolean()) {
                    worldUpgrader.cancel();
                } else {
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException var13) {
                    }
                }
            }
        }
    }
}
