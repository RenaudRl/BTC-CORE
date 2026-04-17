package net.minecraft.server.level;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntMap.Entry;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.players.SleepStatus;
import net.minecraft.server.waypoints.ServerWaypointManager;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.CsvOutput;
import net.minecraft.util.Mth;
import net.minecraft.util.ProgressListener;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.debug.DebugSubscriptions;
import net.minecraft.util.debug.LevelDebugSynchronizers;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.random.WeightedList;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ReputationEventHandler;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.village.ReputationEventType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.animal.equine.SkeletonHorse;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FuelValues;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.gameevent.DynamicGameEventListener;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventDispatcher;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import net.minecraft.world.level.portal.PortalForcer;
import net.minecraft.world.level.redstone.ExperimentalRedstoneUtils;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapIndex;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.waypoints.WaypointTransmitter;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class ServerLevel extends Level implements ServerEntityGetter, WorldGenLevel {
    public static final BlockPos END_SPAWN_POINT = new BlockPos(100, 50, 0);
    public static final IntProvider RAIN_DELAY = UniformInt.of(12000, 180000);
    public static final IntProvider RAIN_DURATION = UniformInt.of(12000, 24000);
    private static final IntProvider THUNDER_DELAY = UniformInt.of(12000, 180000);
    public static final IntProvider THUNDER_DURATION = UniformInt.of(3600, 15600);
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int EMPTY_TIME_NO_TICK = 300;
    private static final int MAX_SCHEDULED_TICKS_PER_TICK = 65536;
    final List<ServerPlayer> players = Lists.newArrayList();
    public final ServerChunkCache chunkSource;
    private final MinecraftServer server;
    public final ServerLevelData serverLevelData;
    final EntityTickList entityTickList = new EntityTickList();
    private final ServerWaypointManager waypointManager;
    private final EnvironmentAttributeSystem environmentAttributes;
    public final PersistentEntitySectionManager<Entity> entityManager;
    private final GameEventDispatcher gameEventDispatcher;
    public boolean noSave;
    private final SleepStatus sleepStatus;
    private int emptyTime;
    private final PortalForcer portalForcer;
    private final LevelTicks<Block> blockTicks = new LevelTicks<>(this::isPositionTickingWithEntitiesLoaded);
    private final LevelTicks<Fluid> fluidTicks = new LevelTicks<>(this::isPositionTickingWithEntitiesLoaded);
    private final PathTypeCache pathTypesByPosCache = new PathTypeCache();
    final Set<Mob> navigatingMobs = new ObjectOpenHashSet<>();
    volatile boolean isUpdatingNavigations;
    protected final Raids raids;
    private final ObjectLinkedOpenHashSet<BlockEventData> blockEvents = new ObjectLinkedOpenHashSet<>();
    private final List<BlockEventData> blockEventsToReschedule = new ArrayList<>(64);
    private boolean handlingTick;
    private final List<CustomSpawner> customSpawners;
    private @Nullable EndDragonFight dragonFight;
    final Int2ObjectMap<EnderDragonPart> dragonParts = new Int2ObjectOpenHashMap<>();
    private final StructureManager structureManager;
    private final StructureCheck structureCheck;
    private final boolean tickTime;
    private final RandomSequences randomSequences;
    final LevelDebugSynchronizers debugSynchronizers = new LevelDebugSynchronizers(this);

    public ServerLevel(
        MinecraftServer server,
        Executor dispatcher,
        LevelStorageSource.LevelStorageAccess levelStorageAccess,
        ServerLevelData serverLevelData,
        ResourceKey<Level> dimension,
        LevelStem levelStem,
        boolean isDebug,
        long biomeZoomSeed,
        List<CustomSpawner> customSpawners,
        boolean tickTime,
        @Nullable RandomSequences randomSequences
    ) {
        super(serverLevelData, dimension, server.registryAccess(), levelStem.type(), false, isDebug, biomeZoomSeed, server.getMaxChainedNeighborUpdates());
        this.tickTime = tickTime;
        this.server = server;
        this.customSpawners = customSpawners;
        this.serverLevelData = serverLevelData;
        ChunkGenerator chunkGenerator = levelStem.generator();
        boolean flag = server.forceSynchronousWrites();
        DataFixer fixerUpper = server.getFixerUpper();
        EntityPersistentStorage<Entity> entityPersistentStorage = new EntityStorage(
            new SimpleRegionStorage(
                new RegionStorageInfo(levelStorageAccess.getLevelId(), dimension, "entities"),
                levelStorageAccess.getDimensionPath(dimension).resolve("entities"),
                fixerUpper,
                flag,
                DataFixTypes.ENTITY_CHUNK
            ),
            this,
            server
        );
        this.entityManager = new PersistentEntitySectionManager<>(Entity.class, new ServerLevel.EntityCallbacks(), entityPersistentStorage);
        this.chunkSource = new ServerChunkCache(
            this,
            levelStorageAccess,
            fixerUpper,
            server.getStructureManager(),
            dispatcher,
            chunkGenerator,
            server.getPlayerList().getViewDistance(),
            server.getPlayerList().getSimulationDistance(),
            flag,
            this.entityManager::updateChunkStatus,
            () -> server.overworld().getDataStorage()
        );
        this.chunkSource.getGeneratorState().ensureStructuresGenerated();
        this.portalForcer = new PortalForcer(this);
        if (this.canHaveWeather()) {
            this.prepareWeather();
        }

        this.raids = this.getDataStorage().computeIfAbsent(Raids.getType(this.dimensionTypeRegistration()));
        if (!server.isSingleplayer()) {
            serverLevelData.setGameType(server.getDefaultGameType());
        }

        long seed = server.getWorldData().worldGenOptions().seed();
        this.structureCheck = new StructureCheck(
            this.chunkSource.chunkScanner(),
            this.registryAccess(),
            server.getStructureManager(),
            dimension,
            chunkGenerator,
            this.chunkSource.randomState(),
            this,
            chunkGenerator.getBiomeSource(),
            seed,
            fixerUpper
        );
        this.structureManager = new StructureManager(this, server.getWorldData().worldGenOptions(), this.structureCheck);
        if (this.dimension() == Level.END && this.dimensionTypeRegistration().is(BuiltinDimensionTypes.END)) {
            this.dragonFight = new EndDragonFight(this, seed, server.getWorldData().endDragonFightData());
        } else {
            this.dragonFight = null;
        }

        this.sleepStatus = new SleepStatus();
        this.gameEventDispatcher = new GameEventDispatcher(this);
        this.randomSequences = Objects.requireNonNullElseGet(randomSequences, () -> this.getDataStorage().computeIfAbsent(RandomSequences.TYPE));
        this.waypointManager = new ServerWaypointManager();
        this.environmentAttributes = EnvironmentAttributeSystem.builder().addDefaultLayers(this).build();
        this.updateSkyBrightness();
    }

    @Deprecated
    @VisibleForTesting
    public void setDragonFight(@Nullable EndDragonFight dragonFight) {
        this.dragonFight = dragonFight;
    }

    public void setWeatherParameters(int clearTime, int weatherTime, boolean isRaining, boolean isThundering) {
        this.serverLevelData.setClearWeatherTime(clearTime);
        this.serverLevelData.setRainTime(weatherTime);
        this.serverLevelData.setThunderTime(weatherTime);
        this.serverLevelData.setRaining(isRaining);
        this.serverLevelData.setThundering(isThundering);
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        return this.getChunkSource().getGenerator().getBiomeSource().getNoiseBiome(x, y, z, this.getChunkSource().randomState().sampler());
    }

    public StructureManager structureManager() {
        return this.structureManager;
    }

    @Override
    public EnvironmentAttributeSystem environmentAttributes() {
        return this.environmentAttributes;
    }

    public void tick(BooleanSupplier hasTimeLeft) {
        ProfilerFiller profilerFiller = Profiler.get();
        this.handlingTick = true;
        TickRateManager tickRateManager = this.tickRateManager();
        boolean runsNormally = tickRateManager.runsNormally();
        if (runsNormally) {
            profilerFiller.push("world border");
            this.getWorldBorder().tick();
            profilerFiller.popPush("weather");
            this.advanceWeatherCycle();
            profilerFiller.pop();
        }

        int i = this.getGameRules().get(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
        if (this.sleepStatus.areEnoughSleeping(i) && this.sleepStatus.areEnoughDeepSleeping(i, this.players)) {
            if (this.getGameRules().get(GameRules.ADVANCE_TIME)) {
                long l = this.levelData.getDayTime() + 24000L;
                this.setDayTime(l - l % 24000L);
            }

            this.wakeUpAllPlayers();
            if (this.getGameRules().get(GameRules.ADVANCE_WEATHER) && this.isRaining()) {
                this.resetWeatherCycle();
            }
        }

        this.updateSkyBrightness();
        if (runsNormally) {
            this.tickTime();
        }

        profilerFiller.push("tickPending");
        if (!this.isDebug() && runsNormally) {
            long l = this.getGameTime();
            profilerFiller.push("blockTicks");
            this.blockTicks.tick(l, 65536, this::tickBlock);
            profilerFiller.popPush("fluidTicks");
            this.fluidTicks.tick(l, 65536, this::tickFluid);
            profilerFiller.pop();
        }

        profilerFiller.popPush("raid");
        if (runsNormally) {
            this.raids.tick(this);
        }

        profilerFiller.popPush("chunkSource");
        this.getChunkSource().tick(hasTimeLeft, true);
        profilerFiller.popPush("blockEvents");
        if (runsNormally) {
            this.runBlockEvents();
        }

        this.handlingTick = false;
        profilerFiller.pop();
        boolean hasActiveTickets = this.chunkSource.hasActiveTickets();
        if (hasActiveTickets) {
            this.resetEmptyTime();
        }

        if (runsNormally) {
            this.emptyTime++;
        }

        if (this.emptyTime < 300) {
            profilerFiller.push("entities");
            if (this.dragonFight != null && runsNormally) {
                profilerFiller.push("dragonFight");
                this.dragonFight.tick();
                profilerFiller.pop();
            }

            this.entityTickList
                .forEach(
                    entity -> {
                        if (!entity.isRemoved()) {
                            if (!tickRateManager.isEntityFrozen(entity)) {
                                profilerFiller.push("checkDespawn");
                                entity.checkDespawn();
                                profilerFiller.pop();
                                if (entity instanceof ServerPlayer
                                    || this.chunkSource.chunkMap.getDistanceManager().inEntityTickingRange(entity.chunkPosition().toLong())) {
                                    Entity vehicle = entity.getVehicle();
                                    if (vehicle != null) {
                                        if (!vehicle.isRemoved() && vehicle.hasPassenger(entity)) {
                                            return;
                                        }

                                        entity.stopRiding();
                                    }

                                    profilerFiller.push("tick");
                                    this.guardEntityTick(this::tickNonPassenger, entity);
                                    profilerFiller.pop();
                                }
                            }
                        }
                    }
                );
            profilerFiller.popPush("blockEntities");
            this.tickBlockEntities();
            profilerFiller.pop();
        }

        profilerFiller.push("entityManagement");
        this.entityManager.tick();
        profilerFiller.pop();
        profilerFiller.push("debugSynchronizers");
        if (this.debugSynchronizers.hasAnySubscriberFor(DebugSubscriptions.NEIGHBOR_UPDATES)) {
            this.neighborUpdater
                .setDebugListener(blockPos -> this.debugSynchronizers.broadcastEventToTracking(blockPos, DebugSubscriptions.NEIGHBOR_UPDATES, blockPos));
        } else {
            this.neighborUpdater.setDebugListener(null);
        }

        this.debugSynchronizers.tick(this.server.debugSubscribers());
        profilerFiller.pop();
        this.environmentAttributes().invalidateTickCache();
    }

    @Override
    public boolean shouldTickBlocksAt(long chunkPos) {
        return this.chunkSource.chunkMap.getDistanceManager().inBlockTickingRange(chunkPos);
    }

    protected void tickTime() {
        if (this.tickTime) {
            long l = this.levelData.getGameTime() + 1L;
            this.serverLevelData.setGameTime(l);
            Profiler.get().push("scheduledFunctions");
            this.serverLevelData.getScheduledEvents().tick(this.server, l);
            Profiler.get().pop();
            if (this.getGameRules().get(GameRules.ADVANCE_TIME)) {
                this.setDayTime(this.levelData.getDayTime() + 1L);
            }
        }
    }

    public void setDayTime(long time) {
        this.serverLevelData.setDayTime(time);
    }

    public long getDayCount() {
        return this.getDayTime() / 24000L;
    }

    public void tickCustomSpawners(boolean spawnEnemies) {
        for (CustomSpawner customSpawner : this.customSpawners) {
            customSpawner.tick(this, spawnEnemies);
        }
    }

    private void wakeUpAllPlayers() {
        this.sleepStatus.removeAllSleepers();
        this.players.stream().filter(LivingEntity::isSleeping).collect(Collectors.toList()).forEach(player -> player.stopSleepInBed(false, false));
    }

    public void tickChunk(LevelChunk chunk, int randomTickSpeed) {
        ChunkPos pos = chunk.getPos();
        int minBlockX = pos.getMinBlockX();
        int minBlockZ = pos.getMinBlockZ();
        ProfilerFiller profilerFiller = Profiler.get();
        profilerFiller.push("iceandsnow");

        for (int i = 0; i < randomTickSpeed; i++) {
            if (this.random.nextInt(48) == 0) {
                this.tickPrecipitation(this.getBlockRandomPos(minBlockX, 0, minBlockZ, 15));
            }
        }

        profilerFiller.popPush("tickBlocks");
        if (randomTickSpeed > 0) {
            LevelChunkSection[] sections = chunk.getSections();

            for (int i1 = 0; i1 < sections.length; i1++) {
                LevelChunkSection levelChunkSection = sections[i1];
                if (levelChunkSection.isRandomlyTicking()) {
                    int sectionYFromSectionIndex = chunk.getSectionYFromSectionIndex(i1);
                    int blockPosCoord = SectionPos.sectionToBlockCoord(sectionYFromSectionIndex);

                    for (int i2 = 0; i2 < randomTickSpeed; i2++) {
                        BlockPos blockRandomPos = this.getBlockRandomPos(minBlockX, blockPosCoord, minBlockZ, 15);
                        profilerFiller.push("randomTick");
                        BlockState blockState = levelChunkSection.getBlockState(
                            blockRandomPos.getX() - minBlockX, blockRandomPos.getY() - blockPosCoord, blockRandomPos.getZ() - minBlockZ
                        );
                        if (blockState.isRandomlyTicking()) {
                            blockState.randomTick(this, blockRandomPos, this.random);
                        }

                        FluidState fluidState = blockState.getFluidState();
                        if (fluidState.isRandomlyTicking()) {
                            fluidState.randomTick(this, blockRandomPos, this.random);
                        }

                        profilerFiller.pop();
                    }
                }
            }
        }

        profilerFiller.pop();
    }

    public void tickThunder(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        boolean isRaining = this.isRaining();
        int minBlockX = pos.getMinBlockX();
        int minBlockZ = pos.getMinBlockZ();
        ProfilerFiller profilerFiller = Profiler.get();
        profilerFiller.push("thunder");
        if (isRaining && this.isThundering() && this.random.nextInt(100000) == 0) {
            BlockPos blockPos = this.findLightningTargetAround(this.getBlockRandomPos(minBlockX, 0, minBlockZ, 15));
            if (this.isRainingAt(blockPos)) {
                DifficultyInstance currentDifficultyAt = this.getCurrentDifficultyAt(blockPos);
                boolean flag = this.getGameRules().get(GameRules.SPAWN_MOBS)
                    && this.random.nextDouble() < currentDifficultyAt.getEffectiveDifficulty() * 0.01
                    && !this.getBlockState(blockPos.below()).is(BlockTags.LIGHTNING_RODS);
                if (flag) {
                    SkeletonHorse skeletonHorse = EntityType.SKELETON_HORSE.create(this, EntitySpawnReason.EVENT);
                    if (skeletonHorse != null) {
                        skeletonHorse.setTrap(true);
                        skeletonHorse.setAge(0);
                        skeletonHorse.setPos(blockPos.getX(), blockPos.getY(), blockPos.getZ());
                        this.addFreshEntity(skeletonHorse);
                    }
                }

                LightningBolt lightningBolt = EntityType.LIGHTNING_BOLT.create(this, EntitySpawnReason.EVENT);
                if (lightningBolt != null) {
                    lightningBolt.snapTo(Vec3.atBottomCenterOf(blockPos));
                    lightningBolt.setVisualOnly(flag);
                    this.addFreshEntity(lightningBolt);
                }
            }
        }

        profilerFiller.pop();
    }

    @VisibleForTesting
    public void tickPrecipitation(BlockPos pos) {
        BlockPos heightmapPos = this.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos);
        BlockPos blockPos = heightmapPos.below();
        Biome biome = this.getBiome(heightmapPos).value();
        if (biome.shouldFreeze(this, blockPos)) {
            this.setBlockAndUpdate(blockPos, Blocks.ICE.defaultBlockState());
        }

        if (this.isRaining()) {
            int i = this.getGameRules().get(GameRules.MAX_SNOW_ACCUMULATION_HEIGHT);
            if (i > 0 && biome.shouldSnow(this, heightmapPos)) {
                BlockState blockState = this.getBlockState(heightmapPos);
                if (blockState.is(Blocks.SNOW)) {
                    int layersValue = blockState.getValue(SnowLayerBlock.LAYERS);
                    if (layersValue < Math.min(i, 8)) {
                        BlockState blockState1 = blockState.setValue(SnowLayerBlock.LAYERS, layersValue + 1);
                        Block.pushEntitiesUp(blockState, blockState1, this, heightmapPos);
                        this.setBlockAndUpdate(heightmapPos, blockState1);
                    }
                } else {
                    this.setBlockAndUpdate(heightmapPos, Blocks.SNOW.defaultBlockState());
                }
            }

            Biome.Precipitation precipitationAt = biome.getPrecipitationAt(blockPos, this.getSeaLevel());
            if (precipitationAt != Biome.Precipitation.NONE) {
                BlockState blockState2 = this.getBlockState(blockPos);
                blockState2.getBlock().handlePrecipitation(blockState2, this, blockPos, precipitationAt);
            }
        }
    }

    public Optional<BlockPos> findLightningRod(BlockPos pos) {
        Optional<BlockPos> optional = this.getPoiManager()
            .findClosest(
                poiType -> poiType.is(PoiTypes.LIGHTNING_ROD),
                blockPos -> blockPos.getY() == this.getHeight(Heightmap.Types.WORLD_SURFACE, blockPos.getX(), blockPos.getZ()) - 1,
                pos,
                128,
                PoiManager.Occupancy.ANY
            );
        return optional.map(blockPos -> blockPos.above(1));
    }

    protected BlockPos findLightningTargetAround(BlockPos pos) {
        BlockPos heightmapPos = this.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos);
        Optional<BlockPos> optional = this.findLightningRod(heightmapPos);
        if (optional.isPresent()) {
            return optional.get();
        } else {
            AABB aabb = AABB.encapsulatingFullBlocks(heightmapPos, heightmapPos.atY(this.getMaxY() + 1)).inflate(3.0);
            List<LivingEntity> entitiesOfClass = this.getEntitiesOfClass(
                LivingEntity.class, aabb, entity -> entity.isAlive() && this.canSeeSky(entity.blockPosition())
            );
            if (!entitiesOfClass.isEmpty()) {
                return entitiesOfClass.get(this.random.nextInt(entitiesOfClass.size())).blockPosition();
            } else {
                if (heightmapPos.getY() == this.getMinY() - 1) {
                    heightmapPos = heightmapPos.above(2);
                }

                return heightmapPos;
            }
        }
    }

    public boolean isHandlingTick() {
        return this.handlingTick;
    }

    public boolean canSleepThroughNights() {
        return this.getGameRules().get(GameRules.PLAYERS_SLEEPING_PERCENTAGE) <= 100;
    }

    private void announceSleepStatus() {
        if (this.canSleepThroughNights()) {
            if (!this.getServer().isSingleplayer() || this.getServer().isPublished()) {
                int i = this.getGameRules().get(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
                Component component;
                if (this.sleepStatus.areEnoughSleeping(i)) {
                    component = Component.translatable("sleep.skipping_night");
                } else {
                    component = Component.translatable("sleep.players_sleeping", this.sleepStatus.amountSleeping(), this.sleepStatus.sleepersNeeded(i));
                }

                for (ServerPlayer serverPlayer : this.players) {
                    serverPlayer.displayClientMessage(component, true);
                }
            }
        }
    }

    public void updateSleepingPlayerList() {
        if (!this.players.isEmpty() && this.sleepStatus.update(this.players)) {
            this.announceSleepStatus();
        }
    }

    @Override
    public ServerScoreboard getScoreboard() {
        return this.server.getScoreboard();
    }

    public ServerWaypointManager getWaypointManager() {
        return this.waypointManager;
    }

    @Override
    public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
        long l = 0L;
        float f = 0.0F;
        ChunkAccess chunk = this.getChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()), ChunkStatus.FULL, false);
        if (chunk != null) {
            l = chunk.getInhabitedTime();
            f = this.getMoonBrightness(pos);
        }

        return new DifficultyInstance(this.getDifficulty(), this.getDayTime(), l, f);
    }

    public float getMoonBrightness(BlockPos pos) {
        MoonPhase moonPhase = this.environmentAttributes.getValue(EnvironmentAttributes.MOON_PHASE, pos);
        return DimensionType.MOON_BRIGHTNESS_PER_PHASE[moonPhase.index()];
    }

    private void advanceWeatherCycle() {
        boolean isRaining = this.isRaining();
        if (this.canHaveWeather()) {
            if (this.getGameRules().get(GameRules.ADVANCE_WEATHER)) {
                int clearWeatherTime = this.serverLevelData.getClearWeatherTime();
                int thunderTime = this.serverLevelData.getThunderTime();
                int rainTime = this.serverLevelData.getRainTime();
                boolean isThundering = this.levelData.isThundering();
                boolean isRaining1 = this.levelData.isRaining();
                if (clearWeatherTime > 0) {
                    clearWeatherTime--;
                    thunderTime = isThundering ? 0 : 1;
                    rainTime = isRaining1 ? 0 : 1;
                    isThundering = false;
                    isRaining1 = false;
                } else {
                    if (thunderTime > 0) {
                        if (--thunderTime == 0) {
                            isThundering = !isThundering;
                        }
                    } else if (isThundering) {
                        thunderTime = THUNDER_DURATION.sample(this.random);
                    } else {
                        thunderTime = THUNDER_DELAY.sample(this.random);
                    }

                    if (rainTime > 0) {
                        if (--rainTime == 0) {
                            isRaining1 = !isRaining1;
                        }
                    } else if (isRaining1) {
                        rainTime = RAIN_DURATION.sample(this.random);
                    } else {
                        rainTime = RAIN_DELAY.sample(this.random);
                    }
                }

                this.serverLevelData.setThunderTime(thunderTime);
                this.serverLevelData.setRainTime(rainTime);
                this.serverLevelData.setClearWeatherTime(clearWeatherTime);
                this.serverLevelData.setThundering(isThundering);
                this.serverLevelData.setRaining(isRaining1);
            }

            this.oThunderLevel = this.thunderLevel;
            if (this.levelData.isThundering()) {
                this.thunderLevel += 0.01F;
            } else {
                this.thunderLevel -= 0.01F;
            }

            this.thunderLevel = Mth.clamp(this.thunderLevel, 0.0F, 1.0F);
            this.oRainLevel = this.rainLevel;
            if (this.levelData.isRaining()) {
                this.rainLevel += 0.01F;
            } else {
                this.rainLevel -= 0.01F;
            }

            this.rainLevel = Mth.clamp(this.rainLevel, 0.0F, 1.0F);
        }

        if (this.oRainLevel != this.rainLevel) {
            this.server
                .getPlayerList()
                .broadcastAll(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, this.rainLevel), this.dimension());
        }

        if (this.oThunderLevel != this.thunderLevel) {
            this.server
                .getPlayerList()
                .broadcastAll(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, this.thunderLevel), this.dimension());
        }

        if (isRaining != this.isRaining()) {
            if (isRaining) {
                this.server.getPlayerList().broadcastAll(new ClientboundGameEventPacket(ClientboundGameEventPacket.STOP_RAINING, 0.0F));
            } else {
                this.server.getPlayerList().broadcastAll(new ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0.0F));
            }

            this.server.getPlayerList().broadcastAll(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, this.rainLevel));
            this.server.getPlayerList().broadcastAll(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, this.thunderLevel));
        }
    }

    @VisibleForTesting
    public void resetWeatherCycle() {
        this.serverLevelData.setRainTime(0);
        this.serverLevelData.setRaining(false);
        this.serverLevelData.setThunderTime(0);
        this.serverLevelData.setThundering(false);
    }

    public void resetEmptyTime() {
        this.emptyTime = 0;
    }

    private void tickFluid(BlockPos pos, Fluid fluid) {
        BlockState blockState = this.getBlockState(pos);
        FluidState fluidState = blockState.getFluidState();
        if (fluidState.is(fluid)) {
            fluidState.tick(this, pos, blockState);
        }
    }

    private void tickBlock(BlockPos pos, Block block) {
        BlockState blockState = this.getBlockState(pos);
        if (blockState.is(block)) {
            blockState.tick(this, pos, this.random);
        }
    }

    public void tickNonPassenger(Entity entity) {
        entity.setOldPosAndRot();
        ProfilerFiller profilerFiller = Profiler.get();
        entity.tickCount++;
        profilerFiller.push(() -> BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        profilerFiller.incrementCounter("tickNonPassenger");
        entity.tick();
        profilerFiller.pop();

        for (Entity entity1 : entity.getPassengers()) {
            this.tickPassenger(entity, entity1);
        }
    }

    private void tickPassenger(Entity ridingEntity, Entity passengerEntity) {
        if (passengerEntity.isRemoved() || passengerEntity.getVehicle() != ridingEntity) {
            passengerEntity.stopRiding();
        } else if (passengerEntity instanceof Player || this.entityTickList.contains(passengerEntity)) {
            passengerEntity.setOldPosAndRot();
            passengerEntity.tickCount++;
            ProfilerFiller profilerFiller = Profiler.get();
            profilerFiller.push(() -> BuiltInRegistries.ENTITY_TYPE.getKey(passengerEntity.getType()).toString());
            profilerFiller.incrementCounter("tickPassenger");
            passengerEntity.rideTick();
            profilerFiller.pop();

            for (Entity entity : passengerEntity.getPassengers()) {
                this.tickPassenger(passengerEntity, entity);
            }
        }
    }

    public void updateNeighboursOnBlockSet(BlockPos pos, BlockState state) {
        BlockState blockState = this.getBlockState(pos);
        Block block = blockState.getBlock();
        boolean flag = !state.is(block);
        if (flag) {
            state.affectNeighborsAfterRemoval(this, pos, false);
        }

        this.updateNeighborsAt(pos, blockState.getBlock());
        if (blockState.hasAnalogOutputSignal()) {
            this.updateNeighbourForOutputSignal(pos, block);
        }
    }

    @Override
    public boolean mayInteract(Entity entity, BlockPos pos) {
        return !(entity instanceof Player player && (this.server.isUnderSpawnProtection(this, pos, player) || !this.getWorldBorder().isWithinBounds(pos)));
    }

    public void save(@Nullable ProgressListener progress, boolean flush, boolean skipSave) {
        ServerChunkCache chunkSource = this.getChunkSource();
        if (!skipSave) {
            if (progress != null) {
                progress.progressStartNoAbort(Component.translatable("menu.savingLevel"));
            }

            this.saveLevelData(flush);
            if (progress != null) {
                progress.progressStage(Component.translatable("menu.savingChunks"));
            }

            chunkSource.save(flush);
            if (flush) {
                this.entityManager.saveAll();
            } else {
                this.entityManager.autoSave();
            }
        }
    }

    private void saveLevelData(boolean join) {
        if (this.dragonFight != null) {
            this.server.getWorldData().setEndDragonFightData(this.dragonFight.saveData());
        }

        DimensionDataStorage dataStorage = this.getChunkSource().getDataStorage();
        if (join) {
            dataStorage.saveAndJoin();
        } else {
            dataStorage.scheduleSave();
        }
    }

    public <T extends Entity> List<? extends T> getEntities(EntityTypeTest<Entity, T> typeTest, Predicate<? super T> predicate) {
        List<T> list = Lists.newArrayList();
        this.getEntities(typeTest, predicate, list);
        return list;
    }

    public <T extends Entity> void getEntities(EntityTypeTest<Entity, T> typeTest, Predicate<? super T> predicate, List<? super T> output) {
        this.getEntities(typeTest, predicate, output, Integer.MAX_VALUE);
    }

    public <T extends Entity> void getEntities(EntityTypeTest<Entity, T> typeTest, Predicate<? super T> predicate, List<? super T> output, int maxResults) {
        this.getEntities().get(typeTest, entity -> {
            if (predicate.test(entity)) {
                output.add(entity);
                if (output.size() >= maxResults) {
                    return AbortableIterationConsumer.Continuation.ABORT;
                }
            }

            return AbortableIterationConsumer.Continuation.CONTINUE;
        });
    }

    public List<? extends EnderDragon> getDragons() {
        return this.getEntities(EntityType.ENDER_DRAGON, LivingEntity::isAlive);
    }

    public List<ServerPlayer> getPlayers(Predicate<? super ServerPlayer> predicate) {
        return this.getPlayers(predicate, Integer.MAX_VALUE);
    }

    public List<ServerPlayer> getPlayers(Predicate<? super ServerPlayer> predicate, int maxResults) {
        List<ServerPlayer> list = Lists.newArrayList();

        for (ServerPlayer serverPlayer : this.players) {
            if (predicate.test(serverPlayer)) {
                list.add(serverPlayer);
                if (list.size() >= maxResults) {
                    return list;
                }
            }
        }

        return list;
    }

    public @Nullable ServerPlayer getRandomPlayer() {
        List<ServerPlayer> players = this.getPlayers(LivingEntity::isAlive);
        return players.isEmpty() ? null : players.get(this.random.nextInt(players.size()));
    }

    @Override
    public boolean addFreshEntity(Entity entity) {
        return this.addEntity(entity);
    }

    public boolean addWithUUID(Entity entity) {
        return this.addEntity(entity);
    }

    public void addDuringTeleport(Entity entity) {
        if (entity instanceof ServerPlayer serverPlayer) {
            this.addPlayer(serverPlayer);
        } else {
            this.addEntity(entity);
        }
    }

    public void addNewPlayer(ServerPlayer player) {
        this.addPlayer(player);
    }

    public void addRespawnedPlayer(ServerPlayer player) {
        this.addPlayer(player);
    }

    private void addPlayer(ServerPlayer player) {
        Entity entity = this.getEntity(player.getUUID());
        if (entity != null) {
            LOGGER.warn("Force-added player with duplicate UUID {}", player.getUUID());
            entity.unRide();
            this.removePlayerImmediately((ServerPlayer)entity, Entity.RemovalReason.DISCARDED);
        }

        this.entityManager.addNewEntity(player);
    }

    private boolean addEntity(Entity entity) {
        if (entity.isRemoved()) {
            LOGGER.warn("Tried to add entity {} but it was marked as removed already", EntityType.getKey(entity.getType()));
            return false;
        } else {
            return this.entityManager.addNewEntity(entity);
        }
    }

    public boolean tryAddFreshEntityWithPassengers(Entity entity) {
        if (entity.getSelfAndPassengers().map(Entity::getUUID).anyMatch(this.entityManager::isLoaded)) {
            return false;
        } else {
            this.addFreshEntityWithPassengers(entity);
            return true;
        }
    }

    public void unload(LevelChunk chunk) {
        chunk.clearAllBlockEntities();
        chunk.unregisterTickContainerFromLevel(this);
        this.debugSynchronizers.dropChunk(chunk.getPos());
    }

    public void removePlayerImmediately(ServerPlayer player, Entity.RemovalReason reason) {
        player.remove(reason);
    }

    @Override
    public void destroyBlockProgress(int breakerId, BlockPos pos, int progress) {
        for (ServerPlayer serverPlayer : this.server.getPlayerList().getPlayers()) {
            if (serverPlayer.level() == this && serverPlayer.getId() != breakerId) {
                double d = pos.getX() - serverPlayer.getX();
                double d1 = pos.getY() - serverPlayer.getY();
                double d2 = pos.getZ() - serverPlayer.getZ();
                if (d * d + d1 * d1 + d2 * d2 < 1024.0) {
                    serverPlayer.connection.send(new ClientboundBlockDestructionPacket(breakerId, pos, progress));
                }
            }
        }
    }

    @Override
    public void playSeededSound(
        @Nullable Entity entity, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed
    ) {
        this.server
            .getPlayerList()
            .broadcast(
                entity instanceof Player player ? player : null,
                x,
                y,
                z,
                sound.value().getRange(volume),
                this.dimension(),
                new ClientboundSoundPacket(sound, source, x, y, z, volume, pitch, seed)
            );
    }

    @Override
    public void playSeededSound(
        @Nullable Entity entity, Entity sourceEntity, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed
    ) {
        this.server
            .getPlayerList()
            .broadcast(
                entity instanceof Player player ? player : null,
                sourceEntity.getX(),
                sourceEntity.getY(),
                sourceEntity.getZ(),
                sound.value().getRange(volume),
                this.dimension(),
                new ClientboundSoundEntityPacket(sound, source, sourceEntity, volume, pitch, seed)
            );
    }

    @Override
    public void globalLevelEvent(int id, BlockPos pos, int data) {
        if (this.getGameRules().get(GameRules.GLOBAL_SOUND_EVENTS)) {
            this.server.getPlayerList().getPlayers().forEach(player -> {
                Vec3 vec31;
                if (player.level() == this) {
                    Vec3 vec3 = Vec3.atCenterOf(pos);
                    if (player.distanceToSqr(vec3) < Mth.square(32)) {
                        vec31 = vec3;
                    } else {
                        Vec3 vec32 = vec3.subtract(player.position()).normalize();
                        vec31 = player.position().add(vec32.scale(32.0));
                    }
                } else {
                    vec31 = player.position();
                }

                player.connection.send(new ClientboundLevelEventPacket(id, BlockPos.containing(vec31), data, true));
            });
        } else {
            this.levelEvent(null, id, pos, data);
        }
    }

    @Override
    public void levelEvent(@Nullable Entity entity, int type, BlockPos pos, int data) {
        this.server
            .getPlayerList()
            .broadcast(
                entity instanceof Player player ? player : null,
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                64.0,
                this.dimension(),
                new ClientboundLevelEventPacket(type, pos, data, false)
            );
    }

    public int getLogicalHeight() {
        return this.dimensionType().logicalHeight();
    }

    @Override
    public void gameEvent(Holder<GameEvent> gameEvent, Vec3 pos, GameEvent.Context context) {
        this.gameEventDispatcher.post(gameEvent, pos, context);
    }

    @Override
    public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
        if (this.isUpdatingNavigations) {
            String string = "recursive call to sendBlockUpdated";
            Util.logAndPauseIfInIde("recursive call to sendBlockUpdated", new IllegalStateException("recursive call to sendBlockUpdated"));
        }

        this.getChunkSource().blockChanged(pos);
        this.pathTypesByPosCache.invalidate(pos);
        VoxelShape collisionShape = oldState.getCollisionShape(this, pos);
        VoxelShape collisionShape1 = newState.getCollisionShape(this, pos);
        if (Shapes.joinIsNotEmpty(collisionShape, collisionShape1, BooleanOp.NOT_SAME)) {
            List<PathNavigation> list = new ObjectArrayList<>();

            for (Mob mob : this.navigatingMobs) {
                PathNavigation navigation = mob.getNavigation();
                if (navigation.shouldRecomputePath(pos)) {
                    list.add(navigation);
                }
            }

            try {
                this.isUpdatingNavigations = true;

                for (PathNavigation pathNavigation : list) {
                    pathNavigation.recomputePath();
                }
            } finally {
                this.isUpdatingNavigations = false;
            }
        }
    }

    @Override
    public void updateNeighborsAt(BlockPos pos, Block block) {
        this.updateNeighborsAt(pos, block, ExperimentalRedstoneUtils.initialOrientation(this, null, null));
    }

    @Override
    public void updateNeighborsAt(BlockPos pos, Block block, @Nullable Orientation orientation) {
        this.neighborUpdater.updateNeighborsAtExceptFromFacing(pos, block, null, orientation);
    }

    @Override
    public void updateNeighborsAtExceptFromFacing(BlockPos pos, Block block, Direction facing, @Nullable Orientation orientation) {
        this.neighborUpdater.updateNeighborsAtExceptFromFacing(pos, block, facing, orientation);
    }

    @Override
    public void neighborChanged(BlockPos pos, Block block, @Nullable Orientation orientation) {
        this.neighborUpdater.neighborChanged(pos, block, orientation);
    }

    @Override
    public void neighborChanged(BlockState state, BlockPos pos, Block block, @Nullable Orientation orientation, boolean movedByPiston) {
        this.neighborUpdater.neighborChanged(state, pos, block, orientation, movedByPiston);
    }

    @Override
    public void broadcastEntityEvent(Entity entity, byte state) {
        this.getChunkSource().sendToTrackingPlayersAndSelf(entity, new ClientboundEntityEventPacket(entity, state));
    }

    @Override
    public void broadcastDamageEvent(Entity entity, DamageSource damageSource) {
        this.getChunkSource().sendToTrackingPlayersAndSelf(entity, new ClientboundDamageEventPacket(entity, damageSource));
    }

    @Override
    public ServerChunkCache getChunkSource() {
        return this.chunkSource;
    }

    @Override
    public void explode(
        @Nullable Entity source,
        @Nullable DamageSource damageSource,
        @Nullable ExplosionDamageCalculator damageCalculator,
        double x,
        double y,
        double z,
        float radius,
        boolean fire,
        Level.ExplosionInteraction explosionInteraction,
        ParticleOptions smallExplosionParticles,
        ParticleOptions largeExplosionParticles,
        WeightedList<ExplosionParticleInfo> blockParticles,
        Holder<SoundEvent> explosionSound
    ) {
        Explosion.BlockInteraction blockInteraction = switch (explosionInteraction) {
            case NONE -> Explosion.BlockInteraction.KEEP;
            case BLOCK -> this.getDestroyType(GameRules.BLOCK_EXPLOSION_DROP_DECAY);
            case MOB -> this.getGameRules().get(GameRules.MOB_GRIEFING)
                ? this.getDestroyType(GameRules.MOB_EXPLOSION_DROP_DECAY)
                : Explosion.BlockInteraction.KEEP;
            case TNT -> this.getDestroyType(GameRules.TNT_EXPLOSION_DROP_DECAY);
            case TRIGGER -> Explosion.BlockInteraction.TRIGGER_BLOCK;
        };
        Vec3 vec3 = new Vec3(x, y, z);
        ServerExplosion serverExplosion = new ServerExplosion(this, source, damageSource, damageCalculator, vec3, radius, fire, blockInteraction);
        int i = serverExplosion.explode();
        ParticleOptions particleOptions = serverExplosion.isSmall() ? smallExplosionParticles : largeExplosionParticles;

        for (ServerPlayer serverPlayer : this.players) {
            if (serverPlayer.distanceToSqr(vec3) < 4096.0) {
                Optional<Vec3> optional = Optional.ofNullable(serverExplosion.getHitPlayers().get(serverPlayer));
                serverPlayer.connection.send(new ClientboundExplodePacket(vec3, radius, i, optional, particleOptions, explosionSound, blockParticles));
            }
        }
    }

    private Explosion.BlockInteraction getDestroyType(GameRule<Boolean> decayGameRule) {
        return this.getGameRules().get(decayGameRule) ? Explosion.BlockInteraction.DESTROY_WITH_DECAY : Explosion.BlockInteraction.DESTROY;
    }

    @Override
    public void blockEvent(BlockPos pos, Block block, int eventId, int eventParam) {
        this.blockEvents.add(new BlockEventData(pos, block, eventId, eventParam));
    }

    private void runBlockEvents() {
        this.blockEventsToReschedule.clear();

        while (!this.blockEvents.isEmpty()) {
            BlockEventData blockEventData = this.blockEvents.removeFirst();
            if (this.shouldTickBlocksAt(blockEventData.pos())) {
                if (this.doBlockEvent(blockEventData)) {
                    this.server
                        .getPlayerList()
                        .broadcast(
                            null,
                            blockEventData.pos().getX(),
                            blockEventData.pos().getY(),
                            blockEventData.pos().getZ(),
                            64.0,
                            this.dimension(),
                            new ClientboundBlockEventPacket(blockEventData.pos(), blockEventData.block(), blockEventData.paramA(), blockEventData.paramB())
                        );
                }
            } else {
                this.blockEventsToReschedule.add(blockEventData);
            }
        }

        this.blockEvents.addAll(this.blockEventsToReschedule);
    }

    private boolean doBlockEvent(BlockEventData event) {
        BlockState blockState = this.getBlockState(event.pos());
        return blockState.is(event.block()) && blockState.triggerEvent(this, event.pos(), event.paramA(), event.paramB());
    }

    @Override
    public LevelTicks<Block> getBlockTicks() {
        return this.blockTicks;
    }

    @Override
    public LevelTicks<Fluid> getFluidTicks() {
        return this.fluidTicks;
    }

    @Override
    public MinecraftServer getServer() {
        return this.server;
    }

    public PortalForcer getPortalForcer() {
        return this.portalForcer;
    }

    public StructureTemplateManager getStructureManager() {
        return this.server.getStructureManager();
    }

    public <T extends ParticleOptions> int sendParticles(
        T options, double x, double y, double z, int count, double xDist, double yDist, double zDist, double speed
    ) {
        return this.sendParticles(options, false, false, x, y, z, count, xDist, yDist, zDist, speed);
    }

    public <T extends ParticleOptions> int sendParticles(
        T options, boolean overrideLimiter, boolean alwaysShow, double x, double y, double z, int count, double xDist, double yDist, double zDist, double speed
    ) {
        ClientboundLevelParticlesPacket clientboundLevelParticlesPacket = new ClientboundLevelParticlesPacket(
            options, overrideLimiter, alwaysShow, x, y, z, (float)xDist, (float)yDist, (float)zDist, (float)speed, count
        );
        int i = 0;

        for (int i1 = 0; i1 < this.players.size(); i1++) {
            ServerPlayer serverPlayer = this.players.get(i1);
            if (this.sendParticles(serverPlayer, overrideLimiter, x, y, z, clientboundLevelParticlesPacket)) {
                i++;
            }
        }

        return i;
    }

    public <T extends ParticleOptions> boolean sendParticles(
        ServerPlayer player,
        T particle,
        boolean overrideLimiter,
        boolean alwaysShow,
        double posX,
        double posY,
        double posZ,
        int count,
        double xDist,
        double yDist,
        double zDist,
        double maxSpeed
    ) {
        Packet<?> packet = new ClientboundLevelParticlesPacket(
            particle, overrideLimiter, alwaysShow, posX, posY, posZ, (float)xDist, (float)yDist, (float)zDist, (float)maxSpeed, count
        );
        return this.sendParticles(player, overrideLimiter, posX, posY, posZ, packet);
    }

    private boolean sendParticles(ServerPlayer player, boolean overrideLimiter, double x, double y, double z, Packet<?> packet) {
        if (player.level() != this) {
            return false;
        } else {
            BlockPos blockPos = player.blockPosition();
            if (blockPos.closerToCenterThan(new Vec3(x, y, z), overrideLimiter ? 512.0 : 32.0)) {
                player.connection.send(packet);
                return true;
            } else {
                return false;
            }
        }
    }

    @Override
    public @Nullable Entity getEntity(int id) {
        return this.getEntities().get(id);
    }

    @Override
    public @Nullable Entity getEntityInAnyDimension(UUID id) {
        Entity entity = this.getEntity(id);
        if (entity != null) {
            return entity;
        } else {
            for (ServerLevel serverLevel : this.getServer().getAllLevels()) {
                if (serverLevel != this) {
                    Entity entity1 = serverLevel.getEntity(id);
                    if (entity1 != null) {
                        return entity1;
                    }
                }
            }

            return null;
        }
    }

    @Override
    public @Nullable Player getPlayerInAnyDimension(UUID id) {
        return this.getServer().getPlayerList().getPlayer(id);
    }

    @Deprecated
    public @Nullable Entity getEntityOrPart(int id) {
        Entity entity = this.getEntities().get(id);
        return entity != null ? entity : this.dragonParts.get(id);
    }

    @Override
    public Collection<EnderDragonPart> dragonParts() {
        return this.dragonParts.values();
    }

    public @Nullable BlockPos findNearestMapStructure(TagKey<Structure> structureTag, BlockPos pos, int radius, boolean skipKnownStructures) {
        if (!this.server.getWorldData().worldGenOptions().generateStructures()) {
            return null;
        } else {
            Optional<HolderSet.Named<Structure>> optional = this.registryAccess().lookupOrThrow(Registries.STRUCTURE).get(structureTag);
            if (optional.isEmpty()) {
                return null;
            } else {
                Pair<BlockPos, Holder<Structure>> pair = this.getChunkSource()
                    .getGenerator()
                    .findNearestMapStructure(this, optional.get(), pos, radius, skipKnownStructures);
                return pair != null ? pair.getFirst() : null;
            }
        }
    }

    public @Nullable Pair<BlockPos, Holder<Biome>> findClosestBiome3d(
        Predicate<Holder<Biome>> biomePredicate, BlockPos pos, int radius, int horizontalStep, int verticalStep
    ) {
        return this.getChunkSource()
            .getGenerator()
            .getBiomeSource()
            .findClosestBiome3d(pos, radius, horizontalStep, verticalStep, biomePredicate, this.getChunkSource().randomState().sampler(), this);
    }

    @Override
    public WorldBorder getWorldBorder() {
        WorldBorder worldBorder = this.getDataStorage().computeIfAbsent(WorldBorder.TYPE);
        worldBorder.applyInitialSettings(this.levelData.getGameTime());
        return worldBorder;
    }

    @Override
    public RecipeManager recipeAccess() {
        return this.server.getRecipeManager();
    }

    @Override
    public TickRateManager tickRateManager() {
        return this.server.tickRateManager();
    }

    @Override
    public boolean noSave() {
        return this.noSave;
    }

    public DimensionDataStorage getDataStorage() {
        return this.getChunkSource().getDataStorage();
    }

    @Override
    public @Nullable MapItemSavedData getMapData(MapId mapId) {
        return this.getServer().overworld().getDataStorage().get(MapItemSavedData.type(mapId));
    }

    public void setMapData(MapId mapId, MapItemSavedData data) {
        this.getServer().overworld().getDataStorage().set(MapItemSavedData.type(mapId), data);
    }

    public MapId getFreeMapId() {
        return this.getServer().overworld().getDataStorage().computeIfAbsent(MapIndex.TYPE).getNextMapId();
    }

    @Override
    public void setRespawnData(LevelData.RespawnData respawnData) {
        this.getServer().setRespawnData(respawnData);
    }

    @Override
    public LevelData.RespawnData getRespawnData() {
        return this.getServer().getRespawnData();
    }

    public LongSet getForceLoadedChunks() {
        return this.chunkSource.getForceLoadedChunks();
    }

    public boolean setChunkForced(int chunkX, int chunkZ, boolean add) {
        boolean flag = this.chunkSource.updateChunkForced(new ChunkPos(chunkX, chunkZ), add);
        if (add && flag) {
            this.getChunk(chunkX, chunkZ);
        }

        return flag;
    }

    @Override
    public List<ServerPlayer> players() {
        return this.players;
    }

    @Override
    public void updatePOIOnBlockStateChange(BlockPos pos, BlockState oldState, BlockState newState) {
        Optional<Holder<PoiType>> optional = PoiTypes.forState(oldState);
        Optional<Holder<PoiType>> optional1 = PoiTypes.forState(newState);
        if (!Objects.equals(optional, optional1)) {
            BlockPos blockPos = pos.immutable();
            optional.ifPresent(holder -> this.getServer().execute(() -> {
                this.getPoiManager().remove(blockPos);
                this.debugSynchronizers.dropPoi(blockPos);
            }));
            optional1.ifPresent(holder -> this.getServer().execute(() -> {
                PoiRecord poiRecord = this.getPoiManager().add(blockPos, (Holder<PoiType>)holder);
                if (poiRecord != null) {
                    this.debugSynchronizers.registerPoi(poiRecord);
                }
            }));
        }
    }

    public PoiManager getPoiManager() {
        return this.getChunkSource().getPoiManager();
    }

    public boolean isVillage(BlockPos pos) {
        return this.isCloseToVillage(pos, 1);
    }

    public boolean isVillage(SectionPos pos) {
        return this.isVillage(pos.center());
    }

    public boolean isCloseToVillage(BlockPos pos, int sections) {
        return sections <= 6 && this.sectionsToVillage(SectionPos.of(pos)) <= sections;
    }

    public int sectionsToVillage(SectionPos pos) {
        return this.getPoiManager().sectionsToVillage(pos);
    }

    public Raids getRaids() {
        return this.raids;
    }

    public @Nullable Raid getRaidAt(BlockPos pos) {
        return this.raids.getNearbyRaid(pos, 9216);
    }

    public boolean isRaided(BlockPos pos) {
        return this.getRaidAt(pos) != null;
    }

    public void onReputationEvent(ReputationEventType type, Entity target, ReputationEventHandler host) {
        host.onReputationEventFrom(type, target);
    }

    public void saveDebugReport(Path path) throws IOException {
        ChunkMap chunkMap = this.getChunkSource().chunkMap;

        try (Writer bufferedWriter = Files.newBufferedWriter(path.resolve("stats.txt"))) {
            bufferedWriter.write(String.format(Locale.ROOT, "spawning_chunks: %d\n", chunkMap.getDistanceManager().getNaturalSpawnChunkCount()));
            NaturalSpawner.SpawnState lastSpawnState = this.getChunkSource().getLastSpawnState();
            if (lastSpawnState != null) {
                for (Entry<MobCategory> entry : lastSpawnState.getMobCategoryCounts().object2IntEntrySet()) {
                    bufferedWriter.write(String.format(Locale.ROOT, "spawn_count.%s: %d\n", entry.getKey().getName(), entry.getIntValue()));
                }
            }

            bufferedWriter.write(String.format(Locale.ROOT, "entities: %s\n", this.entityManager.gatherStats()));
            bufferedWriter.write(String.format(Locale.ROOT, "block_entity_tickers: %d\n", this.blockEntityTickers.size()));
            bufferedWriter.write(String.format(Locale.ROOT, "block_ticks: %d\n", this.getBlockTicks().count()));
            bufferedWriter.write(String.format(Locale.ROOT, "fluid_ticks: %d\n", this.getFluidTicks().count()));
            bufferedWriter.write("distance_manager: " + chunkMap.getDistanceManager().getDebugStatus() + "\n");
            bufferedWriter.write(String.format(Locale.ROOT, "pending_tasks: %d\n", this.getChunkSource().getPendingTasksCount()));
        }

        CrashReport crashReport = new CrashReport("Level dump", new Exception("dummy"));
        this.fillReportDetails(crashReport);

        try (Writer bufferedWriter1 = Files.newBufferedWriter(path.resolve("example_crash.txt"))) {
            bufferedWriter1.write(crashReport.getFriendlyReport(ReportType.TEST));
        }

        Path path1 = path.resolve("chunks.csv");

        try (Writer bufferedWriter2 = Files.newBufferedWriter(path1)) {
            chunkMap.dumpChunks(bufferedWriter2);
        }

        Path path2 = path.resolve("entity_chunks.csv");

        try (Writer bufferedWriter3 = Files.newBufferedWriter(path2)) {
            this.entityManager.dumpSections(bufferedWriter3);
        }

        Path path3 = path.resolve("entities.csv");

        try (Writer bufferedWriter4 = Files.newBufferedWriter(path3)) {
            dumpEntities(bufferedWriter4, this.getEntities().getAll());
        }

        Path path4 = path.resolve("block_entities.csv");

        try (Writer bufferedWriter5 = Files.newBufferedWriter(path4)) {
            this.dumpBlockEntityTickers(bufferedWriter5);
        }
    }

    private static void dumpEntities(Writer writer, Iterable<Entity> entities) throws IOException {
        CsvOutput csvOutput = CsvOutput.builder()
            .addColumn("x")
            .addColumn("y")
            .addColumn("z")
            .addColumn("uuid")
            .addColumn("type")
            .addColumn("alive")
            .addColumn("display_name")
            .addColumn("custom_name")
            .build(writer);

        for (Entity entity : entities) {
            Component customName = entity.getCustomName();
            Component displayName = entity.getDisplayName();
            csvOutput.writeRow(
                entity.getX(),
                entity.getY(),
                entity.getZ(),
                entity.getUUID(),
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()),
                entity.isAlive(),
                displayName.getString(),
                customName != null ? customName.getString() : null
            );
        }
    }

    private void dumpBlockEntityTickers(Writer output) throws IOException {
        CsvOutput csvOutput = CsvOutput.builder().addColumn("x").addColumn("y").addColumn("z").addColumn("type").build(output);

        for (TickingBlockEntity tickingBlockEntity : this.blockEntityTickers) {
            BlockPos pos = tickingBlockEntity.getPos();
            csvOutput.writeRow(pos.getX(), pos.getY(), pos.getZ(), tickingBlockEntity.getType());
        }
    }

    @VisibleForTesting
    public void clearBlockEvents(BoundingBox boundingBox) {
        this.blockEvents.removeIf(blockEventData -> boundingBox.isInside(blockEventData.pos()));
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return 1.0F;
    }

    public Iterable<Entity> getAllEntities() {
        return this.getEntities().getAll();
    }

    @Override
    public String toString() {
        return "ServerLevel[" + this.serverLevelData.getLevelName() + "]";
    }

    public boolean isFlat() {
        return this.server.getWorldData().isFlatWorld();
    }

    @Override
    public long getSeed() {
        return this.server.getWorldData().worldGenOptions().seed();
    }

    public @Nullable EndDragonFight getDragonFight() {
        return this.dragonFight;
    }

    @Override
    public ServerLevel getLevel() {
        return this;
    }

    @VisibleForTesting
    public String getWatchdogStats() {
        return String.format(
            Locale.ROOT,
            "players: %s, entities: %s [%s], block_entities: %d [%s], block_ticks: %d, fluid_ticks: %d, chunk_source: %s",
            this.players.size(),
            this.entityManager.gatherStats(),
            getTypeCount(this.entityManager.getEntityGetter().getAll(), entity -> BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()),
            this.blockEntityTickers.size(),
            getTypeCount(this.blockEntityTickers, TickingBlockEntity::getType),
            this.getBlockTicks().count(),
            this.getFluidTicks().count(),
            this.gatherChunkSourceStats()
        );
    }

    private static <T> String getTypeCount(Iterable<T> objects, Function<T, String> typeGetter) {
        try {
            Object2IntOpenHashMap<String> map = new Object2IntOpenHashMap<>();

            for (T object : objects) {
                String string = typeGetter.apply(object);
                map.addTo(string, 1);
            }

            return map.object2IntEntrySet()
                .stream()
                .sorted(Comparator.<Entry<String>, Integer>comparing(Entry::getIntValue).reversed())
                .limit(5L)
                .map(entry -> entry.getKey() + ":" + entry.getIntValue())
                .collect(Collectors.joining(","));
        } catch (Exception var6) {
            return "";
        }
    }

    @Override
    public LevelEntityGetter<Entity> getEntities() {
        return this.entityManager.getEntityGetter();
    }

    public void addLegacyChunkEntities(Stream<Entity> entities) {
        this.entityManager.addLegacyChunkEntities(entities);
    }

    public void addWorldGenChunkEntities(Stream<Entity> entities) {
        this.entityManager.addWorldGenChunkEntities(entities);
    }

    public void startTickingChunk(LevelChunk chunk) {
        chunk.unpackTicks(this.getGameTime());
    }

    public void onStructureStartsAvailable(ChunkAccess chunk) {
        this.server.execute(() -> this.structureCheck.onStructureLoad(chunk.getPos(), chunk.getAllStarts()));
    }

    public PathTypeCache getPathTypeCache() {
        return this.pathTypesByPosCache;
    }

    public void waitForEntities(ChunkPos chunkPos, int radius) {
        List<ChunkPos> list = ChunkPos.rangeClosed(chunkPos, radius).toList();
        this.server.managedBlock(() -> {
            this.entityManager.processPendingLoads();

            for (ChunkPos chunkPos1 : list) {
                if (!this.areEntitiesLoaded(chunkPos1.toLong())) {
                    return false;
                }
            }

            return true;
        });
    }

    public boolean isSpawningMonsters() {
        return this.getLevelData().getDifficulty() != Difficulty.PEACEFUL
            && this.getGameRules().get(GameRules.SPAWN_MOBS)
            && this.getGameRules().get(GameRules.SPAWN_MONSTERS);
    }

    @Override
    public void close() throws IOException {
        super.close();
        this.entityManager.close();
    }

    @Override
    public String gatherChunkSourceStats() {
        return "Chunks[S] W: " + this.chunkSource.gatherStats() + " E: " + this.entityManager.gatherStats();
    }

    public boolean areEntitiesLoaded(long chunkPos) {
        return this.entityManager.areEntitiesLoaded(chunkPos);
    }

    public boolean isPositionTickingWithEntitiesLoaded(long chunkPos) {
        return this.areEntitiesLoaded(chunkPos) && this.chunkSource.isPositionTicking(chunkPos);
    }

    public boolean isPositionEntityTicking(BlockPos pos) {
        return this.entityManager.canPositionTick(pos) && this.chunkSource.chunkMap.getDistanceManager().inEntityTickingRange(ChunkPos.asLong(pos));
    }

    public boolean areEntitiesActuallyLoadedAndTicking(ChunkPos chunkPos) {
        return this.entityManager.isTicking(chunkPos) && this.entityManager.areEntitiesLoaded(chunkPos.toLong());
    }

    public boolean anyPlayerCloseEnoughForSpawning(BlockPos pos) {
        return this.anyPlayerCloseEnoughForSpawning(new ChunkPos(pos));
    }

    public boolean anyPlayerCloseEnoughForSpawning(ChunkPos chunkPos) {
        return this.chunkSource.chunkMap.anyPlayerCloseEnoughForSpawning(chunkPos);
    }

    public boolean canSpreadFireAround(BlockPos pos) {
        int i = this.getGameRules().get(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER);
        return i == -1 || this.chunkSource.chunkMap.anyPlayerCloseEnoughTo(pos, i);
    }

    public boolean canSpawnEntitiesInChunk(ChunkPos chunkPos) {
        return this.entityManager.canPositionTick(chunkPos) && this.getWorldBorder().isWithinBounds(chunkPos);
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return this.server.getWorldData().enabledFeatures();
    }

    @Override
    public PotionBrewing potionBrewing() {
        return this.server.potionBrewing();
    }

    @Override
    public FuelValues fuelValues() {
        return this.server.fuelValues();
    }

    public RandomSource getRandomSequence(Identifier location) {
        return this.randomSequences.get(location, this.getSeed());
    }

    public RandomSequences getRandomSequences() {
        return this.randomSequences;
    }

    public GameRules getGameRules() {
        return this.serverLevelData.getGameRules();
    }

    @Override
    public CrashReportCategory fillReportDetails(CrashReport report) {
        CrashReportCategory crashReportCategory = super.fillReportDetails(report);
        crashReportCategory.setDetail("Loaded entity count", () -> String.valueOf(this.entityManager.count()));
        return crashReportCategory;
    }

    @Override
    public int getSeaLevel() {
        return this.chunkSource.getGenerator().getSeaLevel();
    }

    @Override
    public void onBlockEntityAdded(BlockEntity entity) {
        super.onBlockEntityAdded(entity);
        this.debugSynchronizers.registerBlockEntity(entity);
    }

    public LevelDebugSynchronizers debugSynchronizers() {
        return this.debugSynchronizers;
    }

    public boolean isAllowedToEnterPortal(Level level) {
        return level.dimension() != Level.NETHER || this.getGameRules().get(GameRules.ALLOW_ENTERING_NETHER_USING_PORTALS);
    }

    public boolean isPvpAllowed() {
        return this.getGameRules().get(GameRules.PVP);
    }

    public boolean isCommandBlockEnabled() {
        return this.getGameRules().get(GameRules.COMMAND_BLOCKS_WORK);
    }

    public boolean isSpawnerBlockEnabled() {
        return this.getGameRules().get(GameRules.SPAWNER_BLOCKS_WORK);
    }

    final class EntityCallbacks implements LevelCallback<Entity> {
        @Override
        public void onCreated(Entity entity) {
            if (entity instanceof WaypointTransmitter waypointTransmitter && waypointTransmitter.isTransmittingWaypoint()) {
                ServerLevel.this.getWaypointManager().trackWaypoint(waypointTransmitter);
            }
        }

        @Override
        public void onDestroyed(Entity entity) {
            if (entity instanceof WaypointTransmitter waypointTransmitter) {
                ServerLevel.this.getWaypointManager().untrackWaypoint(waypointTransmitter);
            }

            ServerLevel.this.getScoreboard().entityRemoved(entity);
        }

        @Override
        public void onTickingStart(Entity entity) {
            ServerLevel.this.entityTickList.add(entity);
        }

        @Override
        public void onTickingEnd(Entity entity) {
            ServerLevel.this.entityTickList.remove(entity);
        }

        @Override
        public void onTrackingStart(Entity entity) {
            ServerLevel.this.getChunkSource().addEntity(entity);
            if (entity instanceof ServerPlayer serverPlayer) {
                ServerLevel.this.players.add(serverPlayer);
                if (serverPlayer.isReceivingWaypoints()) {
                    ServerLevel.this.getWaypointManager().addPlayer(serverPlayer);
                }

                ServerLevel.this.updateSleepingPlayerList();
            }

            if (entity instanceof WaypointTransmitter waypointTransmitter && waypointTransmitter.isTransmittingWaypoint()) {
                ServerLevel.this.getWaypointManager().trackWaypoint(waypointTransmitter);
            }

            if (entity instanceof Mob mob) {
                if (ServerLevel.this.isUpdatingNavigations) {
                    String string = "onTrackingStart called during navigation iteration";
                    Util.logAndPauseIfInIde(
                        "onTrackingStart called during navigation iteration", new IllegalStateException("onTrackingStart called during navigation iteration")
                    );
                }

                ServerLevel.this.navigatingMobs.add(mob);
            }

            if (entity instanceof EnderDragon enderDragon) {
                for (EnderDragonPart enderDragonPart : enderDragon.getSubEntities()) {
                    ServerLevel.this.dragonParts.put(enderDragonPart.getId(), enderDragonPart);
                }
            }

            entity.updateDynamicGameEventListener(DynamicGameEventListener::add);
        }

        @Override
        public void onTrackingEnd(Entity entity) {
            ServerLevel.this.getChunkSource().removeEntity(entity);
            if (entity instanceof ServerPlayer serverPlayer) {
                ServerLevel.this.players.remove(serverPlayer);
                ServerLevel.this.getWaypointManager().removePlayer(serverPlayer);
                ServerLevel.this.updateSleepingPlayerList();
            }

            if (entity instanceof Mob mob) {
                if (ServerLevel.this.isUpdatingNavigations) {
                    String string = "onTrackingStart called during navigation iteration";
                    Util.logAndPauseIfInIde(
                        "onTrackingStart called during navigation iteration", new IllegalStateException("onTrackingStart called during navigation iteration")
                    );
                }

                ServerLevel.this.navigatingMobs.remove(mob);
            }

            if (entity instanceof EnderDragon enderDragon) {
                for (EnderDragonPart enderDragonPart : enderDragon.getSubEntities()) {
                    ServerLevel.this.dragonParts.remove(enderDragonPart.getId());
                }
            }

            entity.updateDynamicGameEventListener(DynamicGameEventListener::remove);
            ServerLevel.this.debugSynchronizers.dropEntity(entity);
        }

        @Override
        public void onSectionChange(Entity entity) {
            entity.updateDynamicGameEventListener(DynamicGameEventListener::move);
        }
    }
}
