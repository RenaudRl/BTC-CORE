package net.minecraft.server.level;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;
import com.google.common.collect.ImmutableList.Builder;
import com.mojang.datafixers.DataFixer;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtException;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.util.CsvOutput;
import net.minecraft.util.Mth;
import net.minecraft.util.StaticCache2D;
import net.minecraft.util.TriState;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.util.thread.ConsecutiveExecutor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.LegacyStructureDataHandler;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class ChunkMap extends SimpleRegionStorage implements ChunkHolder.PlayerProvider, GeneratingChunkMap {
    private static final ChunkResult<List<ChunkAccess>> UNLOADED_CHUNK_LIST_RESULT = ChunkResult.error("Unloaded chunks found in range");
    private static final CompletableFuture<ChunkResult<List<ChunkAccess>>> UNLOADED_CHUNK_LIST_FUTURE = CompletableFuture.completedFuture(
        UNLOADED_CHUNK_LIST_RESULT
    );
    private static final byte CHUNK_TYPE_REPLACEABLE = -1;
    private static final byte CHUNK_TYPE_UNKNOWN = 0;
    private static final byte CHUNK_TYPE_FULL = 1;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CHUNK_SAVED_PER_TICK = 200;
    private static final int CHUNK_SAVED_EAGERLY_PER_TICK = 20;
    private static final int EAGER_CHUNK_SAVE_COOLDOWN_IN_MILLIS = 10000;
    private static final int MAX_ACTIVE_CHUNK_WRITES = 128;
    public static final int MIN_VIEW_DISTANCE = 2;
    public static final int MAX_VIEW_DISTANCE = 32;
    public static final int FORCED_TICKET_LEVEL = ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING);
    public final Long2ObjectLinkedOpenHashMap<ChunkHolder> updatingChunkMap = new Long2ObjectLinkedOpenHashMap<>();
    public volatile Long2ObjectLinkedOpenHashMap<ChunkHolder> visibleChunkMap = this.updatingChunkMap.clone();
    private final Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads = new Long2ObjectLinkedOpenHashMap<>();
    private final List<ChunkGenerationTask> pendingGenerationTasks = new ArrayList<>();
    public final ServerLevel level;
    private final ThreadedLevelLightEngine lightEngine;
    private final BlockableEventLoop<Runnable> mainThreadExecutor;
    private final RandomState randomState;
    private final ChunkGeneratorStructureState chunkGeneratorState;
    private final TicketStorage ticketStorage;
    private final PoiManager poiManager;
    public final LongSet toDrop = new LongOpenHashSet();
    private boolean modified;
    private final ChunkTaskDispatcher worldgenTaskDispatcher;
    private final ChunkTaskDispatcher lightTaskDispatcher;
    private final ChunkStatusUpdateListener chunkStatusListener;
    public final ChunkMap.DistanceManager distanceManager;
    private final String storageName;
    private final PlayerMap playerMap = new PlayerMap();
    public final Int2ObjectMap<ChunkMap.TrackedEntity> entityMap = new Int2ObjectOpenHashMap<>();
    private final Long2ByteMap chunkTypeCache = new Long2ByteOpenHashMap();
    private final Long2LongMap nextChunkSaveTime = new Long2LongOpenHashMap();
    private final LongSet chunksToEagerlySave = new LongLinkedOpenHashSet();
    private final Queue<Runnable> unloadQueue = Queues.newConcurrentLinkedQueue();
    private final AtomicInteger activeChunkWrites = new AtomicInteger();
    public int serverViewDistance;
    private final WorldGenContext worldGenContext;

    public ChunkMap(
        ServerLevel level,
        LevelStorageSource.LevelStorageAccess levelStorageAccess,
        DataFixer fixerUpper,
        StructureTemplateManager structureManager,
        Executor dispatcher,
        BlockableEventLoop<Runnable> mainThreadExecutor,
        LightChunkGetter lightChunk,
        ChunkGenerator generator,
        ChunkStatusUpdateListener chunkStatusListener,
        Supplier<DimensionDataStorage> overworldDataStorage,
        TicketStorage ticketStorage,
        int serverViewDistance,
        boolean sync
    ) {
        super(
            new RegionStorageInfo(levelStorageAccess.getLevelId(), level.dimension(), "chunk"),
            levelStorageAccess.getDimensionPath(level.dimension()).resolve("region"),
            fixerUpper,
            sync,
            DataFixTypes.CHUNK,
            LegacyStructureDataHandler.getLegacyTagFixer(level.dimension(), overworldDataStorage, fixerUpper)
        );
        Path dimensionPath = levelStorageAccess.getDimensionPath(level.dimension());
        this.storageName = dimensionPath.getFileName().toString();
        this.level = level;
        RegistryAccess registryAccess = level.registryAccess();
        long seed = level.getSeed();
        if (generator instanceof NoiseBasedChunkGenerator noiseBasedChunkGenerator) {
            this.randomState = RandomState.create(noiseBasedChunkGenerator.generatorSettings().value(), registryAccess.lookupOrThrow(Registries.NOISE), seed);
        } else {
            this.randomState = RandomState.create(NoiseGeneratorSettings.dummy(), registryAccess.lookupOrThrow(Registries.NOISE), seed);
        }

        this.chunkGeneratorState = generator.createState(registryAccess.lookupOrThrow(Registries.STRUCTURE_SET), this.randomState, seed);
        this.mainThreadExecutor = mainThreadExecutor;
        ConsecutiveExecutor consecutiveExecutor = new ConsecutiveExecutor(dispatcher, "worldgen");
        this.chunkStatusListener = chunkStatusListener;
        ConsecutiveExecutor consecutiveExecutor1 = new ConsecutiveExecutor(dispatcher, "light");
        this.worldgenTaskDispatcher = new ChunkTaskDispatcher(consecutiveExecutor, dispatcher);
        this.lightTaskDispatcher = new ChunkTaskDispatcher(consecutiveExecutor1, dispatcher);
        this.lightEngine = new ThreadedLevelLightEngine(
            lightChunk, this, this.level.dimensionType().hasSkyLight(), consecutiveExecutor1, this.lightTaskDispatcher
        );
        this.distanceManager = new ChunkMap.DistanceManager(ticketStorage, dispatcher, mainThreadExecutor);
        this.ticketStorage = ticketStorage;
        this.poiManager = new PoiManager(
            new RegionStorageInfo(levelStorageAccess.getLevelId(), level.dimension(), "poi"),
            dimensionPath.resolve("poi"),
            fixerUpper,
            sync,
            registryAccess,
            level.getServer(),
            level
        );
        this.setServerViewDistance(serverViewDistance);
        this.worldGenContext = new WorldGenContext(level, generator, structureManager, this.lightEngine, mainThreadExecutor, this::setChunkUnsaved);
    }

    private void setChunkUnsaved(ChunkPos chunkPos) {
        this.chunksToEagerlySave.add(chunkPos.toLong());
    }

    protected ChunkGenerator generator() {
        return this.worldGenContext.generator();
    }

    protected ChunkGeneratorStructureState generatorState() {
        return this.chunkGeneratorState;
    }

    protected RandomState randomState() {
        return this.randomState;
    }

    public boolean isChunkTracked(ServerPlayer player, int x, int z) {
        return player.getChunkTrackingView().contains(x, z) && !player.connection.chunkSender.isPending(ChunkPos.asLong(x, z));
    }

    private boolean isChunkOnTrackedBorder(ServerPlayer player, int x, int z) {
        if (!this.isChunkTracked(player, x, z)) {
            return false;
        } else {
            for (int i = -1; i <= 1; i++) {
                for (int i1 = -1; i1 <= 1; i1++) {
                    if ((i != 0 || i1 != 0) && !this.isChunkTracked(player, x + i, z + i1)) {
                        return true;
                    }
                }
            }

            return false;
        }
    }

    protected ThreadedLevelLightEngine getLightEngine() {
        return this.lightEngine;
    }

    public @Nullable ChunkHolder getUpdatingChunkIfPresent(long chunkPos) {
        return this.updatingChunkMap.get(chunkPos);
    }

    public @Nullable ChunkHolder getVisibleChunkIfPresent(long chunkPos) {
        return this.visibleChunkMap.get(chunkPos);
    }

    public @Nullable ChunkStatus getLatestStatus(long chunkPos) {
        ChunkHolder visibleChunkIfPresent = this.getVisibleChunkIfPresent(chunkPos);
        return visibleChunkIfPresent != null ? visibleChunkIfPresent.getLatestStatus() : null;
    }

    protected IntSupplier getChunkQueueLevel(long chunkPos) {
        return () -> {
            ChunkHolder visibleChunkIfPresent = this.getVisibleChunkIfPresent(chunkPos);
            return visibleChunkIfPresent == null
                ? ChunkTaskPriorityQueue.PRIORITY_LEVEL_COUNT - 1
                : Math.min(visibleChunkIfPresent.getQueueLevel(), ChunkTaskPriorityQueue.PRIORITY_LEVEL_COUNT - 1);
        };
    }

    public String getChunkDebugData(ChunkPos pos) {
        ChunkHolder visibleChunkIfPresent = this.getVisibleChunkIfPresent(pos.toLong());
        if (visibleChunkIfPresent == null) {
            return "null";
        } else {
            String string = visibleChunkIfPresent.getTicketLevel() + "\n";
            ChunkStatus latestStatus = visibleChunkIfPresent.getLatestStatus();
            ChunkAccess latestChunk = visibleChunkIfPresent.getLatestChunk();
            if (latestStatus != null) {
                string = string + "St: §" + latestStatus.getIndex() + latestStatus + "§r\n";
            }

            if (latestChunk != null) {
                string = string + "Ch: §" + latestChunk.getPersistedStatus().getIndex() + latestChunk.getPersistedStatus() + "§r\n";
            }

            FullChunkStatus fullStatus = visibleChunkIfPresent.getFullStatus();
            string = string + '§' + fullStatus.ordinal() + fullStatus;
            return string + "§r";
        }
    }

    CompletableFuture<ChunkResult<List<ChunkAccess>>> getChunkRangeFuture(ChunkHolder chunkHolder, int range, IntFunction<ChunkStatus> statusGetter) {
        if (range == 0) {
            ChunkStatus chunkStatus = statusGetter.apply(0);
            return chunkHolder.scheduleChunkGenerationTask(chunkStatus, this).thenApply(chunkResult -> chunkResult.map(List::of));
        } else {
            int squared = Mth.square(range * 2 + 1);
            List<CompletableFuture<ChunkResult<ChunkAccess>>> list = new ArrayList<>(squared);
            ChunkPos pos = chunkHolder.getPos();

            for (int i = -range; i <= range; i++) {
                for (int i1 = -range; i1 <= range; i1++) {
                    int max = Math.max(Math.abs(i1), Math.abs(i));
                    long packedChunkPos = ChunkPos.asLong(pos.x + i1, pos.z + i);
                    ChunkHolder updatingChunkIfPresent = this.getUpdatingChunkIfPresent(packedChunkPos);
                    if (updatingChunkIfPresent == null) {
                        return UNLOADED_CHUNK_LIST_FUTURE;
                    }

                    ChunkStatus chunkStatus1 = statusGetter.apply(max);
                    list.add(updatingChunkIfPresent.scheduleChunkGenerationTask(chunkStatus1, this));
                }
            }

            return Util.sequence(list).thenApply(list1 -> {
                List<ChunkAccess> list2 = new ArrayList<>(list1.size());

                for (ChunkResult<ChunkAccess> chunkResult : list1) {
                    if (chunkResult == null) {
                        throw this.debugFuturesAndCreateReportedException(new IllegalStateException("At least one of the chunk futures were null"), "n/a");
                    }

                    ChunkAccess chunkAccess = chunkResult.orElse(null);
                    if (chunkAccess == null) {
                        return UNLOADED_CHUNK_LIST_RESULT;
                    }

                    list2.add(chunkAccess);
                }

                return ChunkResult.of(list2);
            });
        }
    }

    public ReportedException debugFuturesAndCreateReportedException(IllegalStateException exception, String details) {
        StringBuilder stringBuilder = new StringBuilder();
        Consumer<ChunkHolder> consumer = chunk -> chunk.getAllFutures()
            .forEach(
                pair -> {
                    ChunkStatus chunkStatus = pair.getFirst();
                    CompletableFuture<ChunkResult<ChunkAccess>> completableFuture = pair.getSecond();
                    if (completableFuture != null && completableFuture.isDone() && completableFuture.join() == null) {
                        stringBuilder.append(chunk.getPos())
                            .append(" - status: ")
                            .append(chunkStatus)
                            .append(" future: ")
                            .append(completableFuture)
                            .append(System.lineSeparator());
                    }
                }
            );
        stringBuilder.append("Updating:").append(System.lineSeparator());
        this.updatingChunkMap.values().forEach(consumer);
        stringBuilder.append("Visible:").append(System.lineSeparator());
        this.visibleChunkMap.values().forEach(consumer);
        CrashReport crashReport = CrashReport.forThrowable(exception, "Chunk loading");
        CrashReportCategory crashReportCategory = crashReport.addCategory("Chunk loading");
        crashReportCategory.setDetail("Details", details);
        crashReportCategory.setDetail("Futures", stringBuilder);
        return new ReportedException(crashReport);
    }

    public CompletableFuture<ChunkResult<LevelChunk>> prepareEntityTickingChunk(ChunkHolder chunk) {
        return this.getChunkRangeFuture(chunk, 2, i -> ChunkStatus.FULL)
            .thenApply(chunkResult -> chunkResult.map(list -> (LevelChunk)list.get(list.size() / 2)));
    }

    @Nullable ChunkHolder updateChunkScheduling(long chunkPos, int newLevel, @Nullable ChunkHolder holder, int oldLevel) {
        if (!ChunkLevel.isLoaded(oldLevel) && !ChunkLevel.isLoaded(newLevel)) {
            return holder;
        } else {
            if (holder != null) {
                holder.setTicketLevel(newLevel);
            }

            if (holder != null) {
                if (!ChunkLevel.isLoaded(newLevel)) {
                    this.toDrop.add(chunkPos);
                } else {
                    this.toDrop.remove(chunkPos);
                }
            }

            if (ChunkLevel.isLoaded(newLevel) && holder == null) {
                holder = this.pendingUnloads.remove(chunkPos);
                if (holder != null) {
                    holder.setTicketLevel(newLevel);
                } else {
                    holder = new ChunkHolder(new ChunkPos(chunkPos), newLevel, this.level, this.lightEngine, this::onLevelChange, this);
                }

                this.updatingChunkMap.put(chunkPos, holder);
                this.modified = true;
            }

            return holder;
        }
    }

    private void onLevelChange(ChunkPos chunkPos, IntSupplier queueLevelGetter, int ticketLevel, IntConsumer queueLevelSetter) {
        this.worldgenTaskDispatcher.onLevelChange(chunkPos, queueLevelGetter, ticketLevel, queueLevelSetter);
        this.lightTaskDispatcher.onLevelChange(chunkPos, queueLevelGetter, ticketLevel, queueLevelSetter);
    }

    @Override
    public void close() throws IOException {
        try {
            this.worldgenTaskDispatcher.close();
            this.lightTaskDispatcher.close();
            this.poiManager.close();
        } finally {
            super.close();
        }
    }

    protected void saveAllChunks(boolean flush) {
        if (flush) {
            List<ChunkHolder> list = this.visibleChunkMap
                .values()
                .stream()
                .filter(ChunkHolder::wasAccessibleSinceLastSave)
                .peek(ChunkHolder::refreshAccessibility)
                .toList();
            MutableBoolean mutableBoolean = new MutableBoolean();

            do {
                mutableBoolean.setFalse();
                list.stream()
                    .map(chunk -> {
                        this.mainThreadExecutor.managedBlock(chunk::isReadyForSaving);
                        return chunk.getLatestChunk();
                    })
                    .filter(chunk -> chunk instanceof ImposterProtoChunk || chunk instanceof LevelChunk)
                    .filter(this::save)
                    .forEach(chunk -> mutableBoolean.setTrue());
            } while (mutableBoolean.isTrue());

            this.poiManager.flushAll();
            this.processUnloads(() -> true);
            this.synchronize(true).join();
        } else {
            this.nextChunkSaveTime.clear();
            long millis = Util.getMillis();

            for (ChunkHolder chunkHolder : this.visibleChunkMap.values()) {
                this.saveChunkIfNeeded(chunkHolder, millis);
            }
        }
    }

    protected void tick(BooleanSupplier hasMoreTime) {
        ProfilerFiller profilerFiller = Profiler.get();
        profilerFiller.push("poi");
        this.poiManager.tick(hasMoreTime);
        profilerFiller.popPush("chunk_unload");
        if (!this.level.noSave()) {
            this.processUnloads(hasMoreTime);
        }

        profilerFiller.pop();
    }

    public boolean hasWork() {
        return this.lightEngine.hasLightWork()
            || !this.pendingUnloads.isEmpty()
            || !this.updatingChunkMap.isEmpty()
            || this.poiManager.hasWork()
            || !this.toDrop.isEmpty()
            || !this.unloadQueue.isEmpty()
            || this.worldgenTaskDispatcher.hasWork()
            || this.lightTaskDispatcher.hasWork()
            || this.distanceManager.hasTickets();
    }

    private void processUnloads(BooleanSupplier hasMoreTime) {
        for (LongIterator longIterator = this.toDrop.iterator(); longIterator.hasNext(); longIterator.remove()) {
            long l = longIterator.nextLong();
            ChunkHolder chunkHolder = this.updatingChunkMap.get(l);
            if (chunkHolder != null) {
                this.updatingChunkMap.remove(l);
                this.pendingUnloads.put(l, chunkHolder);
                this.modified = true;
                this.scheduleUnload(l, chunkHolder);
            }
        }

        int max = Math.max(0, this.unloadQueue.size() - 2000);

        Runnable runnable;
        while ((max > 0 || hasMoreTime.getAsBoolean()) && (runnable = this.unloadQueue.poll()) != null) {
            max--;
            runnable.run();
        }

        this.saveChunksEagerly(hasMoreTime);
    }

    private void saveChunksEagerly(BooleanSupplier hasMoreTime) {
        long millis = Util.getMillis();
        int i = 0;
        LongIterator longIterator = this.chunksToEagerlySave.iterator();

        while (i < 20 && this.activeChunkWrites.get() < 128 && hasMoreTime.getAsBoolean() && longIterator.hasNext()) {
            long l = longIterator.nextLong();
            ChunkHolder chunkHolder = this.visibleChunkMap.get(l);
            ChunkAccess chunkAccess = chunkHolder != null ? chunkHolder.getLatestChunk() : null;
            if (chunkAccess == null || !chunkAccess.isUnsaved()) {
                longIterator.remove();
            } else if (this.saveChunkIfNeeded(chunkHolder, millis)) {
                i++;
                longIterator.remove();
            }
        }
    }

    private void scheduleUnload(long chunkPos, ChunkHolder chunkHolder) {
        CompletableFuture<?> saveSyncFuture = chunkHolder.getSaveSyncFuture();
        saveSyncFuture.thenRunAsync(() -> {
            CompletableFuture<?> saveSyncFuture1 = chunkHolder.getSaveSyncFuture();
            if (saveSyncFuture1 != saveSyncFuture) {
                this.scheduleUnload(chunkPos, chunkHolder);
            } else {
                ChunkAccess latestChunk = chunkHolder.getLatestChunk();
                if (this.pendingUnloads.remove(chunkPos, chunkHolder) && latestChunk != null) {
                    if (latestChunk instanceof LevelChunk levelChunk) {
                        levelChunk.setLoaded(false);
                    }

                    this.save(latestChunk);
                    if (latestChunk instanceof LevelChunk levelChunk) {
                        this.level.unload(levelChunk);
                    }

                    this.lightEngine.updateChunkStatus(latestChunk.getPos());
                    this.lightEngine.tryScheduleUpdate();
                    this.nextChunkSaveTime.remove(latestChunk.getPos().toLong());
                }
            }
        }, this.unloadQueue::add).whenComplete((_void, error) -> {
            if (error != null) {
                LOGGER.error("Failed to save chunk {}", chunkHolder.getPos(), error);
            }
        });
    }

    protected boolean promoteChunkMap() {
        if (!this.modified) {
            return false;
        } else {
            this.visibleChunkMap = this.updatingChunkMap.clone();
            this.modified = false;
            return true;
        }
    }

    private CompletableFuture<ChunkAccess> scheduleChunkLoad(ChunkPos chunkPos) {
        CompletableFuture<Optional<SerializableChunkData>> completableFuture = this.readChunk(chunkPos).thenApplyAsync(optional -> optional.map(tag -> {
            SerializableChunkData serializableChunkData = SerializableChunkData.parse(this.level, this.level.palettedContainerFactory(), tag);
            if (serializableChunkData == null) {
                LOGGER.error("Chunk file at {} is missing level data, skipping", chunkPos);
            }

            return serializableChunkData;
        }), Util.backgroundExecutor().forName("parseChunk"));
        CompletableFuture<?> completableFuture1 = this.poiManager.prefetch(chunkPos);
        return completableFuture.<Object, Optional<SerializableChunkData>>thenCombine(
                (CompletionStage<? extends Object>)completableFuture1, (optional, object) -> optional
            )
            .thenApplyAsync(optional -> {
                Profiler.get().incrementCounter("chunkLoad");
                if (optional.isPresent()) {
                    ChunkAccess chunkAccess = optional.get().read(this.level, this.poiManager, this.storageInfo(), chunkPos);
                    this.markPosition(chunkPos, chunkAccess.getPersistedStatus().getChunkType());
                    return chunkAccess;
                } else {
                    return this.createEmptyChunk(chunkPos);
                }
            }, this.mainThreadExecutor)
            .exceptionallyAsync(throwable -> this.handleChunkLoadFailure(throwable, chunkPos), this.mainThreadExecutor);
    }

    private ChunkAccess handleChunkLoadFailure(Throwable exception, ChunkPos chunkPos) {
        Throwable throwable = exception instanceof CompletionException completionException ? completionException.getCause() : exception;
        Throwable throwable1 = throwable instanceof ReportedException reportedException ? reportedException.getCause() : throwable;
        boolean flag = throwable1 instanceof Error;
        boolean flag1 = throwable1 instanceof IOException || throwable1 instanceof NbtException;
        if (!flag) {
            if (!flag1) {
            }

            this.level.getServer().reportChunkLoadFailure(throwable1, this.storageInfo(), chunkPos);
            return this.createEmptyChunk(chunkPos);
        } else {
            CrashReport crashReport = CrashReport.forThrowable(exception, "Exception loading chunk");
            CrashReportCategory crashReportCategory = crashReport.addCategory("Chunk being loaded");
            crashReportCategory.setDetail("pos", chunkPos);
            this.markPositionReplaceable(chunkPos);
            throw new ReportedException(crashReport);
        }
    }

    private ChunkAccess createEmptyChunk(ChunkPos chunkPos) {
        this.markPositionReplaceable(chunkPos);
        return new ProtoChunk(chunkPos, UpgradeData.EMPTY, this.level, this.level.palettedContainerFactory(), null);
    }

    private void markPositionReplaceable(ChunkPos chunkPos) {
        this.chunkTypeCache.put(chunkPos.toLong(), (byte)-1);
    }

    private byte markPosition(ChunkPos chunkPos, ChunkType chunkType) {
        return this.chunkTypeCache.put(chunkPos.toLong(), (byte)(chunkType == ChunkType.PROTOCHUNK ? -1 : 1));
    }

    @Override
    public GenerationChunkHolder acquireGeneration(long chunkPos) {
        ChunkHolder chunkHolder = this.updatingChunkMap.get(chunkPos);
        chunkHolder.increaseGenerationRefCount();
        return chunkHolder;
    }

    @Override
    public void releaseGeneration(GenerationChunkHolder chunk) {
        chunk.decreaseGenerationRefCount();
    }

    @Override
    public CompletableFuture<ChunkAccess> applyStep(GenerationChunkHolder chunk, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache) {
        ChunkPos pos = chunk.getPos();
        if (step.targetStatus() == ChunkStatus.EMPTY) {
            return this.scheduleChunkLoad(pos);
        } else {
            try {
                GenerationChunkHolder generationChunkHolder = cache.get(pos.x, pos.z);
                ChunkAccess chunkIfPresentUnchecked = generationChunkHolder.getChunkIfPresentUnchecked(step.targetStatus().getParent());
                if (chunkIfPresentUnchecked == null) {
                    throw new IllegalStateException("Parent chunk missing");
                } else {
                    return step.apply(this.worldGenContext, cache, chunkIfPresentUnchecked);
                }
            } catch (Exception var8) {
                var8.getStackTrace();
                CrashReport crashReport = CrashReport.forThrowable(var8, "Exception generating new chunk");
                CrashReportCategory crashReportCategory = crashReport.addCategory("Chunk to be generated");
                crashReportCategory.setDetail("Status being generated", () -> step.targetStatus().getName());
                crashReportCategory.setDetail("Location", String.format(Locale.ROOT, "%d,%d", pos.x, pos.z));
                crashReportCategory.setDetail("Position hash", ChunkPos.asLong(pos.x, pos.z));
                crashReportCategory.setDetail("Generator", this.generator());
                this.mainThreadExecutor.execute(() -> {
                    throw new ReportedException(crashReport);
                });
                throw new ReportedException(crashReport);
            }
        }
    }

    @Override
    public ChunkGenerationTask scheduleGenerationTask(ChunkStatus targetStatus, ChunkPos pos) {
        ChunkGenerationTask chunkGenerationTask = ChunkGenerationTask.create(this, targetStatus, pos);
        this.pendingGenerationTasks.add(chunkGenerationTask);
        return chunkGenerationTask;
    }

    private void runGenerationTask(ChunkGenerationTask task) {
        GenerationChunkHolder center = task.getCenter();
        this.worldgenTaskDispatcher.submit(() -> {
            CompletableFuture<?> completableFuture = task.runUntilWait();
            if (completableFuture != null) {
                completableFuture.thenRun(() -> this.runGenerationTask(task));
            }
        }, center.getPos().toLong(), center::getQueueLevel);
    }

    @Override
    public void runGenerationTasks() {
        this.pendingGenerationTasks.forEach(this::runGenerationTask);
        this.pendingGenerationTasks.clear();
    }

    public CompletableFuture<ChunkResult<LevelChunk>> prepareTickingChunk(ChunkHolder holder) {
        CompletableFuture<ChunkResult<List<ChunkAccess>>> chunkRangeFuture = this.getChunkRangeFuture(holder, 1, i -> ChunkStatus.FULL);
        return chunkRangeFuture.thenApplyAsync(chunk -> chunk.map(list -> {
            LevelChunk levelChunk = (LevelChunk)list.get(list.size() / 2);
            levelChunk.postProcessGeneration(this.level);
            this.level.startTickingChunk(levelChunk);
            CompletableFuture<?> sendSyncFuture = holder.getSendSyncFuture();
            if (sendSyncFuture.isDone()) {
                this.onChunkReadyToSend(holder, levelChunk);
            } else {
                sendSyncFuture.thenAcceptAsync(object -> this.onChunkReadyToSend(holder, levelChunk), this.mainThreadExecutor);
            }

            return levelChunk;
        }), this.mainThreadExecutor);
    }

    private void onChunkReadyToSend(ChunkHolder chunkHolder, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();

        for (ServerPlayer serverPlayer : this.playerMap.getAllPlayers()) {
            if (serverPlayer.getChunkTrackingView().contains(pos)) {
                markChunkPendingToSend(serverPlayer, chunk);
            }
        }

        this.level.getChunkSource().onChunkReadyToSend(chunkHolder);
        this.level.debugSynchronizers().registerChunk(chunk);
    }

    public CompletableFuture<ChunkResult<LevelChunk>> prepareAccessibleChunk(ChunkHolder chunk) {
        return this.getChunkRangeFuture(chunk, 1, ChunkLevel::getStatusAroundFullChunk)
            .thenApply(chunkResult -> chunkResult.map(list -> (LevelChunk)list.get(list.size() / 2)));
    }

    Stream<ChunkHolder> allChunksWithAtLeastStatus(ChunkStatus status) {
        int i = ChunkLevel.byStatus(status);
        return this.visibleChunkMap.values().stream().filter(chunkHolder -> chunkHolder.getTicketLevel() <= i);
    }

    private boolean saveChunkIfNeeded(ChunkHolder chunk, long gameTime) {
        if (chunk.wasAccessibleSinceLastSave() && chunk.isReadyForSaving()) {
            ChunkAccess latestChunk = chunk.getLatestChunk();
            if (!(latestChunk instanceof ImposterProtoChunk) && !(latestChunk instanceof LevelChunk)) {
                return false;
            } else if (!latestChunk.isUnsaved()) {
                return false;
            } else {
                long packedChunkPos = latestChunk.getPos().toLong();
                long orDefault = this.nextChunkSaveTime.getOrDefault(packedChunkPos, -1L);
                if (gameTime < orDefault) {
                    return false;
                } else {
                    boolean flag = this.save(latestChunk);
                    chunk.refreshAccessibility();
                    if (flag) {
                        this.nextChunkSaveTime.put(packedChunkPos, gameTime + 10000L);
                    }

                    return flag;
                }
            }
        } else {
            return false;
        }
    }

    public boolean save(ChunkAccess chunk) {
        this.poiManager.flush(chunk.getPos());
        if (!chunk.tryMarkSaved()) {
            return false;
        } else {
            ChunkPos pos = chunk.getPos();

            try {
                ChunkStatus persistedStatus = chunk.getPersistedStatus();
                if (persistedStatus.getChunkType() != ChunkType.LEVELCHUNK) {
                    if (this.isExistingChunkFull(pos)) {
                        return false;
                    }

                    if (persistedStatus == ChunkStatus.EMPTY && chunk.getAllStarts().values().stream().noneMatch(StructureStart::isValid)) {
                        return false;
                    }
                }

                Profiler.get().incrementCounter("chunkSave");
                this.activeChunkWrites.incrementAndGet();
                SerializableChunkData serializableChunkData = SerializableChunkData.copyOf(this.level, chunk);
                CompletableFuture<CompoundTag> completableFuture = CompletableFuture.supplyAsync(serializableChunkData::write, Util.backgroundExecutor());
                this.write(pos, completableFuture::join).handle((_void, exception1) -> {
                    if (exception1 != null) {
                        this.level.getServer().reportChunkSaveFailure(exception1, this.storageInfo(), pos);
                    }

                    this.activeChunkWrites.decrementAndGet();
                    return null;
                });
                this.markPosition(pos, persistedStatus.getChunkType());
                return true;
            } catch (Exception var6) {
                this.level.getServer().reportChunkSaveFailure(var6, this.storageInfo(), pos);
                return false;
            }
        }
    }

    private boolean isExistingChunkFull(ChunkPos chunkPos) {
        byte b = this.chunkTypeCache.get(chunkPos.toLong());
        if (b != 0) {
            return b == 1;
        } else {
            CompoundTag compoundTag;
            try {
                compoundTag = this.readChunk(chunkPos).join().orElse(null);
                if (compoundTag == null) {
                    this.markPositionReplaceable(chunkPos);
                    return false;
                }
            } catch (Exception var5) {
                LOGGER.error("Failed to read chunk {}", chunkPos, var5);
                this.markPositionReplaceable(chunkPos);
                return false;
            }

            ChunkType chunkType = SerializableChunkData.getChunkStatusFromTag(compoundTag).getChunkType();
            return this.markPosition(chunkPos, chunkType) == 1;
        }
    }

    public void setServerViewDistance(int viewDistance) {
        int i = Mth.clamp(viewDistance, 2, 32);
        if (i != this.serverViewDistance) {
            this.serverViewDistance = i;
            this.distanceManager.updatePlayerTickets(this.serverViewDistance);

            for (ServerPlayer serverPlayer : this.playerMap.getAllPlayers()) {
                this.updateChunkTracking(serverPlayer);
            }
        }
    }

    int getPlayerViewDistance(ServerPlayer player) {
        return Mth.clamp(player.requestedViewDistance(), 2, this.serverViewDistance);
    }

    private void markChunkPendingToSend(ServerPlayer player, ChunkPos chunkPos) {
        LevelChunk chunkToSend = this.getChunkToSend(chunkPos.toLong());
        if (chunkToSend != null) {
            markChunkPendingToSend(player, chunkToSend);
        }
    }

    private static void markChunkPendingToSend(ServerPlayer player, LevelChunk chunk) {
        player.connection.chunkSender.markChunkPendingToSend(chunk);
    }

    private static void dropChunk(ServerPlayer player, ChunkPos chunkPos) {
        player.connection.chunkSender.dropChunk(player, chunkPos);
    }

    public @Nullable LevelChunk getChunkToSend(long chunkPos) {
        ChunkHolder visibleChunkIfPresent = this.getVisibleChunkIfPresent(chunkPos);
        return visibleChunkIfPresent == null ? null : visibleChunkIfPresent.getChunkToSend();
    }

    public int size() {
        return this.visibleChunkMap.size();
    }

    public net.minecraft.server.level.DistanceManager getDistanceManager() {
        return this.distanceManager;
    }

    void dumpChunks(Writer writer) throws IOException {
        CsvOutput csvOutput = CsvOutput.builder()
            .addColumn("x")
            .addColumn("z")
            .addColumn("level")
            .addColumn("in_memory")
            .addColumn("status")
            .addColumn("full_status")
            .addColumn("accessible_ready")
            .addColumn("ticking_ready")
            .addColumn("entity_ticking_ready")
            .addColumn("ticket")
            .addColumn("spawning")
            .addColumn("block_entity_count")
            .addColumn("ticking_ticket")
            .addColumn("ticking_level")
            .addColumn("block_ticks")
            .addColumn("fluid_ticks")
            .build(writer);

        for (Entry<ChunkHolder> entry : this.visibleChunkMap.long2ObjectEntrySet()) {
            long longKey = entry.getLongKey();
            ChunkPos chunkPos = new ChunkPos(longKey);
            ChunkHolder chunkHolder = entry.getValue();
            Optional<ChunkAccess> optional = Optional.ofNullable(chunkHolder.getLatestChunk());
            Optional<LevelChunk> optional1 = optional.flatMap(chunk -> chunk instanceof LevelChunk ? Optional.of((LevelChunk)chunk) : Optional.empty());
            csvOutput.writeRow(
                chunkPos.x,
                chunkPos.z,
                chunkHolder.getTicketLevel(),
                optional.isPresent(),
                optional.map(ChunkAccess::getPersistedStatus).orElse(null),
                optional1.map(LevelChunk::getFullStatus).orElse(null),
                printFuture(chunkHolder.getFullChunkFuture()),
                printFuture(chunkHolder.getTickingChunkFuture()),
                printFuture(chunkHolder.getEntityTickingChunkFuture()),
                this.ticketStorage.getTicketDebugString(longKey, false),
                this.anyPlayerCloseEnoughForSpawning(chunkPos),
                optional1.<Integer>map(chunk -> chunk.getBlockEntities().size()).orElse(0),
                this.ticketStorage.getTicketDebugString(longKey, true),
                this.distanceManager.getChunkLevel(longKey, true),
                optional1.<Integer>map(chunk -> chunk.getBlockTicks().count()).orElse(0),
                optional1.<Integer>map(chunk -> chunk.getFluidTicks().count()).orElse(0)
            );
        }
    }

    private static String printFuture(CompletableFuture<ChunkResult<LevelChunk>> future) {
        try {
            ChunkResult<LevelChunk> chunkResult = future.getNow(null);
            if (chunkResult != null) {
                return chunkResult.isSuccess() ? "done" : "unloaded";
            } else {
                return "not completed";
            }
        } catch (CompletionException var2) {
            return "failed " + var2.getCause().getMessage();
        } catch (CancellationException var3) {
            return "cancelled";
        }
    }

    private CompletableFuture<Optional<CompoundTag>> readChunk(ChunkPos pos) {
        return this.read(pos).thenApplyAsync(optional -> optional.map(this::upgradeChunkTag), Util.backgroundExecutor().forName("upgradeChunk"));
    }

    private CompoundTag upgradeChunkTag(CompoundTag tag) {
        return this.upgradeChunkTag(tag, -1, getChunkDataFixContextTag(this.level.dimension(), this.generator().getTypeNameForDataFixer()));
    }

    public static CompoundTag getChunkDataFixContextTag(ResourceKey<Level> dimension, Optional<ResourceKey<MapCodec<? extends ChunkGenerator>>> generator) {
        CompoundTag compoundTag = new CompoundTag();
        compoundTag.putString("dimension", dimension.identifier().toString());
        generator.ifPresent(resourceKey -> compoundTag.putString("generator", resourceKey.identifier().toString()));
        return compoundTag;
    }

    void collectSpawningChunks(List<LevelChunk> output) {
        LongIterator spawnCandidateChunks = this.distanceManager.getSpawnCandidateChunks();

        while (spawnCandidateChunks.hasNext()) {
            ChunkHolder chunkHolder = this.visibleChunkMap.get(spawnCandidateChunks.nextLong());
            if (chunkHolder != null) {
                LevelChunk tickingChunk = chunkHolder.getTickingChunk();
                if (tickingChunk != null && this.anyPlayerCloseEnoughForSpawningInternal(chunkHolder.getPos())) {
                    output.add(tickingChunk);
                }
            }
        }
    }

    void forEachBlockTickingChunk(Consumer<LevelChunk> action) {
        this.distanceManager.forEachEntityTickingChunk(chunkPos -> {
            ChunkHolder chunkHolder = this.visibleChunkMap.get(chunkPos);
            if (chunkHolder != null) {
                LevelChunk tickingChunk = chunkHolder.getTickingChunk();
                if (tickingChunk != null) {
                    action.accept(tickingChunk);
                }
            }
        });
    }

    public boolean anyPlayerCloseEnoughForSpawning(ChunkPos chunkPos) {
        TriState triState = this.distanceManager.hasPlayersNearby(chunkPos.toLong());
        return triState == TriState.DEFAULT ? this.anyPlayerCloseEnoughForSpawningInternal(chunkPos) : triState.toBoolean(true);
    }

    boolean anyPlayerCloseEnoughTo(BlockPos pos, int radius) {
        Vec3 vec3 = new Vec3(pos);

        for (ServerPlayer serverPlayer : this.playerMap.getAllPlayers()) {
            if (this.playerIsCloseEnoughTo(serverPlayer, vec3, radius)) {
                return true;
            }
        }

        return false;
    }

    private boolean anyPlayerCloseEnoughForSpawningInternal(ChunkPos chunkPos) {
        for (ServerPlayer serverPlayer : this.playerMap.getAllPlayers()) {
            if (this.playerIsCloseEnoughForSpawning(serverPlayer, chunkPos)) {
                return true;
            }
        }

        return false;
    }

    public List<ServerPlayer> getPlayersCloseForSpawning(ChunkPos chunkPos) {
        long packedChunkPos = chunkPos.toLong();
        if (!this.distanceManager.hasPlayersNearby(packedChunkPos).toBoolean(true)) {
            return List.of();
        } else {
            Builder<ServerPlayer> builder = ImmutableList.builder();

            for (ServerPlayer serverPlayer : this.playerMap.getAllPlayers()) {
                if (this.playerIsCloseEnoughForSpawning(serverPlayer, chunkPos)) {
                    builder.add(serverPlayer);
                }
            }

            return builder.build();
        }
    }

    private boolean playerIsCloseEnoughForSpawning(ServerPlayer player, ChunkPos chunkPos) {
        if (player.isSpectator()) {
            return false;
        } else {
            double d = euclideanDistanceSquared(chunkPos, player.position());
            return d < 16384.0;
        }
    }

    private boolean playerIsCloseEnoughTo(ServerPlayer player, Vec3 pos, int radius) {
        if (player.isSpectator()) {
            return false;
        } else {
            double d = player.position().distanceTo(pos);
            return d < radius;
        }
    }

    private static double euclideanDistanceSquared(ChunkPos chunkPos, Vec3 pos) {
        double d = SectionPos.sectionToBlockCoord(chunkPos.x, 8);
        double d1 = SectionPos.sectionToBlockCoord(chunkPos.z, 8);
        double d2 = d - pos.x;
        double d3 = d1 - pos.z;
        return d2 * d2 + d3 * d3;
    }

    private boolean skipPlayer(ServerPlayer player) {
        return player.isSpectator() && !this.level.getGameRules().get(GameRules.SPECTATORS_GENERATE_CHUNKS);
    }

    void updatePlayerStatus(ServerPlayer player, boolean track) {
        boolean flag = this.skipPlayer(player);
        boolean flag1 = this.playerMap.ignoredOrUnknown(player);
        if (track) {
            this.playerMap.addPlayer(player, flag);
            this.updatePlayerPos(player);
            if (!flag) {
                this.distanceManager.addPlayer(SectionPos.of(player), player);
            }

            player.setChunkTrackingView(ChunkTrackingView.EMPTY);
            this.updateChunkTracking(player);
        } else {
            SectionPos lastSectionPos = player.getLastSectionPos();
            this.playerMap.removePlayer(player);
            if (!flag1) {
                this.distanceManager.removePlayer(lastSectionPos, player);
            }

            this.applyChunkTrackingView(player, ChunkTrackingView.EMPTY);
        }
    }

    private void updatePlayerPos(ServerPlayer player) {
        SectionPos sectionPos = SectionPos.of(player);
        player.setLastSectionPos(sectionPos);
    }

    public void move(ServerPlayer player) {
        for (ChunkMap.TrackedEntity trackedEntity : this.entityMap.values()) {
            if (trackedEntity.entity == player) {
                trackedEntity.updatePlayers(this.level.players());
            } else {
                trackedEntity.updatePlayer(player);
            }
        }

        SectionPos lastSectionPos = player.getLastSectionPos();
        SectionPos sectionPos = SectionPos.of(player);
        boolean flag = this.playerMap.ignored(player);
        boolean flag1 = this.skipPlayer(player);
        boolean flag2 = lastSectionPos.asLong() != sectionPos.asLong();
        if (flag2 || flag != flag1) {
            this.updatePlayerPos(player);
            if (!flag) {
                this.distanceManager.removePlayer(lastSectionPos, player);
            }

            if (!flag1) {
                this.distanceManager.addPlayer(sectionPos, player);
            }

            if (!flag && flag1) {
                this.playerMap.ignorePlayer(player);
            }

            if (flag && !flag1) {
                this.playerMap.unIgnorePlayer(player);
            }

            this.updateChunkTracking(player);
        }
    }

    private void updateChunkTracking(ServerPlayer player) {
        ChunkPos chunkPos = player.chunkPosition();
        int playerViewDistance = this.getPlayerViewDistance(player);
        if (!(
            player.getChunkTrackingView() instanceof ChunkTrackingView.Positioned positioned
                && positioned.center().equals(chunkPos)
                && positioned.viewDistance() == playerViewDistance
        )) {
            this.applyChunkTrackingView(player, ChunkTrackingView.of(chunkPos, playerViewDistance));
        }
    }

    private void applyChunkTrackingView(ServerPlayer player, ChunkTrackingView chunkTrackingView) {
        if (player.level() == this.level) {
            ChunkTrackingView chunkTrackingView1 = player.getChunkTrackingView();
            if (chunkTrackingView instanceof ChunkTrackingView.Positioned positioned
                && !(chunkTrackingView1 instanceof ChunkTrackingView.Positioned positioned1 && positioned1.center().equals(positioned.center()))) {
                player.connection.send(new ClientboundSetChunkCacheCenterPacket(positioned.center().x, positioned.center().z));
            }

            ChunkTrackingView.difference(
                chunkTrackingView1, chunkTrackingView, chunkPos -> this.markChunkPendingToSend(player, chunkPos), chunkPos -> dropChunk(player, chunkPos)
            );
            player.setChunkTrackingView(chunkTrackingView);
        }
    }

    @Override
    public List<ServerPlayer> getPlayers(ChunkPos pos, boolean boundaryOnly) {
        Set<ServerPlayer> allPlayers = this.playerMap.getAllPlayers();
        Builder<ServerPlayer> builder = ImmutableList.builder();

        for (ServerPlayer serverPlayer : allPlayers) {
            if (boundaryOnly && this.isChunkOnTrackedBorder(serverPlayer, pos.x, pos.z) || !boundaryOnly && this.isChunkTracked(serverPlayer, pos.x, pos.z)) {
                builder.add(serverPlayer);
            }
        }

        return builder.build();
    }

    public void addEntity(Entity entity) {
        if (!(entity instanceof EnderDragonPart)) {
            EntityType<?> type = entity.getType();
            int i = type.clientTrackingRange() * 16;
            if (i != 0) {
                int updateInterval = type.updateInterval();
                if (this.entityMap.containsKey(entity.getId())) {
                    throw (IllegalStateException)Util.pauseInIde(new IllegalStateException("Entity is already tracked!"));
                } else {
                    ChunkMap.TrackedEntity trackedEntity = new ChunkMap.TrackedEntity(entity, i, updateInterval, type.trackDeltas());
                    this.entityMap.put(entity.getId(), trackedEntity);
                    trackedEntity.updatePlayers(this.level.players());
                    if (entity instanceof ServerPlayer serverPlayer) {
                        this.updatePlayerStatus(serverPlayer, true);

                        for (ChunkMap.TrackedEntity trackedEntity1 : this.entityMap.values()) {
                            if (trackedEntity1.entity != serverPlayer) {
                                trackedEntity1.updatePlayer(serverPlayer);
                            }
                        }
                    }
                }
            }
        }
    }

    protected void removeEntity(Entity entity) {
        if (entity instanceof ServerPlayer serverPlayer) {
            this.updatePlayerStatus(serverPlayer, false);

            for (ChunkMap.TrackedEntity trackedEntity : this.entityMap.values()) {
                trackedEntity.removePlayer(serverPlayer);
            }
        }

        ChunkMap.TrackedEntity trackedEntity1 = this.entityMap.remove(entity.getId());
        if (trackedEntity1 != null) {
            trackedEntity1.broadcastRemoved();
        }
    }

    protected void tick() {
        for (ServerPlayer serverPlayer : this.playerMap.getAllPlayers()) {
            this.updateChunkTracking(serverPlayer);
        }

        List<ServerPlayer> list = Lists.newArrayList();
        List<ServerPlayer> list1 = this.level.players();

        for (ChunkMap.TrackedEntity trackedEntity : this.entityMap.values()) {
            SectionPos sectionPos = trackedEntity.lastSectionPos;
            SectionPos sectionPos1 = SectionPos.of(trackedEntity.entity);
            boolean flag = !Objects.equals(sectionPos, sectionPos1);
            if (flag) {
                trackedEntity.updatePlayers(list1);
                Entity entity = trackedEntity.entity;
                if (entity instanceof ServerPlayer) {
                    list.add((ServerPlayer)entity);
                }

                trackedEntity.lastSectionPos = sectionPos1;
            }

            if (flag || trackedEntity.entity.needsSync || this.distanceManager.inEntityTickingRange(sectionPos1.chunk().toLong())) {
                trackedEntity.serverEntity.sendChanges();
            }
        }

        if (!list.isEmpty()) {
            for (ChunkMap.TrackedEntity trackedEntity : this.entityMap.values()) {
                trackedEntity.updatePlayers(list);
            }
        }
    }

    public void sendToTrackingPlayers(Entity entity, Packet<? super ClientGamePacketListener> packet) {
        ChunkMap.TrackedEntity trackedEntity = this.entityMap.get(entity.getId());
        if (trackedEntity != null) {
            trackedEntity.sendToTrackingPlayers(packet);
        }
    }

    public void sendToTrackingPlayersFiltered(Entity entity, Packet<? super ClientGamePacketListener> packet, Predicate<ServerPlayer> filter) {
        ChunkMap.TrackedEntity trackedEntity = this.entityMap.get(entity.getId());
        if (trackedEntity != null) {
            trackedEntity.sendToTrackingPlayersFiltered(packet, filter);
        }
    }

    protected void sendToTrackingPlayersAndSelf(Entity entity, Packet<? super ClientGamePacketListener> packet) {
        ChunkMap.TrackedEntity trackedEntity = this.entityMap.get(entity.getId());
        if (trackedEntity != null) {
            trackedEntity.sendToTrackingPlayersAndSelf(packet);
        }
    }

    public boolean isTrackedByAnyPlayer(Entity entity) {
        ChunkMap.TrackedEntity trackedEntity = this.entityMap.get(entity.getId());
        return trackedEntity != null && !trackedEntity.seenBy.isEmpty();
    }

    public void forEachEntityTrackedBy(ServerPlayer player, Consumer<Entity> action) {
        for (ChunkMap.TrackedEntity trackedEntity : this.entityMap.values()) {
            if (trackedEntity.seenBy.contains(player.connection)) {
                action.accept(trackedEntity.entity);
            }
        }
    }

    public void resendBiomesForChunks(List<ChunkAccess> chunks) {
        Map<ServerPlayer, List<LevelChunk>> map = new HashMap<>();

        for (ChunkAccess chunkAccess : chunks) {
            ChunkPos pos = chunkAccess.getPos();
            LevelChunk levelChunk1;
            if (chunkAccess instanceof LevelChunk levelChunk) {
                levelChunk1 = levelChunk;
            } else {
                levelChunk1 = this.level.getChunk(pos.x, pos.z);
            }

            for (ServerPlayer serverPlayer : this.getPlayers(pos, false)) {
                map.computeIfAbsent(serverPlayer, serverPlayer1 -> new ArrayList<>()).add(levelChunk1);
            }
        }

        map.forEach((serverPlayer1, list) -> serverPlayer1.connection.send(ClientboundChunksBiomesPacket.forChunks((List<LevelChunk>)list)));
    }

    protected PoiManager getPoiManager() {
        return this.poiManager;
    }

    public String getStorageName() {
        return this.storageName;
    }

    void onFullChunkStatusChange(ChunkPos chunkPos, FullChunkStatus fullChunkStatus) {
        this.chunkStatusListener.onChunkStatusChange(chunkPos, fullChunkStatus);
    }

    public void waitForLightBeforeSending(ChunkPos chunkPos, int range) {
        int i = range + 1;
        ChunkPos.rangeClosed(chunkPos, i).forEach(chunkPos1 -> {
            ChunkHolder visibleChunkIfPresent = this.getVisibleChunkIfPresent(chunkPos1.toLong());
            if (visibleChunkIfPresent != null) {
                visibleChunkIfPresent.addSendDependency(this.lightEngine.waitForPendingTasks(chunkPos1.x, chunkPos1.z));
            }
        });
    }

    public void forEachReadyToSendChunk(Consumer<LevelChunk> action) {
        for (ChunkHolder chunkHolder : this.visibleChunkMap.values()) {
            LevelChunk chunkToSend = chunkHolder.getChunkToSend();
            if (chunkToSend != null) {
                action.accept(chunkToSend);
            }
        }
    }

    public class DistanceManager extends net.minecraft.server.level.DistanceManager {
        protected DistanceManager(final TicketStorage ticketStorage, final Executor dispatcher, final Executor mainThreadExecutor) {
            super(ticketStorage, dispatcher, mainThreadExecutor);
        }

        @Override
        protected boolean isChunkToRemove(long chunkPos) {
            return ChunkMap.this.toDrop.contains(chunkPos);
        }

        @Override
        protected @Nullable ChunkHolder getChunk(long chunkPos) {
            return ChunkMap.this.getUpdatingChunkIfPresent(chunkPos);
        }

        @Override
        protected @Nullable ChunkHolder updateChunkScheduling(long chunkPos, int newLevel, @Nullable ChunkHolder holder, int oldLevel) {
            return ChunkMap.this.updateChunkScheduling(chunkPos, newLevel, holder, oldLevel);
        }
    }

    public class TrackedEntity implements ServerEntity.Synchronizer {
        public final ServerEntity serverEntity;
        final Entity entity;
        private final int range;
        SectionPos lastSectionPos;
        public final Set<ServerPlayerConnection> seenBy = Sets.newIdentityHashSet();

        public TrackedEntity(final Entity entity, final int range, final int updateInterval, final boolean trackDelta) {
            this.serverEntity = new ServerEntity(ChunkMap.this.level, entity, updateInterval, trackDelta, this);
            this.entity = entity;
            this.range = range;
            this.lastSectionPos = SectionPos.of(entity);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ChunkMap.TrackedEntity && ((ChunkMap.TrackedEntity)other).entity.getId() == this.entity.getId();
        }

        @Override
        public int hashCode() {
            return this.entity.getId();
        }

        @Override
        public void sendToTrackingPlayers(Packet<? super ClientGamePacketListener> packet) {
            for (ServerPlayerConnection serverPlayerConnection : this.seenBy) {
                serverPlayerConnection.send(packet);
            }
        }

        @Override
        public void sendToTrackingPlayersAndSelf(Packet<? super ClientGamePacketListener> packet) {
            this.sendToTrackingPlayers(packet);
            if (this.entity instanceof ServerPlayer serverPlayer) {
                serverPlayer.connection.send(packet);
            }
        }

        @Override
        public void sendToTrackingPlayersFiltered(Packet<? super ClientGamePacketListener> packet, Predicate<ServerPlayer> filter) {
            for (ServerPlayerConnection serverPlayerConnection : this.seenBy) {
                if (filter.test(serverPlayerConnection.getPlayer())) {
                    serverPlayerConnection.send(packet);
                }
            }
        }

        public void broadcastRemoved() {
            for (ServerPlayerConnection serverPlayerConnection : this.seenBy) {
                this.serverEntity.removePairing(serverPlayerConnection.getPlayer());
            }
        }

        public void removePlayer(ServerPlayer player) {
            if (this.seenBy.remove(player.connection)) {
                this.serverEntity.removePairing(player);
                if (this.seenBy.isEmpty()) {
                    ChunkMap.this.level.debugSynchronizers().dropEntity(this.entity);
                }
            }
        }

        public void updatePlayer(ServerPlayer player) {
            if (player != this.entity) {
                Vec3 vec3 = player.position().subtract(this.entity.position());
                int playerViewDistance = ChunkMap.this.getPlayerViewDistance(player);
                double d = Math.min(this.getEffectiveRange(), playerViewDistance * 16);
                double d1 = vec3.x * vec3.x + vec3.z * vec3.z;
                double d2 = d * d;
                boolean flag = d1 <= d2
                    && this.entity.broadcastToPlayer(player)
                    && ChunkMap.this.isChunkTracked(player, this.entity.chunkPosition().x, this.entity.chunkPosition().z);
                if (flag) {
                    if (this.seenBy.add(player.connection)) {
                        this.serverEntity.addPairing(player);
                        if (this.seenBy.size() == 1) {
                            ChunkMap.this.level.debugSynchronizers().registerEntity(this.entity);
                        }

                        ChunkMap.this.level.debugSynchronizers().startTrackingEntity(player, this.entity);
                    }
                } else {
                    this.removePlayer(player);
                }
            }
        }

        private int scaledRange(int trackingDistance) {
            return ChunkMap.this.level.getServer().getScaledTrackingDistance(trackingDistance);
        }

        private int getEffectiveRange() {
            int i = this.range;

            for (Entity entity : this.entity.getIndirectPassengers()) {
                int i1 = entity.getType().clientTrackingRange() * 16;
                if (i1 > i) {
                    i = i1;
                }
            }

            return this.scaledRange(i);
        }

        public void updatePlayers(List<ServerPlayer> playersList) {
            for (ServerPlayer serverPlayer : playersList) {
                this.updatePlayer(serverPlayer);
            }
        }
    }
}
