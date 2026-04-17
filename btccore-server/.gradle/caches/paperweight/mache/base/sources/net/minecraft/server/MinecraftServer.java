package net.minecraft.server;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.DataFixer;
import com.mojang.jtracy.DiscontinuousFrame;
import com.mojang.jtracy.TracyClient;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.net.Proxy;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportType;
import net.minecraft.ReportedException;
import net.minecraft.SharedConstants;
import net.minecraft.SystemReport;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.MiscOverworldFeatures;
import net.minecraft.gametest.framework.GameTestTicker;
import net.minecraft.nbt.Tag;
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.chat.ChatDecorator;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.obfuscate.DontObfuscate;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.bossevents.CustomBossEvents;
import net.minecraft.server.level.ChunkLoadCounter;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DemoMode;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.level.progress.ChunkLoadStatusView;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.server.network.ServerConnectionListener;
import net.minecraft.server.network.TextFilter;
import net.minecraft.server.notifications.NotificationManager;
import net.minecraft.server.notifications.ServerActivityMonitor;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.server.waypoints.ServerWaypointManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.Crypt;
import net.minecraft.util.CryptException;
import net.minecraft.util.FileUtil;
import net.minecraft.util.ModCheck;
import net.minecraft.util.Mth;
import net.minecraft.util.NativeModuleLister;
import net.minecraft.util.PngInfo;
import net.minecraft.util.RandomSource;
import net.minecraft.util.TimeUtil;
import net.minecraft.util.Util;
import net.minecraft.util.debug.ServerDebugSubscribers;
import net.minecraft.util.debugchart.SampleLogger;
import net.minecraft.util.debugchart.TpsDebugDimensions;
import net.minecraft.util.profiling.EmptyProfileResults;
import net.minecraft.util.profiling.ProfileResults;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.ResultField;
import net.minecraft.util.profiling.SingleTickProfiler;
import net.minecraft.util.profiling.jfr.Environment;
import net.minecraft.util.profiling.jfr.JvmProfiler;
import net.minecraft.util.profiling.jfr.callback.ProfiledDuration;
import net.minecraft.util.profiling.metrics.profiling.ActiveMetricsRecorder;
import net.minecraft.util.profiling.metrics.profiling.InactiveMetricsRecorder;
import net.minecraft.util.profiling.metrics.profiling.MetricsRecorder;
import net.minecraft.util.profiling.metrics.profiling.ServerMetricsSamplersProvider;
import net.minecraft.util.profiling.metrics.storage.MetricsPersister;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import net.minecraft.world.Difficulty;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.Stopwatches;
import net.minecraft.world.entity.ai.village.VillageSiege;
import net.minecraft.world.entity.npc.CatSpawner;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTraderSpawner;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.FuelValues;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkIOErrorReporter;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleTypeVisitor;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.CommandStorage;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.ScoreboardSaveData;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public abstract class MinecraftServer extends ReentrantBlockableEventLoop<TickTask> implements ServerInfo, CommandSource, ChunkIOErrorReporter {
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final String VANILLA_BRAND = "vanilla";
    private static final float AVERAGE_TICK_TIME_SMOOTHING = 0.8F;
    private static final int TICK_STATS_SPAN = 100;
    private static final long OVERLOADED_THRESHOLD_NANOS = 20L * TimeUtil.NANOSECONDS_PER_SECOND / 20L;
    private static final int OVERLOADED_TICKS_THRESHOLD = 20;
    private static final long OVERLOADED_WARNING_INTERVAL_NANOS = 10L * TimeUtil.NANOSECONDS_PER_SECOND;
    private static final int OVERLOADED_TICKS_WARNING_INTERVAL = 100;
    private static final long STATUS_EXPIRE_TIME_NANOS = 5L * TimeUtil.NANOSECONDS_PER_SECOND;
    private static final long PREPARE_LEVELS_DEFAULT_DELAY_NANOS = 10L * TimeUtil.NANOSECONDS_PER_MILLISECOND;
    private static final int MAX_STATUS_PLAYER_SAMPLE = 12;
    public static final int SPAWN_POSITION_SEARCH_RADIUS = 5;
    private static final int SERVER_ACTIVITY_MONITOR_SECONDS_BETWEEN_NOTIFICATIONS = 30;
    private static final int AUTOSAVE_INTERVAL = 6000;
    private static final int MIMINUM_AUTOSAVE_TICKS = 100;
    private static final int MAX_TICK_LATENCY = 3;
    public static final int ABSOLUTE_MAX_WORLD_SIZE = 29999984;
    public static final LevelSettings DEMO_SETTINGS = new LevelSettings(
        "Demo World", GameType.SURVIVAL, false, Difficulty.NORMAL, false, new GameRules(FeatureFlags.DEFAULT_FLAGS), WorldDataConfiguration.DEFAULT
    );
    public static final NameAndId ANONYMOUS_PLAYER_PROFILE = new NameAndId(Util.NIL_UUID, "Anonymous Player");
    public LevelStorageSource.LevelStorageAccess storageSource;
    public final PlayerDataStorage playerDataStorage;
    private final List<Runnable> tickables = Lists.newArrayList();
    private MetricsRecorder metricsRecorder = InactiveMetricsRecorder.INSTANCE;
    private Consumer<ProfileResults> onMetricsRecordingStopped = results -> this.stopRecordingMetrics();
    private Consumer<Path> onMetricsRecordingFinished = path -> {};
    private boolean willStartRecordingMetrics;
    private MinecraftServer.@Nullable TimeProfiler debugCommandProfiler;
    private boolean debugCommandProfilerDelayStart;
    private ServerConnectionListener connection;
    private final LevelLoadListener levelLoadListener;
    private @Nullable ServerStatus status;
    private ServerStatus.@Nullable Favicon statusIcon;
    private final RandomSource random = RandomSource.create();
    public final DataFixer fixerUpper;
    private String localIp;
    private int port = -1;
    private final LayeredRegistryAccess<RegistryLayer> registries;
    private Map<ResourceKey<Level>, ServerLevel> levels = Maps.newLinkedHashMap();
    private PlayerList playerList;
    private volatile boolean running = true;
    private boolean stopped;
    private int tickCount;
    private int ticksUntilAutosave = 6000;
    protected final Proxy proxy;
    private boolean onlineMode;
    private boolean preventProxyConnections;
    private @Nullable String motd;
    private int playerIdleTimeout;
    private final long[] tickTimesNanos = new long[100];
    private long aggregatedTickTimesNanos = 0L;
    private @Nullable KeyPair keyPair;
    private @Nullable GameProfile singleplayerProfile;
    private boolean isDemo;
    private volatile boolean isReady;
    private long lastOverloadWarningNanos;
    protected final Services services;
    private final NotificationManager notificationManager;
    private final ServerActivityMonitor serverActivityMonitor;
    private long lastServerStatus;
    public final Thread serverThread;
    private long lastTickNanos = Util.getNanos();
    private long taskExecutionStartNanos = Util.getNanos();
    private long idleTimeNanos;
    private long nextTickTimeNanos = Util.getNanos();
    private boolean waitingForNextTick = false;
    private long delayedTasksMaxNextTickTimeNanos;
    private boolean mayHaveDelayedTasks;
    private final PackRepository packRepository;
    private final ServerScoreboard scoreboard = new ServerScoreboard(this);
    private @Nullable Stopwatches stopwatches;
    private @Nullable CommandStorage commandStorage;
    private final CustomBossEvents customBossEvents = new CustomBossEvents();
    private final ServerFunctionManager functionManager;
    private boolean enforceWhitelist;
    private boolean usingWhitelist;
    private float smoothedTickTimeMillis;
    public final Executor executor;
    private @Nullable String serverId;
    public MinecraftServer.ReloadableResources resources;
    private final StructureTemplateManager structureTemplateManager;
    private final ServerTickRateManager tickRateManager;
    private final ServerDebugSubscribers debugSubscribers = new ServerDebugSubscribers(this);
    protected WorldData worldData;
    private LevelData.RespawnData effectiveRespawnData = LevelData.RespawnData.DEFAULT;
    public PotionBrewing potionBrewing;
    private FuelValues fuelValues;
    private int emptyTicks;
    private volatile boolean isSaving;
    private static final AtomicReference<@Nullable RuntimeException> fatalException = new AtomicReference<>();
    private final SuppressedExceptionCollector suppressedExceptions = new SuppressedExceptionCollector();
    private final DiscontinuousFrame tickFrame;
    private final PacketProcessor packetProcessor;

    public static <S extends MinecraftServer> S spin(Function<Thread, S> threadFunction) {
        AtomicReference<S> atomicReference = new AtomicReference<>();
        Thread thread = new Thread(() -> atomicReference.get().runServer(), "Server thread");
        thread.setUncaughtExceptionHandler((thread1, exception) -> LOGGER.error("Uncaught exception in server thread", exception));
        if (Runtime.getRuntime().availableProcessors() > 4) {
            thread.setPriority(8);
        }

        S minecraftServer = (S)threadFunction.apply(thread);
        atomicReference.set(minecraftServer);
        thread.start();
        return minecraftServer;
    }

    public MinecraftServer(
        Thread serverThread,
        LevelStorageSource.LevelStorageAccess storageSource,
        PackRepository packRepository,
        WorldStem worldStem,
        Proxy proxy,
        DataFixer fixerUpper,
        Services services,
        LevelLoadListener levelLoadListener
    ) {
        super("Server");
        this.registries = worldStem.registries();
        this.worldData = worldStem.worldData();
        if (!this.registries.compositeAccess().lookupOrThrow(Registries.LEVEL_STEM).containsKey(LevelStem.OVERWORLD)) {
            throw new IllegalStateException("Missing Overworld dimension data");
        } else {
            this.proxy = proxy;
            this.packRepository = packRepository;
            this.resources = new MinecraftServer.ReloadableResources(worldStem.resourceManager(), worldStem.dataPackResources());
            this.services = services;
            this.connection = new ServerConnectionListener(this);
            this.tickRateManager = new ServerTickRateManager(this);
            this.levelLoadListener = levelLoadListener;
            this.storageSource = storageSource;
            this.playerDataStorage = storageSource.createPlayerStorage();
            this.fixerUpper = fixerUpper;
            this.functionManager = new ServerFunctionManager(this, this.resources.managers.getFunctionLibrary());
            HolderGetter<Block> holderGetter = this.registries
                .compositeAccess()
                .lookupOrThrow(Registries.BLOCK)
                .filterFeatures(this.worldData.enabledFeatures());
            this.structureTemplateManager = new StructureTemplateManager(worldStem.resourceManager(), storageSource, fixerUpper, holderGetter);
            this.serverThread = serverThread;
            this.executor = Util.backgroundExecutor();
            this.potionBrewing = PotionBrewing.bootstrap(this.worldData.enabledFeatures());
            this.resources.managers.getRecipeManager().finalizeRecipeLoading(this.worldData.enabledFeatures());
            this.fuelValues = FuelValues.vanillaBurnTimes(this.registries.compositeAccess(), this.worldData.enabledFeatures());
            this.tickFrame = TracyClient.createDiscontinuousFrame("Server Tick");
            this.notificationManager = new NotificationManager();
            this.serverActivityMonitor = new ServerActivityMonitor(this.notificationManager, 30);
            this.packetProcessor = new PacketProcessor(serverThread);
        }
    }

    protected abstract boolean initServer() throws IOException;

    public ChunkLoadStatusView createChunkLoadStatusView(final int radius) {
        return new ChunkLoadStatusView() {
            private @Nullable ChunkMap chunkMap;
            private int centerChunkX;
            private int centerChunkZ;

            @Override
            public void moveTo(ResourceKey<Level> dimension, ChunkPos chunkPos) {
                ServerLevel level = MinecraftServer.this.getLevel(dimension);
                this.chunkMap = level != null ? level.getChunkSource().chunkMap : null;
                this.centerChunkX = chunkPos.x;
                this.centerChunkZ = chunkPos.z;
            }

            @Override
            public @Nullable ChunkStatus get(int x, int z) {
                return this.chunkMap == null
                    ? null
                    : this.chunkMap.getLatestStatus(ChunkPos.asLong(x + this.centerChunkX - radius, z + this.centerChunkZ - radius));
            }

            @Override
            public int radius() {
                return radius;
            }
        };
    }

    protected void loadLevel() {
        boolean flag = !JvmProfiler.INSTANCE.isRunning()
            && SharedConstants.DEBUG_JFR_PROFILING_ENABLE_LEVEL_LOADING
            && JvmProfiler.INSTANCE.start(Environment.from(this));
        ProfiledDuration profiledDuration = JvmProfiler.INSTANCE.onWorldLoadedStarted();
        this.worldData.setModdedInfo(this.getServerModName(), this.getModdedStatus().shouldReportAsModified());
        this.createLevels();
        this.forceDifficulty();
        this.prepareLevels();
        if (profiledDuration != null) {
            profiledDuration.finish(true);
        }

        if (flag) {
            try {
                JvmProfiler.INSTANCE.stop();
            } catch (Throwable var4) {
                LOGGER.warn("Failed to stop JFR profiling", var4);
            }
        }
    }

    protected void forceDifficulty() {
    }

    protected void createLevels() {
        ServerLevelData serverLevelData = this.worldData.overworldData();
        boolean isDebugWorld = this.worldData.isDebugWorld();
        Registry<LevelStem> registry = this.registries.compositeAccess().lookupOrThrow(Registries.LEVEL_STEM);
        WorldOptions worldOptions = this.worldData.worldGenOptions();
        long seed = worldOptions.seed();
        long l = BiomeManager.obfuscateSeed(seed);
        List<CustomSpawner> list = ImmutableList.of(
            new PhantomSpawner(), new PatrolSpawner(), new CatSpawner(), new VillageSiege(), new WanderingTraderSpawner(serverLevelData)
        );
        LevelStem levelStem = registry.getValue(LevelStem.OVERWORLD);
        ServerLevel serverLevel = new ServerLevel(
            this, this.executor, this.storageSource, serverLevelData, Level.OVERWORLD, levelStem, isDebugWorld, l, list, true, null
        );
        this.levels.put(Level.OVERWORLD, serverLevel);
        DimensionDataStorage dataStorage = serverLevel.getDataStorage();
        this.scoreboard.load(dataStorage.computeIfAbsent(ScoreboardSaveData.TYPE).getData());
        this.commandStorage = new CommandStorage(dataStorage);
        this.stopwatches = dataStorage.computeIfAbsent(Stopwatches.TYPE);
        if (!serverLevelData.isInitialized()) {
            try {
                setInitialSpawn(serverLevel, serverLevelData, worldOptions.generateBonusChest(), isDebugWorld, this.levelLoadListener);
                serverLevelData.setInitialized(true);
                if (isDebugWorld) {
                    this.setupDebugLevel(this.worldData);
                }
            } catch (Throwable var28) {
                CrashReport crashReport = CrashReport.forThrowable(var28, "Exception initializing level");

                try {
                    serverLevel.fillReportDetails(crashReport);
                } catch (Throwable var27) {
                }

                throw new ReportedException(crashReport);
            }

            serverLevelData.setInitialized(true);
        }

        GlobalPos globalPos = this.selectLevelLoadFocusPos();
        this.levelLoadListener.updateFocus(globalPos.dimension(), new ChunkPos(globalPos.pos()));
        if (this.worldData.getCustomBossEvents() != null) {
            this.getCustomBossEvents().load(this.worldData.getCustomBossEvents(), this.registryAccess());
        }

        RandomSequences randomSequences = serverLevel.getRandomSequences();
        boolean flag = false;

        for (Entry<ResourceKey<LevelStem>, LevelStem> entry : registry.entrySet()) {
            ResourceKey<LevelStem> resourceKey = entry.getKey();
            ServerLevel serverLevel1;
            if (resourceKey != LevelStem.OVERWORLD) {
                ResourceKey<Level> resourceKey1 = ResourceKey.create(Registries.DIMENSION, resourceKey.identifier());
                DerivedLevelData derivedLevelData = new DerivedLevelData(this.worldData, serverLevelData);
                serverLevel1 = new ServerLevel(
                    this,
                    this.executor,
                    this.storageSource,
                    derivedLevelData,
                    resourceKey1,
                    entry.getValue(),
                    isDebugWorld,
                    l,
                    ImmutableList.of(),
                    false,
                    randomSequences
                );
                this.levels.put(resourceKey1, serverLevel1);
            } else {
                serverLevel1 = serverLevel;
            }

            Optional<WorldBorder.Settings> legacyWorldBorderSettings = serverLevelData.getLegacyWorldBorderSettings();
            if (legacyWorldBorderSettings.isPresent()) {
                WorldBorder.Settings settings = legacyWorldBorderSettings.get();
                DimensionDataStorage dataStorage1 = serverLevel1.getDataStorage();
                if (dataStorage1.get(WorldBorder.TYPE) == null) {
                    double coordinateScale = serverLevel1.dimensionType().coordinateScale();
                    WorldBorder.Settings settings1 = new WorldBorder.Settings(
                        settings.centerX() / coordinateScale,
                        settings.centerZ() / coordinateScale,
                        settings.damagePerBlock(),
                        settings.safeZone(),
                        settings.warningBlocks(),
                        settings.warningTime(),
                        settings.size(),
                        settings.lerpTime(),
                        settings.lerpTarget()
                    );
                    WorldBorder worldBorder = new WorldBorder(settings1);
                    worldBorder.applyInitialSettings(serverLevel1.getGameTime());
                    dataStorage1.set(WorldBorder.TYPE, worldBorder);
                }

                flag = true;
            }

            serverLevel1.getWorldBorder().setAbsoluteMaxSize(this.getAbsoluteMaxWorldSize());
            this.getPlayerList().addWorldborderListener(serverLevel1);
        }

        if (flag) {
            serverLevelData.setLegacyWorldBorderSettings(Optional.empty());
        }
    }

    private static void setInitialSpawn(
        ServerLevel level, ServerLevelData levelData, boolean generateBonusChest, boolean debug, LevelLoadListener levelLoadListener
    ) {
        if (SharedConstants.DEBUG_ONLY_GENERATE_HALF_THE_WORLD && SharedConstants.DEBUG_WORLD_RECREATE) {
            levelData.setSpawn(LevelData.RespawnData.of(level.dimension(), new BlockPos(0, 64, -100), 0.0F, 0.0F));
        } else if (debug) {
            levelData.setSpawn(LevelData.RespawnData.of(level.dimension(), BlockPos.ZERO.above(80), 0.0F, 0.0F));
        } else {
            ServerChunkCache chunkSource = level.getChunkSource();
            ChunkPos chunkPos = new ChunkPos(chunkSource.randomState().sampler().findSpawnPosition());
            levelLoadListener.start(LevelLoadListener.Stage.PREPARE_GLOBAL_SPAWN, 0);
            levelLoadListener.updateFocus(level.dimension(), chunkPos);
            int spawnHeight = chunkSource.getGenerator().getSpawnHeight(level);
            if (spawnHeight < level.getMinY()) {
                BlockPos worldPosition = chunkPos.getWorldPosition();
                spawnHeight = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldPosition.getX() + 8, worldPosition.getZ() + 8);
            }

            levelData.setSpawn(LevelData.RespawnData.of(level.dimension(), chunkPos.getWorldPosition().offset(8, spawnHeight, 8), 0.0F, 0.0F));
            int i = 0;
            int i1 = 0;
            int i2 = 0;
            int i3 = -1;

            for (int i4 = 0; i4 < Mth.square(11); i4++) {
                if (i >= -5 && i <= 5 && i1 >= -5 && i1 <= 5) {
                    BlockPos spawnPosInChunk = PlayerSpawnFinder.getSpawnPosInChunk(level, new ChunkPos(chunkPos.x + i, chunkPos.z + i1));
                    if (spawnPosInChunk != null) {
                        levelData.setSpawn(LevelData.RespawnData.of(level.dimension(), spawnPosInChunk, 0.0F, 0.0F));
                        break;
                    }
                }

                if (i == i1 || i < 0 && i == -i1 || i > 0 && i == 1 - i1) {
                    int i5 = i2;
                    i2 = -i3;
                    i3 = i5;
                }

                i += i2;
                i1 += i3;
            }

            if (generateBonusChest) {
                level.registryAccess()
                    .lookup(Registries.CONFIGURED_FEATURE)
                    .flatMap(registry -> registry.get(MiscOverworldFeatures.BONUS_CHEST))
                    .ifPresent(holder -> holder.value().place(level, chunkSource.getGenerator(), level.random, levelData.getRespawnData().pos()));
            }

            levelLoadListener.finish(LevelLoadListener.Stage.PREPARE_GLOBAL_SPAWN);
        }
    }

    private void setupDebugLevel(WorldData worldData) {
        worldData.setDifficulty(Difficulty.PEACEFUL);
        worldData.setDifficultyLocked(true);
        ServerLevelData serverLevelData = worldData.overworldData();
        serverLevelData.setRaining(false);
        serverLevelData.setThundering(false);
        serverLevelData.setClearWeatherTime(1000000000);
        serverLevelData.setDayTime(6000L);
        serverLevelData.setGameType(GameType.SPECTATOR);
    }

    private void prepareLevels() {
        ChunkLoadCounter chunkLoadCounter = new ChunkLoadCounter();

        for (ServerLevel serverLevel : this.levels.values()) {
            chunkLoadCounter.track(serverLevel, () -> {
                TicketStorage ticketStorage = serverLevel.getDataStorage().get(TicketStorage.TYPE);
                if (ticketStorage != null) {
                    ticketStorage.activateAllDeactivatedTickets();
                }
            });
        }

        this.levelLoadListener.start(LevelLoadListener.Stage.LOAD_INITIAL_CHUNKS, chunkLoadCounter.totalChunks());

        do {
            this.levelLoadListener.update(LevelLoadListener.Stage.LOAD_INITIAL_CHUNKS, chunkLoadCounter.readyChunks(), chunkLoadCounter.totalChunks());
            this.nextTickTimeNanos = Util.getNanos() + PREPARE_LEVELS_DEFAULT_DELAY_NANOS;
            this.waitUntilNextTick();
        } while (chunkLoadCounter.pendingChunks() > 0);

        this.levelLoadListener.finish(LevelLoadListener.Stage.LOAD_INITIAL_CHUNKS);
        this.updateMobSpawningFlags();
        this.updateEffectiveRespawnData();
    }

    protected GlobalPos selectLevelLoadFocusPos() {
        return this.worldData.overworldData().getRespawnData().globalPos();
    }

    public GameType getDefaultGameType() {
        return this.worldData.getGameType();
    }

    public boolean isHardcore() {
        return this.worldData.isHardcore();
    }

    public abstract LevelBasedPermissionSet operatorUserPermissions();

    public abstract PermissionSet getFunctionCompilationPermissions();

    public abstract boolean shouldRconBroadcast();

    public boolean saveAllChunks(boolean suppressLogs, boolean flush, boolean force) {
        this.scoreboard.storeToSaveDataIfDirty(this.overworld().getDataStorage().computeIfAbsent(ScoreboardSaveData.TYPE));
        boolean flag = false;

        for (ServerLevel serverLevel : this.getAllLevels()) {
            if (!suppressLogs) {
                LOGGER.info("Saving chunks for level '{}'/{}", serverLevel, serverLevel.dimension().identifier());
            }

            serverLevel.save(null, flush, SharedConstants.DEBUG_DONT_SAVE_WORLD || serverLevel.noSave && !force);
            flag = true;
        }

        this.worldData.setCustomBossEvents(this.getCustomBossEvents().save(this.registryAccess()));
        this.storageSource.saveDataTag(this.registryAccess(), this.worldData, this.getPlayerList().getSingleplayerData());
        if (flush) {
            for (ServerLevel serverLevel : this.getAllLevels()) {
                LOGGER.info("ThreadedAnvilChunkStorage ({}): All chunks are saved", serverLevel.getChunkSource().chunkMap.getStorageName());
            }

            LOGGER.info("ThreadedAnvilChunkStorage: All dimensions are saved");
        }

        return flag;
    }

    public boolean saveEverything(boolean suppressLogs, boolean flush, boolean force) {
        boolean var4;
        try {
            this.isSaving = true;
            this.getPlayerList().saveAll();
            var4 = this.saveAllChunks(suppressLogs, flush, force);
        } finally {
            this.isSaving = false;
        }

        return var4;
    }

    @Override
    public void close() {
        this.stopServer();
    }

    public void stopServer() {
        this.packetProcessor.close();
        if (this.metricsRecorder.isRecording()) {
            this.cancelRecordingMetrics();
        }

        LOGGER.info("Stopping server");
        this.getConnection().stop();
        this.isSaving = true;
        if (this.playerList != null) {
            LOGGER.info("Saving players");
            this.playerList.saveAll();
            this.playerList.removeAll();
        }

        LOGGER.info("Saving worlds");

        for (ServerLevel serverLevel : this.getAllLevels()) {
            if (serverLevel != null) {
                serverLevel.noSave = false;
            }
        }

        while (this.levels.values().stream().anyMatch(level -> level.getChunkSource().chunkMap.hasWork())) {
            this.nextTickTimeNanos = Util.getNanos() + TimeUtil.NANOSECONDS_PER_MILLISECOND;

            for (ServerLevel serverLevelx : this.getAllLevels()) {
                serverLevelx.getChunkSource().deactivateTicketsOnClosing();
                serverLevelx.getChunkSource().tick(() -> true, false);
            }

            this.waitUntilNextTick();
        }

        this.saveAllChunks(false, true, false);

        for (ServerLevel serverLevelx : this.getAllLevels()) {
            if (serverLevelx != null) {
                try {
                    serverLevelx.close();
                } catch (IOException var5) {
                    LOGGER.error("Exception closing the level", (Throwable)var5);
                }
            }
        }

        this.isSaving = false;
        this.resources.close();

        try {
            this.storageSource.close();
        } catch (IOException var4) {
            LOGGER.error("Failed to unlock level {}", this.storageSource.getLevelId(), var4);
        }
    }

    public String getLocalIp() {
        return this.localIp;
    }

    public void setLocalIp(String localIp) {
        this.localIp = localIp;
    }

    public boolean isRunning() {
        return this.running;
    }

    public void halt(boolean waitForShutdown) {
        this.running = false;
        if (waitForShutdown) {
            try {
                this.serverThread.join();
            } catch (InterruptedException var3) {
                LOGGER.error("Error while shutting down", (Throwable)var3);
            }
        }
    }

    protected void runServer() {
        try {
            if (!this.initServer()) {
                throw new IllegalStateException("Failed to initialize server");
            }

            this.nextTickTimeNanos = Util.getNanos();
            this.statusIcon = this.loadStatusIcon().orElse(null);
            this.status = this.buildServerStatus();

            while (this.running) {
                long l;
                if (!this.isPaused() && this.tickRateManager.isSprinting() && this.tickRateManager.checkShouldSprintThisTick()) {
                    l = 0L;
                    this.nextTickTimeNanos = Util.getNanos();
                    this.lastOverloadWarningNanos = this.nextTickTimeNanos;
                } else {
                    l = this.tickRateManager.nanosecondsPerTick();
                    long l1 = Util.getNanos() - this.nextTickTimeNanos;
                    if (l1 > OVERLOADED_THRESHOLD_NANOS + 20L * l
                        && this.nextTickTimeNanos - this.lastOverloadWarningNanos >= OVERLOADED_WARNING_INTERVAL_NANOS + 100L * l) {
                        long l2 = l1 / l;
                        LOGGER.warn("Can't keep up! Is the server overloaded? Running {}ms or {} ticks behind", l1 / TimeUtil.NANOSECONDS_PER_MILLISECOND, l2);
                        this.nextTickTimeNanos += l2 * l;
                        this.lastOverloadWarningNanos = this.nextTickTimeNanos;
                    }
                }

                boolean flag = l == 0L;
                if (this.debugCommandProfilerDelayStart) {
                    this.debugCommandProfilerDelayStart = false;
                    this.debugCommandProfiler = new MinecraftServer.TimeProfiler(Util.getNanos(), this.tickCount);
                }

                this.nextTickTimeNanos += l;

                try (Profiler.Scope scope = Profiler.use(this.createProfiler())) {
                    this.processPacketsAndTick(flag);
                    ProfilerFiller profilerFiller = Profiler.get();
                    profilerFiller.push("nextTickWait");
                    this.mayHaveDelayedTasks = true;
                    this.delayedTasksMaxNextTickTimeNanos = Math.max(Util.getNanos() + l, this.nextTickTimeNanos);
                    this.startMeasuringTaskExecutionTime();
                    this.waitUntilNextTick();
                    this.finishMeasuringTaskExecutionTime();
                    if (flag) {
                        this.tickRateManager.endTickWork();
                    }

                    profilerFiller.pop();
                    this.logFullTickTime();
                } finally {
                    this.endMetricsRecordingTick();
                }

                this.isReady = true;
                JvmProfiler.INSTANCE.onServerTick(this.smoothedTickTimeMillis);
            }
        } catch (Throwable var69) {
            LOGGER.error("Encountered an unexpected exception", var69);
            CrashReport crashReport = constructOrExtractCrashReport(var69);
            this.fillSystemReport(crashReport.getSystemReport());
            Path path = this.getServerDirectory().resolve("crash-reports").resolve("crash-" + Util.getFilenameFormattedDateTime() + "-server.txt");
            if (crashReport.saveToFile(path, ReportType.CRASH)) {
                LOGGER.error("This crash report has been saved to: {}", path.toAbsolutePath());
            } else {
                LOGGER.error("We were unable to save this crash report to disk.");
            }

            this.onServerCrash(crashReport);
        } finally {
            try {
                this.stopped = true;
                this.stopServer();
            } catch (Throwable var64) {
                LOGGER.error("Exception stopping the server", var64);
            } finally {
                this.onServerExit();
            }
        }
    }

    private void logFullTickTime() {
        long nanos = Util.getNanos();
        if (this.isTickTimeLoggingEnabled()) {
            this.getTickTimeLogger().logSample(nanos - this.lastTickNanos);
        }

        this.lastTickNanos = nanos;
    }

    private void startMeasuringTaskExecutionTime() {
        if (this.isTickTimeLoggingEnabled()) {
            this.taskExecutionStartNanos = Util.getNanos();
            this.idleTimeNanos = 0L;
        }
    }

    private void finishMeasuringTaskExecutionTime() {
        if (this.isTickTimeLoggingEnabled()) {
            SampleLogger tickTimeLogger = this.getTickTimeLogger();
            tickTimeLogger.logPartialSample(Util.getNanos() - this.taskExecutionStartNanos - this.idleTimeNanos, TpsDebugDimensions.SCHEDULED_TASKS.ordinal());
            tickTimeLogger.logPartialSample(this.idleTimeNanos, TpsDebugDimensions.IDLE.ordinal());
        }
    }

    private static CrashReport constructOrExtractCrashReport(Throwable cause) {
        ReportedException reportedException = null;

        for (Throwable throwable = cause; throwable != null; throwable = throwable.getCause()) {
            if (throwable instanceof ReportedException reportedException1) {
                reportedException = reportedException1;
            }
        }

        CrashReport report;
        if (reportedException != null) {
            report = reportedException.getReport();
            if (reportedException != cause) {
                report.addCategory("Wrapped in").setDetailError("Wrapping exception", cause);
            }
        } else {
            report = new CrashReport("Exception in server tick loop", cause);
        }

        return report;
    }

    private boolean haveTime() {
        return this.runningTask() || Util.getNanos() < (this.mayHaveDelayedTasks ? this.delayedTasksMaxNextTickTimeNanos : this.nextTickTimeNanos);
    }

    public static boolean throwIfFatalException() {
        RuntimeException runtimeException = fatalException.get();
        if (runtimeException != null) {
            throw runtimeException;
        } else {
            return true;
        }
    }

    public static void setFatalException(RuntimeException fatalException) {
        MinecraftServer.fatalException.compareAndSet(null, fatalException);
    }

    @Override
    public void managedBlock(BooleanSupplier isDone) {
        super.managedBlock(() -> throwIfFatalException() && isDone.getAsBoolean());
    }

    public NotificationManager notificationManager() {
        return this.notificationManager;
    }

    protected void waitUntilNextTick() {
        this.runAllTasks();
        this.waitingForNextTick = true;

        try {
            this.managedBlock(() -> !this.haveTime());
        } finally {
            this.waitingForNextTick = false;
        }
    }

    @Override
    public void waitForTasks() {
        boolean isTickTimeLoggingEnabled = this.isTickTimeLoggingEnabled();
        long l = isTickTimeLoggingEnabled ? Util.getNanos() : 0L;
        long l1 = this.waitingForNextTick ? this.nextTickTimeNanos - Util.getNanos() : 100000L;
        LockSupport.parkNanos("waiting for tasks", l1);
        if (isTickTimeLoggingEnabled) {
            this.idleTimeNanos = this.idleTimeNanos + (Util.getNanos() - l);
        }
    }

    @Override
    public TickTask wrapRunnable(Runnable runnable) {
        return new TickTask(this.tickCount, runnable);
    }

    @Override
    protected boolean shouldRun(TickTask runnable) {
        return runnable.getTick() + 3 < this.tickCount || this.haveTime();
    }

    @Override
    public boolean pollTask() {
        boolean flag = this.pollTaskInternal();
        this.mayHaveDelayedTasks = flag;
        return flag;
    }

    private boolean pollTaskInternal() {
        if (super.pollTask()) {
            return true;
        } else {
            if (this.tickRateManager.isSprinting() || this.shouldRunAllTasks() || this.haveTime()) {
                for (ServerLevel serverLevel : this.getAllLevels()) {
                    if (serverLevel.getChunkSource().pollTask()) {
                        return true;
                    }
                }
            }

            return false;
        }
    }

    @Override
    public void doRunTask(TickTask task) {
        Profiler.get().incrementCounter("runTask");
        super.doRunTask(task);
    }

    private Optional<ServerStatus.Favicon> loadStatusIcon() {
        Optional<Path> optional = Optional.of(this.getFile("server-icon.png"))
            .filter(path -> Files.isRegularFile(path))
            .or(() -> this.storageSource.getIconFile().filter(path -> Files.isRegularFile(path)));
        return optional.flatMap(path -> {
            try {
                byte[] allBytes = Files.readAllBytes(path);
                PngInfo pngInfo = PngInfo.fromBytes(allBytes);
                if (pngInfo.width() == 64 && pngInfo.height() == 64) {
                    return Optional.of(new ServerStatus.Favicon(allBytes));
                } else {
                    throw new IllegalArgumentException("Invalid world icon size [" + pngInfo.width() + ", " + pngInfo.height() + "], but expected [64, 64]");
                }
            } catch (Exception var3) {
                LOGGER.error("Couldn't load server icon", (Throwable)var3);
                return Optional.empty();
            }
        });
    }

    public Optional<Path> getWorldScreenshotFile() {
        return this.storageSource.getIconFile();
    }

    public Path getServerDirectory() {
        return Path.of("");
    }

    public ServerActivityMonitor getServerActivityMonitor() {
        return this.serverActivityMonitor;
    }

    public void onServerCrash(CrashReport report) {
    }

    public void onServerExit() {
    }

    public boolean isPaused() {
        return false;
    }

    public void tickServer(BooleanSupplier hasTimeLeft) {
        long nanos = Util.getNanos();
        int i = this.pauseWhenEmptySeconds() * 20;
        if (i > 0) {
            if (this.playerList.getPlayerCount() == 0 && !this.tickRateManager.isSprinting()) {
                this.emptyTicks++;
            } else {
                this.emptyTicks = 0;
            }

            if (this.emptyTicks >= i) {
                if (this.emptyTicks == i) {
                    LOGGER.info("Server empty for {} seconds, pausing", this.pauseWhenEmptySeconds());
                    this.autoSave();
                }

                this.tickConnection();
                return;
            }
        }

        this.tickCount++;
        this.tickRateManager.tick();
        this.tickChildren(hasTimeLeft);
        if (nanos - this.lastServerStatus >= STATUS_EXPIRE_TIME_NANOS) {
            this.lastServerStatus = nanos;
            this.status = this.buildServerStatus();
        }

        this.ticksUntilAutosave--;
        if (this.ticksUntilAutosave <= 0) {
            this.autoSave();
        }

        ProfilerFiller profilerFiller = Profiler.get();
        profilerFiller.push("tallying");
        long l = Util.getNanos() - nanos;
        int i1 = this.tickCount % 100;
        this.aggregatedTickTimesNanos = this.aggregatedTickTimesNanos - this.tickTimesNanos[i1];
        this.aggregatedTickTimesNanos += l;
        this.tickTimesNanos[i1] = l;
        this.smoothedTickTimeMillis = this.smoothedTickTimeMillis * 0.8F + (float)l / (float)TimeUtil.NANOSECONDS_PER_MILLISECOND * 0.19999999F;
        this.logTickMethodTime(nanos);
        profilerFiller.pop();
    }

    protected void processPacketsAndTick(boolean sprinting) {
        ProfilerFiller profilerFiller = Profiler.get();
        profilerFiller.push("tick");
        this.tickFrame.start();
        profilerFiller.push("scheduledPacketProcessing");
        this.packetProcessor.processQueuedPackets();
        profilerFiller.pop();
        this.tickServer(sprinting ? () -> false : this::haveTime);
        this.tickFrame.end();
        profilerFiller.pop();
    }

    private void autoSave() {
        this.ticksUntilAutosave = this.computeNextAutosaveInterval();
        LOGGER.debug("Autosave started");
        ProfilerFiller profilerFiller = Profiler.get();
        profilerFiller.push("save");
        this.saveEverything(true, false, false);
        profilerFiller.pop();
        LOGGER.debug("Autosave finished");
    }

    private void logTickMethodTime(long startTime) {
        if (this.isTickTimeLoggingEnabled()) {
            this.getTickTimeLogger().logPartialSample(Util.getNanos() - startTime, TpsDebugDimensions.TICK_SERVER_METHOD.ordinal());
        }
    }

    private int computeNextAutosaveInterval() {
        float f;
        if (this.tickRateManager.isSprinting()) {
            long l = this.getAverageTickTimeNanos() + 1L;
            f = (float)TimeUtil.NANOSECONDS_PER_SECOND / (float)l;
        } else {
            f = this.tickRateManager.tickrate();
        }

        int i = 300;
        return Math.max(100, (int)(f * 300.0F));
    }

    public void onTickRateChanged() {
        int i = this.computeNextAutosaveInterval();
        if (i < this.ticksUntilAutosave) {
            this.ticksUntilAutosave = i;
        }
    }

    protected abstract SampleLogger getTickTimeLogger();

    public abstract boolean isTickTimeLoggingEnabled();

    private ServerStatus buildServerStatus() {
        ServerStatus.Players players = this.buildPlayerStatus();
        return new ServerStatus(
            Component.nullToEmpty(this.getMotd()),
            Optional.of(players),
            Optional.of(ServerStatus.Version.current()),
            Optional.ofNullable(this.statusIcon),
            this.enforceSecureProfile()
        );
    }

    private ServerStatus.Players buildPlayerStatus() {
        List<ServerPlayer> players = this.playerList.getPlayers();
        int maxPlayers = this.getMaxPlayers();
        if (this.hidesOnlinePlayers()) {
            return new ServerStatus.Players(maxPlayers, players.size(), List.of());
        } else {
            int min = Math.min(players.size(), 12);
            ObjectArrayList<NameAndId> list = new ObjectArrayList<>(min);
            int randomInt = Mth.nextInt(this.random, 0, players.size() - min);

            for (int i = 0; i < min; i++) {
                ServerPlayer serverPlayer = players.get(randomInt + i);
                list.add(serverPlayer.allowsListing() ? serverPlayer.nameAndId() : ANONYMOUS_PLAYER_PROFILE);
            }

            Util.shuffle(list, this.random);
            return new ServerStatus.Players(maxPlayers, players.size(), list);
        }
    }

    protected void tickChildren(BooleanSupplier hasTimeLeft) {
        ProfilerFiller profilerFiller = Profiler.get();
        this.getPlayerList().getPlayers().forEach(serverPlayer1 -> serverPlayer1.connection.suspendFlushing());
        profilerFiller.push("commandFunctions");
        this.getFunctions().tick();
        profilerFiller.popPush("levels");
        this.updateEffectiveRespawnData();

        for (ServerLevel serverLevel : this.getAllLevels()) {
            profilerFiller.push(() -> serverLevel + " " + serverLevel.dimension().identifier());
            if (this.tickCount % 20 == 0) {
                profilerFiller.push("timeSync");
                this.synchronizeTime(serverLevel);
                profilerFiller.pop();
            }

            profilerFiller.push("tick");

            try {
                serverLevel.tick(hasTimeLeft);
            } catch (Throwable var7) {
                CrashReport crashReport = CrashReport.forThrowable(var7, "Exception ticking world");
                serverLevel.fillReportDetails(crashReport);
                throw new ReportedException(crashReport);
            }

            profilerFiller.pop();
            profilerFiller.pop();
        }

        profilerFiller.popPush("connection");
        this.tickConnection();
        profilerFiller.popPush("players");
        this.playerList.tick();
        profilerFiller.popPush("debugSubscribers");
        this.debugSubscribers.tick();
        if (this.tickRateManager.runsNormally()) {
            profilerFiller.popPush("gameTests");
            GameTestTicker.SINGLETON.tick();
        }

        profilerFiller.popPush("server gui refresh");

        for (Runnable runnable : this.tickables) {
            runnable.run();
        }

        profilerFiller.popPush("send chunks");

        for (ServerPlayer serverPlayer : this.playerList.getPlayers()) {
            serverPlayer.connection.chunkSender.sendNextChunks(serverPlayer);
            serverPlayer.connection.resumeFlushing();
        }

        profilerFiller.pop();
        this.serverActivityMonitor.tick();
    }

    private void updateEffectiveRespawnData() {
        LevelData.RespawnData respawnData = this.worldData.overworldData().getRespawnData();
        ServerLevel serverLevel = this.findRespawnDimension();
        this.effectiveRespawnData = serverLevel.getWorldBorderAdjustedRespawnData(respawnData);
    }

    public void tickConnection() {
        this.getConnection().tick();
    }

    private void synchronizeTime(ServerLevel level) {
        this.playerList
            .broadcastAll(
                new ClientboundSetTimePacket(level.getGameTime(), level.getDayTime(), level.getGameRules().get(GameRules.ADVANCE_TIME)), level.dimension()
            );
    }

    public void forceTimeSynchronization() {
        ProfilerFiller profilerFiller = Profiler.get();
        profilerFiller.push("timeSync");

        for (ServerLevel serverLevel : this.getAllLevels()) {
            this.synchronizeTime(serverLevel);
        }

        profilerFiller.pop();
    }

    public void addTickable(Runnable tickable) {
        this.tickables.add(tickable);
    }

    protected void setId(String serverId) {
        this.serverId = serverId;
    }

    public boolean isShutdown() {
        return !this.serverThread.isAlive();
    }

    public Path getFile(String path) {
        return this.getServerDirectory().resolve(path);
    }

    public final ServerLevel overworld() {
        return this.levels.get(Level.OVERWORLD);
    }

    public @Nullable ServerLevel getLevel(ResourceKey<Level> dimension) {
        return this.levels.get(dimension);
    }

    public Set<ResourceKey<Level>> levelKeys() {
        return this.levels.keySet();
    }

    public Iterable<ServerLevel> getAllLevels() {
        return this.levels.values();
    }

    @Override
    public String getServerVersion() {
        return SharedConstants.getCurrentVersion().name();
    }

    @Override
    public int getPlayerCount() {
        return this.playerList.getPlayerCount();
    }

    public String[] getPlayerNames() {
        return this.playerList.getPlayerNamesArray();
    }

    @DontObfuscate
    public String getServerModName() {
        return "vanilla";
    }

    public SystemReport fillSystemReport(SystemReport systemReport) {
        systemReport.setDetail("Server Running", () -> Boolean.toString(this.running));
        if (this.playerList != null) {
            systemReport.setDetail(
                "Player Count", () -> this.playerList.getPlayerCount() + " / " + this.playerList.getMaxPlayers() + "; " + this.playerList.getPlayers()
            );
        }

        systemReport.setDetail("Active Data Packs", () -> PackRepository.displayPackList(this.packRepository.getSelectedPacks()));
        systemReport.setDetail("Available Data Packs", () -> PackRepository.displayPackList(this.packRepository.getAvailablePacks()));
        systemReport.setDetail(
            "Enabled Feature Flags",
            () -> FeatureFlags.REGISTRY.toNames(this.worldData.enabledFeatures()).stream().map(Identifier::toString).collect(Collectors.joining(", "))
        );
        systemReport.setDetail("World Generation", () -> this.worldData.worldGenSettingsLifecycle().toString());
        systemReport.setDetail("World Seed", () -> String.valueOf(this.worldData.worldGenOptions().seed()));
        systemReport.setDetail("Suppressed Exceptions", this.suppressedExceptions::dump);
        if (this.serverId != null) {
            systemReport.setDetail("Server Id", () -> this.serverId);
        }

        return this.fillServerSystemReport(systemReport);
    }

    public abstract SystemReport fillServerSystemReport(SystemReport report);

    public ModCheck getModdedStatus() {
        return ModCheck.identify("vanilla", this::getServerModName, "Server", MinecraftServer.class);
    }

    @Override
    public void sendSystemMessage(Component message) {
        LOGGER.info(message.getString());
    }

    public KeyPair getKeyPair() {
        return Objects.requireNonNull(this.keyPair);
    }

    public int getPort() {
        return this.port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public @Nullable GameProfile getSingleplayerProfile() {
        return this.singleplayerProfile;
    }

    public void setSingleplayerProfile(@Nullable GameProfile singleplayerProfile) {
        this.singleplayerProfile = singleplayerProfile;
    }

    public boolean isSingleplayer() {
        return this.singleplayerProfile != null;
    }

    protected void initializeKeyPair() {
        LOGGER.info("Generating keypair");

        try {
            this.keyPair = Crypt.generateKeyPair();
        } catch (CryptException var2) {
            throw new IllegalStateException("Failed to generate key pair", var2);
        }
    }

    public void setDifficulty(Difficulty difficulty, boolean forced) {
        if (forced || !this.worldData.isDifficultyLocked()) {
            this.worldData.setDifficulty(this.worldData.isHardcore() ? Difficulty.HARD : difficulty);
            this.updateMobSpawningFlags();
            this.getPlayerList().getPlayers().forEach(this::sendDifficultyUpdate);
        }
    }

    public int getScaledTrackingDistance(int trackingDistance) {
        return trackingDistance;
    }

    public void updateMobSpawningFlags() {
        for (ServerLevel serverLevel : this.getAllLevels()) {
            serverLevel.setSpawnSettings(serverLevel.isSpawningMonsters());
        }
    }

    public void setDifficultyLocked(boolean locked) {
        this.worldData.setDifficultyLocked(locked);
        this.getPlayerList().getPlayers().forEach(this::sendDifficultyUpdate);
    }

    private void sendDifficultyUpdate(ServerPlayer player) {
        LevelData levelData = player.level().getLevelData();
        player.connection.send(new ClientboundChangeDifficultyPacket(levelData.getDifficulty(), levelData.isDifficultyLocked()));
    }

    public boolean isDemo() {
        return this.isDemo;
    }

    public void setDemo(boolean demo) {
        this.isDemo = demo;
    }

    public Map<String, String> getCodeOfConducts() {
        return Map.of();
    }

    public Optional<MinecraftServer.ServerResourcePackInfo> getServerResourcePack() {
        return Optional.empty();
    }

    public boolean isResourcePackRequired() {
        return this.getServerResourcePack().filter(MinecraftServer.ServerResourcePackInfo::isRequired).isPresent();
    }

    public abstract boolean isDedicatedServer();

    public abstract int getRateLimitPacketsPerSecond();

    public boolean usesAuthentication() {
        return this.onlineMode;
    }

    public void setUsesAuthentication(boolean online) {
        this.onlineMode = online;
    }

    public boolean getPreventProxyConnections() {
        return this.preventProxyConnections;
    }

    public void setPreventProxyConnections(boolean preventProxyConnections) {
        this.preventProxyConnections = preventProxyConnections;
    }

    public abstract boolean useNativeTransport();

    public boolean allowFlight() {
        return true;
    }

    @Override
    public String getMotd() {
        return this.motd;
    }

    public void setMotd(String motd) {
        this.motd = motd;
    }

    public boolean isStopped() {
        return this.stopped;
    }

    public PlayerList getPlayerList() {
        return this.playerList;
    }

    public void setPlayerList(PlayerList list) {
        this.playerList = list;
    }

    public abstract boolean isPublished();

    public void setDefaultGameType(GameType gameMode) {
        this.worldData.setGameType(gameMode);
    }

    public int enforceGameTypeForPlayers(@Nullable GameType gameMode) {
        if (gameMode == null) {
            return 0;
        } else {
            int i = 0;

            for (ServerPlayer serverPlayer : this.getPlayerList().getPlayers()) {
                if (serverPlayer.setGameMode(gameMode)) {
                    i++;
                }
            }

            return i;
        }
    }

    public ServerConnectionListener getConnection() {
        return this.connection;
    }

    public boolean isReady() {
        return this.isReady;
    }

    public boolean publishServer(@Nullable GameType gameMode, boolean commands, int port) {
        return false;
    }

    public int getTickCount() {
        return this.tickCount;
    }

    public boolean isUnderSpawnProtection(ServerLevel level, BlockPos pos, Player player) {
        return false;
    }

    public boolean repliesToStatus() {
        return true;
    }

    public boolean hidesOnlinePlayers() {
        return false;
    }

    public Proxy getProxy() {
        return this.proxy;
    }

    public int playerIdleTimeout() {
        return this.playerIdleTimeout;
    }

    public void setPlayerIdleTimeout(int idleTimeout) {
        this.playerIdleTimeout = idleTimeout;
    }

    public Services services() {
        return this.services;
    }

    public @Nullable ServerStatus getStatus() {
        return this.status;
    }

    public void invalidateStatus() {
        this.lastServerStatus = 0L;
    }

    public int getAbsoluteMaxWorldSize() {
        return 29999984;
    }

    @Override
    public boolean scheduleExecutables() {
        return super.scheduleExecutables() && !this.isStopped();
    }

    @Override
    public void executeIfPossible(Runnable task) {
        if (this.isStopped()) {
            throw new RejectedExecutionException("Server already shutting down");
        } else {
            super.executeIfPossible(task);
        }
    }

    @Override
    public Thread getRunningThread() {
        return this.serverThread;
    }

    public int getCompressionThreshold() {
        return 256;
    }

    public boolean enforceSecureProfile() {
        return false;
    }

    public long getNextTickTime() {
        return this.nextTickTimeNanos;
    }

    public DataFixer getFixerUpper() {
        return this.fixerUpper;
    }

    public ServerAdvancementManager getAdvancements() {
        return this.resources.managers.getAdvancements();
    }

    public ServerFunctionManager getFunctions() {
        return this.functionManager;
    }

    public CompletableFuture<Void> reloadResources(Collection<String> selectedIds) {
        CompletableFuture<Void> completableFuture = CompletableFuture.<ImmutableList>supplyAsync(
                () -> selectedIds.stream().map(this.packRepository::getPack).filter(Objects::nonNull).map(Pack::open).collect(ImmutableList.toImmutableList()),
                this
            )
            .thenCompose(
                list -> {
                    CloseableResourceManager closeableResourceManager = new MultiPackResourceManager(PackType.SERVER_DATA, list);
                    List<Registry.PendingTags<?>> list1 = TagLoader.loadTagsForExistingRegistries(closeableResourceManager, this.registries.compositeAccess());
                    return ReloadableServerResources.loadResources(
                            closeableResourceManager,
                            this.registries,
                            list1,
                            this.worldData.enabledFeatures(),
                            this.isDedicatedServer() ? Commands.CommandSelection.DEDICATED : Commands.CommandSelection.INTEGRATED,
                            this.getFunctionCompilationPermissions(),
                            this.executor,
                            this
                        )
                        .whenComplete((reloadableServerResources, throwable) -> {
                            if (throwable != null) {
                                closeableResourceManager.close();
                            }
                        })
                        .thenApply(reloadableServerResources -> new MinecraftServer.ReloadableResources(closeableResourceManager, reloadableServerResources));
                }
            )
            .thenAcceptAsync(
                reloadableResources -> {
                    this.resources.close();
                    this.resources = reloadableResources;
                    this.packRepository.setSelected(selectedIds);
                    WorldDataConfiguration worldDataConfiguration = new WorldDataConfiguration(
                        getSelectedPacks(this.packRepository, true), this.worldData.enabledFeatures()
                    );
                    this.worldData.setDataConfiguration(worldDataConfiguration);
                    this.resources.managers.updateStaticRegistryTags();
                    this.resources.managers.getRecipeManager().finalizeRecipeLoading(this.worldData.enabledFeatures());
                    this.getPlayerList().saveAll();
                    this.getPlayerList().reloadResources();
                    this.functionManager.replaceLibrary(this.resources.managers.getFunctionLibrary());
                    this.structureTemplateManager.onResourceManagerReload(this.resources.resourceManager);
                    this.fuelValues = FuelValues.vanillaBurnTimes(this.registries.compositeAccess(), this.worldData.enabledFeatures());
                },
                this
            );
        if (this.isSameThread()) {
            this.managedBlock(completableFuture::isDone);
        }

        return completableFuture;
    }

    public static WorldDataConfiguration configurePackRepository(
        PackRepository packRepository, WorldDataConfiguration initialDataConfig, boolean initMode, boolean safeMode
    ) {
        DataPackConfig dataPackConfig = initialDataConfig.dataPacks();
        FeatureFlagSet featureFlagSet = initMode ? FeatureFlagSet.of() : initialDataConfig.enabledFeatures();
        FeatureFlagSet featureFlagSet1 = initMode ? FeatureFlags.REGISTRY.allFlags() : initialDataConfig.enabledFeatures();
        packRepository.reload();
        if (safeMode) {
            return configureRepositoryWithSelection(packRepository, List.of("vanilla"), featureFlagSet, false);
        } else {
            Set<String> set = Sets.newLinkedHashSet();

            for (String string : dataPackConfig.getEnabled()) {
                if (packRepository.isAvailable(string)) {
                    set.add(string);
                } else {
                    LOGGER.warn("Missing data pack {}", string);
                }
            }

            for (Pack pack : packRepository.getAvailablePacks()) {
                String id = pack.getId();
                if (!dataPackConfig.getDisabled().contains(id)) {
                    FeatureFlagSet requestedFeatures = pack.getRequestedFeatures();
                    boolean flag = set.contains(id);
                    if (!flag && pack.getPackSource().shouldAddAutomatically()) {
                        if (requestedFeatures.isSubsetOf(featureFlagSet1)) {
                            LOGGER.info("Found new data pack {}, loading it automatically", id);
                            set.add(id);
                        } else {
                            LOGGER.info(
                                "Found new data pack {}, but can't load it due to missing features {}",
                                id,
                                FeatureFlags.printMissingFlags(featureFlagSet1, requestedFeatures)
                            );
                        }
                    }

                    if (flag && !requestedFeatures.isSubsetOf(featureFlagSet1)) {
                        LOGGER.warn(
                            "Pack {} requires features {} that are not enabled for this world, disabling pack.",
                            id,
                            FeatureFlags.printMissingFlags(featureFlagSet1, requestedFeatures)
                        );
                        set.remove(id);
                    }
                }
            }

            if (set.isEmpty()) {
                LOGGER.info("No datapacks selected, forcing vanilla");
                set.add("vanilla");
            }

            return configureRepositoryWithSelection(packRepository, set, featureFlagSet, true);
        }
    }

    private static WorldDataConfiguration configureRepositoryWithSelection(
        PackRepository packRepository, Collection<String> selectedPacks, FeatureFlagSet enabledFeatures, boolean safeMode
    ) {
        packRepository.setSelected(selectedPacks);
        enableForcedFeaturePacks(packRepository, enabledFeatures);
        DataPackConfig selectedPacks1 = getSelectedPacks(packRepository, safeMode);
        FeatureFlagSet featureFlagSet = packRepository.getRequestedFeatureFlags().join(enabledFeatures);
        return new WorldDataConfiguration(selectedPacks1, featureFlagSet);
    }

    private static void enableForcedFeaturePacks(PackRepository packRepository, FeatureFlagSet enabledFeatures) {
        FeatureFlagSet requestedFeatureFlags = packRepository.getRequestedFeatureFlags();
        FeatureFlagSet featureFlagSet = enabledFeatures.subtract(requestedFeatureFlags);
        if (!featureFlagSet.isEmpty()) {
            Set<String> set = new ObjectArraySet<>(packRepository.getSelectedIds());

            for (Pack pack : packRepository.getAvailablePacks()) {
                if (featureFlagSet.isEmpty()) {
                    break;
                }

                if (pack.getPackSource() == PackSource.FEATURE) {
                    String id = pack.getId();
                    FeatureFlagSet requestedFeatures = pack.getRequestedFeatures();
                    if (!requestedFeatures.isEmpty() && requestedFeatures.intersects(featureFlagSet) && requestedFeatures.isSubsetOf(enabledFeatures)) {
                        if (!set.add(id)) {
                            throw new IllegalStateException("Tried to force '" + id + "', but it was already enabled");
                        }

                        LOGGER.info("Found feature pack ('{}') for requested feature, forcing to enabled", id);
                        featureFlagSet = featureFlagSet.subtract(requestedFeatures);
                    }
                }
            }

            packRepository.setSelected(set);
        }
    }

    private static DataPackConfig getSelectedPacks(PackRepository packRepository, boolean safeMode) {
        Collection<String> selectedIds = packRepository.getSelectedIds();
        List<String> list = ImmutableList.copyOf(selectedIds);
        List<String> list1 = safeMode ? packRepository.getAvailableIds().stream().filter(packId -> !selectedIds.contains(packId)).toList() : List.of();
        return new DataPackConfig(list, list1);
    }

    public void kickUnlistedPlayers() {
        if (this.isEnforceWhitelist() && this.isUsingWhitelist()) {
            PlayerList playerList = this.getPlayerList();
            UserWhiteList whiteList = playerList.getWhiteList();

            for (ServerPlayer serverPlayer : Lists.newArrayList(playerList.getPlayers())) {
                if (!whiteList.isWhiteListed(serverPlayer.nameAndId())) {
                    serverPlayer.connection.disconnect(Component.translatable("multiplayer.disconnect.not_whitelisted"));
                }
            }
        }
    }

    public PackRepository getPackRepository() {
        return this.packRepository;
    }

    public Commands getCommands() {
        return this.resources.managers.getCommands();
    }

    public CommandSourceStack createCommandSourceStack() {
        ServerLevel serverLevel = this.findRespawnDimension();
        return new CommandSourceStack(
            this,
            Vec3.atLowerCornerOf(this.getRespawnData().pos()),
            Vec2.ZERO,
            serverLevel,
            LevelBasedPermissionSet.OWNER,
            "Server",
            Component.literal("Server"),
            this,
            null
        );
    }

    public ServerLevel findRespawnDimension() {
        LevelData.RespawnData respawnData = this.getWorldData().overworldData().getRespawnData();
        ResourceKey<Level> resourceKey = respawnData.dimension();
        ServerLevel level = this.getLevel(resourceKey);
        return level != null ? level : this.overworld();
    }

    public void setRespawnData(LevelData.RespawnData respawnData) {
        ServerLevelData serverLevelData = this.worldData.overworldData();
        LevelData.RespawnData respawnData1 = serverLevelData.getRespawnData();
        if (!respawnData1.equals(respawnData)) {
            serverLevelData.setSpawn(respawnData);
            this.getPlayerList().broadcastAll(new ClientboundSetDefaultSpawnPositionPacket(respawnData));
            this.updateEffectiveRespawnData();
        }
    }

    public LevelData.RespawnData getRespawnData() {
        return this.effectiveRespawnData;
    }

    @Override
    public boolean acceptsSuccess() {
        return true;
    }

    @Override
    public boolean acceptsFailure() {
        return true;
    }

    @Override
    public abstract boolean shouldInformAdmins();

    public RecipeManager getRecipeManager() {
        return this.resources.managers.getRecipeManager();
    }

    public ServerScoreboard getScoreboard() {
        return this.scoreboard;
    }

    public CommandStorage getCommandStorage() {
        if (this.commandStorage == null) {
            throw new NullPointerException("Called before server init");
        } else {
            return this.commandStorage;
        }
    }

    public Stopwatches getStopwatches() {
        if (this.stopwatches == null) {
            throw new NullPointerException("Called before server init");
        } else {
            return this.stopwatches;
        }
    }

    public CustomBossEvents getCustomBossEvents() {
        return this.customBossEvents;
    }

    public boolean isEnforceWhitelist() {
        return this.enforceWhitelist;
    }

    public void setEnforceWhitelist(boolean enforceWhitelist) {
        this.enforceWhitelist = enforceWhitelist;
    }

    public boolean isUsingWhitelist() {
        return this.usingWhitelist;
    }

    public void setUsingWhitelist(boolean usingWhitelist) {
        this.usingWhitelist = usingWhitelist;
    }

    public float getCurrentSmoothedTickTime() {
        return this.smoothedTickTimeMillis;
    }

    public ServerTickRateManager tickRateManager() {
        return this.tickRateManager;
    }

    public long getAverageTickTimeNanos() {
        return this.aggregatedTickTimesNanos / Math.min(100, Math.max(this.tickCount, 1));
    }

    public long[] getTickTimesNanos() {
        return this.tickTimesNanos;
    }

    public LevelBasedPermissionSet getProfilePermissions(NameAndId nameAndId) {
        if (this.getPlayerList().isOp(nameAndId)) {
            ServerOpListEntry serverOpListEntry = this.getPlayerList().getOps().get(nameAndId);
            if (serverOpListEntry != null) {
                return serverOpListEntry.permissions();
            } else if (this.isSingleplayerOwner(nameAndId)) {
                return LevelBasedPermissionSet.OWNER;
            } else if (this.isSingleplayer()) {
                return this.getPlayerList().isAllowCommandsForAllPlayers() ? LevelBasedPermissionSet.OWNER : LevelBasedPermissionSet.ALL;
            } else {
                return this.operatorUserPermissions();
            }
        } else {
            return LevelBasedPermissionSet.ALL;
        }
    }

    public abstract boolean isSingleplayerOwner(NameAndId nameAndId);

    public void dumpServerProperties(Path path) throws IOException {
    }

    private void saveDebugReport(Path path) {
        Path path1 = path.resolve("levels");

        try {
            for (Entry<ResourceKey<Level>, ServerLevel> entry : this.levels.entrySet()) {
                Identifier identifier = entry.getKey().identifier();
                Path path2 = path1.resolve(identifier.getNamespace()).resolve(identifier.getPath());
                Files.createDirectories(path2);
                entry.getValue().saveDebugReport(path2);
            }

            this.dumpGameRules(path.resolve("gamerules.txt"));
            this.dumpClasspath(path.resolve("classpath.txt"));
            this.dumpMiscStats(path.resolve("stats.txt"));
            this.dumpThreads(path.resolve("threads.txt"));
            this.dumpServerProperties(path.resolve("server.properties.txt"));
            this.dumpNativeModules(path.resolve("modules.txt"));
        } catch (IOException var7) {
            LOGGER.warn("Failed to save debug report", (Throwable)var7);
        }
    }

    private void dumpMiscStats(Path path) throws IOException {
        try (Writer bufferedWriter = Files.newBufferedWriter(path)) {
            bufferedWriter.write(String.format(Locale.ROOT, "pending_tasks: %d\n", this.getPendingTasksCount()));
            bufferedWriter.write(String.format(Locale.ROOT, "average_tick_time: %f\n", this.getCurrentSmoothedTickTime()));
            bufferedWriter.write(String.format(Locale.ROOT, "tick_times: %s\n", Arrays.toString(this.tickTimesNanos)));
            bufferedWriter.write(String.format(Locale.ROOT, "queue: %s\n", Util.backgroundExecutor()));
        }
    }

    private void dumpGameRules(Path path) throws IOException {
        try (Writer bufferedWriter = Files.newBufferedWriter(path)) {
            final List<String> list = Lists.newArrayList();
            final GameRules gameRules = this.worldData.getGameRules();
            gameRules.visitGameRuleTypes(new GameRuleTypeVisitor() {
                @Override
                public <T> void visit(GameRule<T> rule) {
                    list.add(String.format(Locale.ROOT, "%s=%s\n", rule.getIdentifier(), gameRules.getAsString(rule)));
                }
            });

            for (String string : list) {
                bufferedWriter.write(string);
            }
        }
    }

    private void dumpClasspath(Path path) throws IOException {
        try (Writer bufferedWriter = Files.newBufferedWriter(path)) {
            String property = System.getProperty("java.class.path");
            String string = File.pathSeparator;

            for (String string1 : Splitter.on(string).split(property)) {
                bufferedWriter.write(string1);
                bufferedWriter.write("\n");
            }
        }
    }

    private void dumpThreads(Path path) throws IOException {
        ThreadMXBean threadMxBean = ManagementFactory.getThreadMXBean();
        ThreadInfo[] threadInfos = threadMxBean.dumpAllThreads(true, true);
        Arrays.sort(threadInfos, Comparator.comparing(ThreadInfo::getThreadName));

        try (Writer bufferedWriter = Files.newBufferedWriter(path)) {
            for (ThreadInfo threadInfo : threadInfos) {
                bufferedWriter.write(threadInfo.toString());
                bufferedWriter.write(10);
            }
        }
    }

    private void dumpNativeModules(Path path) throws IOException {
        try (Writer bufferedWriter = Files.newBufferedWriter(path)) {
            List<NativeModuleLister.NativeModuleInfo> list;
            try {
                list = Lists.newArrayList(NativeModuleLister.listModules());
            } catch (Throwable var7) {
                LOGGER.warn("Failed to list native modules", var7);
                return;
            }

            list.sort(Comparator.comparing(nativeModuleInfo1 -> nativeModuleInfo1.name));

            for (NativeModuleLister.NativeModuleInfo nativeModuleInfo : list) {
                bufferedWriter.write(nativeModuleInfo.toString());
                bufferedWriter.write(10);
            }
        }
    }

    private ProfilerFiller createProfiler() {
        if (this.willStartRecordingMetrics) {
            this.metricsRecorder = ActiveMetricsRecorder.createStarted(
                new ServerMetricsSamplersProvider(Util.timeSource, this.isDedicatedServer()),
                Util.timeSource,
                Util.ioPool(),
                new MetricsPersister("server"),
                this.onMetricsRecordingStopped,
                path -> {
                    this.executeBlocking(() -> this.saveDebugReport(path.resolve("server")));
                    this.onMetricsRecordingFinished.accept(path);
                }
            );
            this.willStartRecordingMetrics = false;
        }

        this.metricsRecorder.startTick();
        return SingleTickProfiler.decorateFiller(this.metricsRecorder.getProfiler(), SingleTickProfiler.createTickProfiler("Server"));
    }

    public void endMetricsRecordingTick() {
        this.metricsRecorder.endTick();
    }

    public boolean isRecordingMetrics() {
        return this.metricsRecorder.isRecording();
    }

    public void startRecordingMetrics(Consumer<ProfileResults> output, Consumer<Path> onMetricsRecordingFinished) {
        this.onMetricsRecordingStopped = profileResults -> {
            this.stopRecordingMetrics();
            output.accept(profileResults);
        };
        this.onMetricsRecordingFinished = onMetricsRecordingFinished;
        this.willStartRecordingMetrics = true;
    }

    public void stopRecordingMetrics() {
        this.metricsRecorder = InactiveMetricsRecorder.INSTANCE;
    }

    public void finishRecordingMetrics() {
        this.metricsRecorder.end();
    }

    public void cancelRecordingMetrics() {
        this.metricsRecorder.cancel();
    }

    public Path getWorldPath(LevelResource levelResource) {
        return this.storageSource.getLevelPath(levelResource);
    }

    public boolean forceSynchronousWrites() {
        return true;
    }

    public StructureTemplateManager getStructureManager() {
        return this.structureTemplateManager;
    }

    public WorldData getWorldData() {
        return this.worldData;
    }

    public RegistryAccess.Frozen registryAccess() {
        return this.registries.compositeAccess();
    }

    public LayeredRegistryAccess<RegistryLayer> registries() {
        return this.registries;
    }

    public ReloadableServerRegistries.Holder reloadableRegistries() {
        return this.resources.managers.fullRegistries();
    }

    public TextFilter createTextFilterForPlayer(ServerPlayer player) {
        return TextFilter.DUMMY;
    }

    public ServerPlayerGameMode createGameModeForPlayer(ServerPlayer player) {
        return (ServerPlayerGameMode)(this.isDemo() ? new DemoMode(player) : new ServerPlayerGameMode(player));
    }

    public @Nullable GameType getForcedGameType() {
        return null;
    }

    public ResourceManager getResourceManager() {
        return this.resources.resourceManager;
    }

    public boolean isCurrentlySaving() {
        return this.isSaving;
    }

    public boolean isTimeProfilerRunning() {
        return this.debugCommandProfilerDelayStart || this.debugCommandProfiler != null;
    }

    public void startTimeProfiler() {
        this.debugCommandProfilerDelayStart = true;
    }

    public ProfileResults stopTimeProfiler() {
        if (this.debugCommandProfiler == null) {
            return EmptyProfileResults.EMPTY;
        } else {
            ProfileResults profileResults = this.debugCommandProfiler.stop(Util.getNanos(), this.tickCount);
            this.debugCommandProfiler = null;
            return profileResults;
        }
    }

    public int getMaxChainedNeighborUpdates() {
        return 1000000;
    }

    public void logChatMessage(Component content, ChatType.Bound boundChatType, @Nullable String header) {
        String string = boundChatType.decorate(content).getString();
        if (header != null) {
            LOGGER.info("[{}] {}", header, string);
        } else {
            LOGGER.info("{}", string);
        }
    }

    public ChatDecorator getChatDecorator() {
        return ChatDecorator.PLAIN;
    }

    public boolean logIPs() {
        return true;
    }

    public void handleCustomClickAction(Identifier id, Optional<Tag> payload) {
        LOGGER.debug("Received custom click action {} with payload {}", id, payload.orElse(null));
    }

    public LevelLoadListener getLevelLoadListener() {
        return this.levelLoadListener;
    }

    public boolean setAutoSave(boolean autoSave) {
        boolean flag = false;

        for (ServerLevel serverLevel : this.getAllLevels()) {
            if (serverLevel != null && serverLevel.noSave == autoSave) {
                serverLevel.noSave = !autoSave;
                flag = true;
            }
        }

        return flag;
    }

    public boolean isAutoSave() {
        for (ServerLevel serverLevel : this.getAllLevels()) {
            if (serverLevel != null && !serverLevel.noSave) {
                return true;
            }
        }

        return false;
    }

    public <T> void onGameRuleChanged(GameRule<T> rule, T value) {
        this.notificationManager().onGameRuleChanged(rule, value);
        if (rule == GameRules.REDUCED_DEBUG_INFO) {
            byte b = (byte)((Boolean)value ? 22 : 23);

            for (ServerPlayer serverPlayer : this.getPlayerList().getPlayers()) {
                serverPlayer.connection.send(new ClientboundEntityEventPacket(serverPlayer, b));
            }
        } else if (rule == GameRules.LIMITED_CRAFTING || rule == GameRules.IMMEDIATE_RESPAWN) {
            ClientboundGameEventPacket.Type type = rule == GameRules.LIMITED_CRAFTING
                ? ClientboundGameEventPacket.LIMITED_CRAFTING
                : ClientboundGameEventPacket.IMMEDIATE_RESPAWN;
            ClientboundGameEventPacket clientboundGameEventPacket = new ClientboundGameEventPacket(type, (Boolean)value ? 1.0F : 0.0F);
            this.getPlayerList().getPlayers().forEach(serverPlayer1 -> serverPlayer1.connection.send(clientboundGameEventPacket));
        } else if (rule == GameRules.LOCATOR_BAR) {
            this.getAllLevels().forEach(serverLevel -> {
                ServerWaypointManager waypointManager = serverLevel.getWaypointManager();
                if ((Boolean)value) {
                    serverLevel.players().forEach(waypointManager::updatePlayer);
                } else {
                    waypointManager.breakAllConnections();
                }
            });
        } else if (rule == GameRules.SPAWN_MONSTERS) {
            this.updateMobSpawningFlags();
        }
    }

    public boolean acceptsTransfers() {
        return false;
    }

    private void storeChunkIoError(CrashReport crashReport, ChunkPos chunkPos, RegionStorageInfo regionStorageInfo) {
        Util.ioPool().execute(() -> {
            try {
                Path file = this.getFile("debug");
                FileUtil.createDirectoriesSafe(file);
                String string = FileUtil.sanitizeName(regionStorageInfo.level());
                Path path = file.resolve("chunk-" + string + "-" + Util.getFilenameFormattedDateTime() + "-server.txt");
                FileStore fileStore = Files.getFileStore(file);
                long usableSpace = fileStore.getUsableSpace();
                if (usableSpace < 8192L) {
                    LOGGER.warn("Not storing chunk IO report due to low space on drive {}", fileStore.name());
                    return;
                }

                CrashReportCategory crashReportCategory = crashReport.addCategory("Chunk Info");
                crashReportCategory.setDetail("Level", regionStorageInfo::level);
                crashReportCategory.setDetail("Dimension", () -> regionStorageInfo.dimension().identifier().toString());
                crashReportCategory.setDetail("Storage", regionStorageInfo::type);
                crashReportCategory.setDetail("Position", chunkPos::toString);
                crashReport.saveToFile(path, ReportType.CHUNK_IO_ERROR);
                LOGGER.info("Saved details to {}", crashReport.getSaveFile());
            } catch (Exception var11) {
                LOGGER.warn("Failed to store chunk IO exception", (Throwable)var11);
            }
        });
    }

    @Override
    public void reportChunkLoadFailure(Throwable throwable, RegionStorageInfo regionStorageInfo, ChunkPos chunkPos) {
        LOGGER.error("Failed to load chunk {},{}", chunkPos.x, chunkPos.z, throwable);
        this.suppressedExceptions.addEntry("chunk/load", throwable);
        this.storeChunkIoError(CrashReport.forThrowable(throwable, "Chunk load failure"), chunkPos, regionStorageInfo);
    }

    @Override
    public void reportChunkSaveFailure(Throwable throwable, RegionStorageInfo regionStorageInfo, ChunkPos chunkPos) {
        LOGGER.error("Failed to save chunk {},{}", chunkPos.x, chunkPos.z, throwable);
        this.suppressedExceptions.addEntry("chunk/save", throwable);
        this.storeChunkIoError(CrashReport.forThrowable(throwable, "Chunk save failure"), chunkPos, regionStorageInfo);
    }

    public void reportPacketHandlingException(Throwable throwable, PacketType<?> packetType) {
        this.suppressedExceptions.addEntry("packet/" + packetType, throwable);
    }

    public PotionBrewing potionBrewing() {
        return this.potionBrewing;
    }

    public FuelValues fuelValues() {
        return this.fuelValues;
    }

    public ServerLinks serverLinks() {
        return ServerLinks.EMPTY;
    }

    protected int pauseWhenEmptySeconds() {
        return 0;
    }

    public PacketProcessor packetProcessor() {
        return this.packetProcessor;
    }

    public ServerDebugSubscribers debugSubscribers() {
        return this.debugSubscribers;
    }

    public record ReloadableResources(CloseableResourceManager resourceManager, ReloadableServerResources managers) implements AutoCloseable {
        @Override
        public void close() {
            this.resourceManager.close();
        }
    }

    public record ServerResourcePackInfo(UUID id, String url, String hash, boolean isRequired, @Nullable Component prompt) {
    }

    static class TimeProfiler {
        final long startNanos;
        final int startTick;

        TimeProfiler(long startNanos, int startTick) {
            this.startNanos = startNanos;
            this.startTick = startTick;
        }

        ProfileResults stop(final long endTimeNano, final int endTimeTicks) {
            return new ProfileResults() {
                @Override
                public List<ResultField> getTimes(String sectionPath) {
                    return Collections.emptyList();
                }

                @Override
                public boolean saveResults(Path path) {
                    return false;
                }

                @Override
                public long getStartTimeNano() {
                    return TimeProfiler.this.startNanos;
                }

                @Override
                public int getStartTimeTicks() {
                    return TimeProfiler.this.startTick;
                }

                @Override
                public long getEndTimeNano() {
                    return endTimeNano;
                }

                @Override
                public int getEndTimeTicks() {
                    return endTimeTicks;
                }

                @Override
                public String getProfilerResults() {
                    return "";
                }
            };
        }
    }
}
