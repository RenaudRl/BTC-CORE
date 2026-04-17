package net.minecraft.world.level.chunk;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.debug.DebugStructureInfo;
import net.minecraft.util.debug.DebugSubscriptions;
import net.minecraft.util.debug.DebugValueSource;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.gameevent.EuclideanGameEventListenerRegistry;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gameevent.GameEventListenerRegistry;
import net.minecraft.world.level.levelgen.DebugLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.TickContainerAccess;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class LevelChunk extends ChunkAccess implements DebugValueSource {
    static final Logger LOGGER = LogUtils.getLogger();
    private static final TickingBlockEntity NULL_TICKER = new TickingBlockEntity() {
        @Override
        public void tick() {
        }

        @Override
        public boolean isRemoved() {
            return true;
        }

        @Override
        public BlockPos getPos() {
            return BlockPos.ZERO;
        }

        @Override
        public String getType() {
            return "<null>";
        }
    };
    private final Map<BlockPos, LevelChunk.RebindableTickingBlockEntityWrapper> tickersInLevel = Maps.newHashMap();
    public boolean loaded;
    public final Level level;
    private @Nullable Supplier<FullChunkStatus> fullStatus;
    private LevelChunk.@Nullable PostLoadProcessor postLoad;
    private final Int2ObjectMap<GameEventListenerRegistry> gameEventListenerRegistrySections;
    private final LevelChunkTicks<Block> blockTicks;
    private final LevelChunkTicks<Fluid> fluidTicks;
    private LevelChunk.UnsavedListener unsavedListener = chunkPos -> {};

    public LevelChunk(Level level, ChunkPos pos) {
        this(level, pos, UpgradeData.EMPTY, new LevelChunkTicks<>(), new LevelChunkTicks<>(), 0L, null, null, null);
    }

    public LevelChunk(
        Level level,
        ChunkPos pos,
        UpgradeData data,
        LevelChunkTicks<Block> blockTicks,
        LevelChunkTicks<Fluid> fluidTicks,
        long inhabitedTime,
        LevelChunkSection @Nullable [] sections,
        LevelChunk.@Nullable PostLoadProcessor postLoad,
        @Nullable BlendingData blendingData
    ) {
        super(pos, data, level, level.palettedContainerFactory(), inhabitedTime, sections, blendingData);
        this.level = level;
        this.gameEventListenerRegistrySections = new Int2ObjectOpenHashMap<>();

        for (Heightmap.Types types : Heightmap.Types.values()) {
            if (ChunkStatus.FULL.heightmapsAfter().contains(types)) {
                this.heightmaps.put(types, new Heightmap(this, types));
            }
        }

        this.postLoad = postLoad;
        this.blockTicks = blockTicks;
        this.fluidTicks = fluidTicks;
    }

    public LevelChunk(ServerLevel level, ProtoChunk chunk, LevelChunk.@Nullable PostLoadProcessor postLoad) {
        this(
            level,
            chunk.getPos(),
            chunk.getUpgradeData(),
            chunk.unpackBlockTicks(),
            chunk.unpackFluidTicks(),
            chunk.getInhabitedTime(),
            chunk.getSections(),
            postLoad,
            chunk.getBlendingData()
        );
        if (!Collections.disjoint(chunk.pendingBlockEntities.keySet(), chunk.blockEntities.keySet())) {
            LOGGER.error("Chunk at {} contains duplicated block entities", chunk.getPos());
        }

        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            this.setBlockEntity(blockEntity);
        }

        this.pendingBlockEntities.putAll(chunk.getBlockEntityNbts());

        for (int i = 0; i < chunk.getPostProcessing().length; i++) {
            this.postProcessing[i] = chunk.getPostProcessing()[i];
        }

        this.setAllStarts(chunk.getAllStarts());
        this.setAllReferences(chunk.getAllReferences());

        for (Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
            if (ChunkStatus.FULL.heightmapsAfter().contains(entry.getKey())) {
                this.setHeightmap(entry.getKey(), entry.getValue().getRawData());
            }
        }

        this.skyLightSources = chunk.skyLightSources;
        this.setLightCorrect(chunk.isLightCorrect());
        this.markUnsaved();
    }

    public void setUnsavedListener(LevelChunk.UnsavedListener unsavedListener) {
        this.unsavedListener = unsavedListener;
        if (this.isUnsaved()) {
            unsavedListener.setUnsaved(this.chunkPos);
        }
    }

    @Override
    public void markUnsaved() {
        boolean isUnsaved = this.isUnsaved();
        super.markUnsaved();
        if (!isUnsaved) {
            this.unsavedListener.setUnsaved(this.chunkPos);
        }
    }

    @Override
    public TickContainerAccess<Block> getBlockTicks() {
        return this.blockTicks;
    }

    @Override
    public TickContainerAccess<Fluid> getFluidTicks() {
        return this.fluidTicks;
    }

    @Override
    public ChunkAccess.PackedTicks getTicksForSerialization(long gameTime) {
        return new ChunkAccess.PackedTicks(this.blockTicks.pack(gameTime), this.fluidTicks.pack(gameTime));
    }

    @Override
    public GameEventListenerRegistry getListenerRegistry(int sectionY) {
        return this.level instanceof ServerLevel serverLevel
            ? this.gameEventListenerRegistrySections
                .computeIfAbsent(sectionY, i -> new EuclideanGameEventListenerRegistry(serverLevel, sectionY, this::removeGameEventListenerRegistry))
            : super.getListenerRegistry(sectionY);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        if (this.level.isDebug()) {
            BlockState blockState = null;
            if (y == 60) {
                blockState = Blocks.BARRIER.defaultBlockState();
            }

            if (y == 70) {
                blockState = DebugLevelSource.getBlockStateFor(x, z);
            }

            return blockState == null ? Blocks.AIR.defaultBlockState() : blockState;
        } else {
            try {
                int sectionIndex = this.getSectionIndex(y);
                if (sectionIndex >= 0 && sectionIndex < this.sections.length) {
                    LevelChunkSection levelChunkSection = this.sections[sectionIndex];
                    if (!levelChunkSection.hasOnlyAir()) {
                        return levelChunkSection.getBlockState(x & 15, y & 15, z & 15);
                    }
                }

                return Blocks.AIR.defaultBlockState();
            } catch (Throwable var8) {
                CrashReport crashReport = CrashReport.forThrowable(var8, "Getting block state");
                CrashReportCategory crashReportCategory = crashReport.addCategory("Block being got");
                crashReportCategory.setDetail("Location", () -> CrashReportCategory.formatLocation(this, x, y, z));
                throw new ReportedException(crashReport);
            }
        }
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return this.getFluidState(pos.getX(), pos.getY(), pos.getZ());
    }

    public FluidState getFluidState(int x, int y, int z) {
        try {
            int sectionIndex = this.getSectionIndex(y);
            if (sectionIndex >= 0 && sectionIndex < this.sections.length) {
                LevelChunkSection levelChunkSection = this.sections[sectionIndex];
                if (!levelChunkSection.hasOnlyAir()) {
                    return levelChunkSection.getFluidState(x & 15, y & 15, z & 15);
                }
            }

            return Fluids.EMPTY.defaultFluidState();
        } catch (Throwable var7) {
            CrashReport crashReport = CrashReport.forThrowable(var7, "Getting fluid state");
            CrashReportCategory crashReportCategory = crashReport.addCategory("Block being got");
            crashReportCategory.setDetail("Location", () -> CrashReportCategory.formatLocation(this, x, y, z));
            throw new ReportedException(crashReport);
        }
    }

    @Override
    public @Nullable BlockState setBlockState(BlockPos pos, BlockState state, @Block.UpdateFlags int flags) {
        int y = pos.getY();
        LevelChunkSection section = this.getSection(this.getSectionIndex(y));
        boolean hasOnlyAir = section.hasOnlyAir();
        if (hasOnlyAir && state.isAir()) {
            return null;
        } else {
            int i = pos.getX() & 15;
            int i1 = y & 15;
            int i2 = pos.getZ() & 15;
            BlockState blockState = section.setBlockState(i, i1, i2, state);
            if (blockState == state) {
                return null;
            } else {
                Block block = state.getBlock();
                this.heightmaps.get(Heightmap.Types.MOTION_BLOCKING).update(i, y, i2, state);
                this.heightmaps.get(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES).update(i, y, i2, state);
                this.heightmaps.get(Heightmap.Types.OCEAN_FLOOR).update(i, y, i2, state);
                this.heightmaps.get(Heightmap.Types.WORLD_SURFACE).update(i, y, i2, state);
                boolean hasOnlyAir1 = section.hasOnlyAir();
                if (hasOnlyAir != hasOnlyAir1) {
                    this.level.getChunkSource().getLightEngine().updateSectionStatus(pos, hasOnlyAir1);
                    this.level.getChunkSource().onSectionEmptinessChanged(this.chunkPos.x, SectionPos.blockToSectionCoord(y), this.chunkPos.z, hasOnlyAir1);
                }

                if (LightEngine.hasDifferentLightProperties(blockState, state)) {
                    ProfilerFiller profilerFiller = Profiler.get();
                    profilerFiller.push("updateSkyLightSources");
                    this.skyLightSources.update(this, i, y, i2);
                    profilerFiller.popPush("queueCheckLight");
                    this.level.getChunkSource().getLightEngine().checkBlock(pos);
                    profilerFiller.pop();
                }

                boolean flag = !blockState.is(block);
                boolean flag1 = (flags & Block.UPDATE_MOVE_BY_PISTON) != 0;
                boolean flag2 = (flags & Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS) == 0;
                if (flag && blockState.hasBlockEntity() && !state.shouldChangedStateKeepBlockEntity(blockState)) {
                    if (!this.level.isClientSide() && flag2) {
                        BlockEntity blockEntity = this.level.getBlockEntity(pos);
                        if (blockEntity != null) {
                            blockEntity.preRemoveSideEffects(pos, blockState);
                        }
                    }

                    this.removeBlockEntity(pos);
                }

                if ((flag || block instanceof BaseRailBlock)
                    && this.level instanceof ServerLevel serverLevel
                    && ((flags & Block.UPDATE_NEIGHBORS) != 0 || flag1)) {
                    blockState.affectNeighborsAfterRemoval(serverLevel, pos, flag1);
                }

                if (!section.getBlockState(i, i1, i2).is(block)) {
                    return null;
                } else {
                    if (!this.level.isClientSide() && (flags & Block.UPDATE_SKIP_ON_PLACE) == 0) {
                        state.onPlace(this.level, pos, blockState, flag1);
                    }

                    if (state.hasBlockEntity()) {
                        BlockEntity blockEntity = this.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
                        if (blockEntity != null && !blockEntity.isValidBlockState(state)) {
                            LOGGER.warn(
                                "Found mismatched block entity @ {}: type = {}, state = {}",
                                pos,
                                blockEntity.getType().builtInRegistryHolder().key().identifier(),
                                state
                            );
                            this.removeBlockEntity(pos);
                            blockEntity = null;
                        }

                        if (blockEntity == null) {
                            blockEntity = ((EntityBlock)block).newBlockEntity(pos, state);
                            if (blockEntity != null) {
                                this.addAndRegisterBlockEntity(blockEntity);
                            }
                        } else {
                            blockEntity.setBlockState(state);
                            this.updateBlockEntityTicker(blockEntity);
                        }
                    }

                    this.markUnsaved();
                    return blockState;
                }
            }
        }
    }

    @Deprecated
    @Override
    public void addEntity(Entity entity) {
    }

    private @Nullable BlockEntity createBlockEntity(BlockPos pos) {
        BlockState blockState = this.getBlockState(pos);
        return !blockState.hasBlockEntity() ? null : ((EntityBlock)blockState.getBlock()).newBlockEntity(pos, blockState);
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return this.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
    }

    public @Nullable BlockEntity getBlockEntity(BlockPos pos, LevelChunk.EntityCreationType creationType) {
        BlockEntity blockEntity = this.blockEntities.get(pos);
        if (blockEntity == null) {
            CompoundTag compoundTag = this.pendingBlockEntities.remove(pos);
            if (compoundTag != null) {
                BlockEntity blockEntity1 = this.promotePendingBlockEntity(pos, compoundTag);
                if (blockEntity1 != null) {
                    return blockEntity1;
                }
            }
        }

        if (blockEntity == null) {
            if (creationType == LevelChunk.EntityCreationType.IMMEDIATE) {
                blockEntity = this.createBlockEntity(pos);
                if (blockEntity != null) {
                    this.addAndRegisterBlockEntity(blockEntity);
                }
            }
        } else if (blockEntity.isRemoved()) {
            this.blockEntities.remove(pos);
            return null;
        }

        return blockEntity;
    }

    public void addAndRegisterBlockEntity(BlockEntity blockEntity) {
        this.setBlockEntity(blockEntity);
        if (this.isInLevel()) {
            if (this.level instanceof ServerLevel serverLevel) {
                this.addGameEventListener(blockEntity, serverLevel);
            }

            this.level.onBlockEntityAdded(blockEntity);
            this.updateBlockEntityTicker(blockEntity);
        }
    }

    private boolean isInLevel() {
        return this.loaded || this.level.isClientSide();
    }

    boolean isTicking(BlockPos pos) {
        return this.level.getWorldBorder().isWithinBounds(pos)
            && (
                !(this.level instanceof ServerLevel serverLevel)
                    || this.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING) && serverLevel.areEntitiesLoaded(ChunkPos.asLong(pos))
            );
    }

    @Override
    public void setBlockEntity(BlockEntity blockEntity) {
        BlockPos blockPos = blockEntity.getBlockPos();
        BlockState blockState = this.getBlockState(blockPos);
        if (!blockState.hasBlockEntity()) {
            LOGGER.warn("Trying to set block entity {} at position {}, but state {} does not allow it", blockEntity, blockPos, blockState);
        } else {
            BlockState blockState1 = blockEntity.getBlockState();
            if (blockState != blockState1) {
                if (!blockEntity.getType().isValid(blockState)) {
                    LOGGER.warn("Trying to set block entity {} at position {}, but state {} does not allow it", blockEntity, blockPos, blockState);
                    return;
                }

                if (blockState.getBlock() != blockState1.getBlock()) {
                    LOGGER.warn("Block state mismatch on block entity {} in position {}, {} != {}, updating", blockEntity, blockPos, blockState, blockState1);
                }

                blockEntity.setBlockState(blockState);
            }

            blockEntity.setLevel(this.level);
            blockEntity.clearRemoved();
            BlockEntity blockEntity1 = this.blockEntities.put(blockPos.immutable(), blockEntity);
            if (blockEntity1 != null && blockEntity1 != blockEntity) {
                blockEntity1.setRemoved();
            }
        }
    }

    @Override
    public @Nullable CompoundTag getBlockEntityNbtForSaving(BlockPos pos, HolderLookup.Provider registries) {
        BlockEntity blockEntity = this.getBlockEntity(pos);
        if (blockEntity != null && !blockEntity.isRemoved()) {
            CompoundTag compoundTag = blockEntity.saveWithFullMetadata(this.level.registryAccess());
            compoundTag.putBoolean("keepPacked", false);
            return compoundTag;
        } else {
            CompoundTag compoundTag = this.pendingBlockEntities.get(pos);
            if (compoundTag != null) {
                compoundTag = compoundTag.copy();
                compoundTag.putBoolean("keepPacked", true);
            }

            return compoundTag;
        }
    }

    @Override
    public void removeBlockEntity(BlockPos pos) {
        if (this.isInLevel()) {
            BlockEntity blockEntity = this.blockEntities.remove(pos);
            if (blockEntity != null) {
                if (this.level instanceof ServerLevel serverLevel) {
                    this.removeGameEventListener(blockEntity, serverLevel);
                    serverLevel.debugSynchronizers().dropBlockEntity(pos);
                }

                blockEntity.setRemoved();
            }
        }

        this.removeBlockEntityTicker(pos);
    }

    private <T extends BlockEntity> void removeGameEventListener(T blockEntity, ServerLevel level) {
        Block block = blockEntity.getBlockState().getBlock();
        if (block instanceof EntityBlock) {
            GameEventListener listener = ((EntityBlock)block).getListener(level, blockEntity);
            if (listener != null) {
                int sectionPosY = SectionPos.blockToSectionCoord(blockEntity.getBlockPos().getY());
                GameEventListenerRegistry listenerRegistry = this.getListenerRegistry(sectionPosY);
                listenerRegistry.unregister(listener);
            }
        }
    }

    private void removeGameEventListenerRegistry(int sectionY) {
        this.gameEventListenerRegistrySections.remove(sectionY);
    }

    private void removeBlockEntityTicker(BlockPos pos) {
        LevelChunk.RebindableTickingBlockEntityWrapper rebindableTickingBlockEntityWrapper = this.tickersInLevel.remove(pos);
        if (rebindableTickingBlockEntityWrapper != null) {
            rebindableTickingBlockEntityWrapper.rebind(NULL_TICKER);
        }
    }

    public void runPostLoad() {
        if (this.postLoad != null) {
            this.postLoad.run(this);
            this.postLoad = null;
        }
    }

    public boolean isEmpty() {
        return false;
    }

    public void replaceWithPacketData(
        FriendlyByteBuf buffer, Map<Heightmap.Types, long[]> heightmaps, Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> outputTagConsumer
    ) {
        this.clearAllBlockEntities();

        for (LevelChunkSection levelChunkSection : this.sections) {
            levelChunkSection.read(buffer);
        }

        heightmaps.forEach(this::setHeightmap);
        this.initializeLightSources();

        try (ProblemReporter.ScopedCollector scopedCollector = new ProblemReporter.ScopedCollector(this.problemPath(), LOGGER)) {
            outputTagConsumer.accept((pos, type, tag) -> {
                BlockEntity blockEntity = this.getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE);
                if (blockEntity != null && tag != null && blockEntity.getType() == type) {
                    blockEntity.loadWithComponents(TagValueInput.create(scopedCollector.forChild(blockEntity.problemPath()), this.level.registryAccess(), tag));
                }
            });
        }
    }

    public void replaceBiomes(FriendlyByteBuf buffer) {
        for (LevelChunkSection levelChunkSection : this.sections) {
            levelChunkSection.readBiomes(buffer);
        }
    }

    public void setLoaded(boolean loaded) {
        this.loaded = loaded;
    }

    public Level getLevel() {
        return this.level;
    }

    public Map<BlockPos, BlockEntity> getBlockEntities() {
        return this.blockEntities;
    }

    public void postProcessGeneration(ServerLevel level) {
        ChunkPos pos = this.getPos();

        for (int i = 0; i < this.postProcessing.length; i++) {
            ShortList list = this.postProcessing[i];
            if (list != null) {
                for (Short _short : list) {
                    BlockPos blockPos = ProtoChunk.unpackOffsetCoordinates(_short, this.getSectionYFromSectionIndex(i), pos);
                    BlockState blockState = this.getBlockState(blockPos);
                    FluidState fluidState = blockState.getFluidState();
                    if (!fluidState.isEmpty()) {
                        fluidState.tick(level, blockPos, blockState);
                    }

                    if (!(blockState.getBlock() instanceof LiquidBlock)) {
                        BlockState blockState1 = Block.updateFromNeighbourShapes(blockState, level, blockPos);
                        if (blockState1 != blockState) {
                            level.setBlock(blockPos, blockState1, Block.UPDATE_NONE | Block.UPDATE_KNOWN_SHAPE);
                        }
                    }
                }

                list.clear();
            }
        }

        for (BlockPos blockPos1 : ImmutableList.copyOf(this.pendingBlockEntities.keySet())) {
            this.getBlockEntity(blockPos1);
        }

        this.pendingBlockEntities.clear();
        this.upgradeData.upgrade(this);
    }

    private @Nullable BlockEntity promotePendingBlockEntity(BlockPos pos, CompoundTag tag) {
        BlockState blockState = this.getBlockState(pos);
        BlockEntity blockEntity;
        if ("DUMMY".equals(tag.getStringOr("id", ""))) {
            if (blockState.hasBlockEntity()) {
                blockEntity = ((EntityBlock)blockState.getBlock()).newBlockEntity(pos, blockState);
            } else {
                blockEntity = null;
                LOGGER.warn("Tried to load a DUMMY block entity @ {} but found not block entity block {} at location", pos, blockState);
            }
        } else {
            blockEntity = BlockEntity.loadStatic(pos, blockState, tag, this.level.registryAccess());
        }

        if (blockEntity != null) {
            blockEntity.setLevel(this.level);
            this.addAndRegisterBlockEntity(blockEntity);
        } else {
            LOGGER.warn("Tried to load a block entity for block {} but failed at location {}", blockState, pos);
        }

        return blockEntity;
    }

    public void unpackTicks(long pos) {
        this.blockTicks.unpack(pos);
        this.fluidTicks.unpack(pos);
    }

    public void registerTickContainerInLevel(ServerLevel level) {
        level.getBlockTicks().addContainer(this.chunkPos, this.blockTicks);
        level.getFluidTicks().addContainer(this.chunkPos, this.fluidTicks);
    }

    public void unregisterTickContainerFromLevel(ServerLevel level) {
        level.getBlockTicks().removeContainer(this.chunkPos);
        level.getFluidTicks().removeContainer(this.chunkPos);
    }

    @Override
    public void registerDebugValues(ServerLevel level, DebugValueSource.Registration registrar) {
        if (!this.getAllStarts().isEmpty()) {
            registrar.register(DebugSubscriptions.STRUCTURES, () -> {
                List<DebugStructureInfo> list = new ArrayList<>();

                for (StructureStart structureStart : this.getAllStarts().values()) {
                    BoundingBox boundingBox = structureStart.getBoundingBox();
                    List<StructurePiece> pieces = structureStart.getPieces();
                    List<DebugStructureInfo.Piece> list1 = new ArrayList<>(pieces.size());

                    for (int i = 0; i < pieces.size(); i++) {
                        boolean flag = i == 0;
                        list1.add(new DebugStructureInfo.Piece(pieces.get(i).getBoundingBox(), flag));
                    }

                    list.add(new DebugStructureInfo(boundingBox, list1));
                }

                return list;
            });
        }

        registrar.register(DebugSubscriptions.RAIDS, () -> level.getRaids().getRaidCentersInChunk(this.chunkPos));
    }

    @Override
    public ChunkStatus getPersistedStatus() {
        return ChunkStatus.FULL;
    }

    public FullChunkStatus getFullStatus() {
        return this.fullStatus == null ? FullChunkStatus.FULL : this.fullStatus.get();
    }

    public void setFullStatus(Supplier<FullChunkStatus> fullStatus) {
        this.fullStatus = fullStatus;
    }

    public void clearAllBlockEntities() {
        this.blockEntities.values().forEach(BlockEntity::setRemoved);
        this.blockEntities.clear();
        this.tickersInLevel.values().forEach(ticker -> ticker.rebind(NULL_TICKER));
        this.tickersInLevel.clear();
    }

    public void registerAllBlockEntitiesAfterLevelLoad() {
        this.blockEntities.values().forEach(blockEntity -> {
            if (this.level instanceof ServerLevel serverLevel) {
                this.addGameEventListener(blockEntity, serverLevel);
            }

            this.level.onBlockEntityAdded(blockEntity);
            this.updateBlockEntityTicker(blockEntity);
        });
    }

    private <T extends BlockEntity> void addGameEventListener(T blockEntity, ServerLevel level) {
        Block block = blockEntity.getBlockState().getBlock();
        if (block instanceof EntityBlock) {
            GameEventListener listener = ((EntityBlock)block).getListener(level, blockEntity);
            if (listener != null) {
                this.getListenerRegistry(SectionPos.blockToSectionCoord(blockEntity.getBlockPos().getY())).register(listener);
            }
        }
    }

    private <T extends BlockEntity> void updateBlockEntityTicker(T blockEntity) {
        BlockState blockState = blockEntity.getBlockState();
        BlockEntityTicker<T> ticker = blockState.getTicker(this.level, (BlockEntityType<T>)blockEntity.getType());
        if (ticker == null) {
            this.removeBlockEntityTicker(blockEntity.getBlockPos());
        } else {
            this.tickersInLevel
                .compute(
                    blockEntity.getBlockPos(),
                    (pos, ticker1) -> {
                        TickingBlockEntity tickingBlockEntity = this.createTicker(blockEntity, ticker);
                        if (ticker1 != null) {
                            ticker1.rebind(tickingBlockEntity);
                            return (LevelChunk.RebindableTickingBlockEntityWrapper)ticker1;
                        } else if (this.isInLevel()) {
                            LevelChunk.RebindableTickingBlockEntityWrapper rebindableTickingBlockEntityWrapper = new LevelChunk.RebindableTickingBlockEntityWrapper(
                                tickingBlockEntity
                            );
                            this.level.addBlockEntityTicker(rebindableTickingBlockEntityWrapper);
                            return rebindableTickingBlockEntityWrapper;
                        } else {
                            return null;
                        }
                    }
                );
        }
    }

    private <T extends BlockEntity> TickingBlockEntity createTicker(T blockEntity, BlockEntityTicker<T> ticker) {
        return new LevelChunk.BoundTickingBlockEntity<>(blockEntity, ticker);
    }

    class BoundTickingBlockEntity<T extends BlockEntity> implements TickingBlockEntity {
        private final T blockEntity;
        private final BlockEntityTicker<T> ticker;
        private boolean loggedInvalidBlockState;

        BoundTickingBlockEntity(final T blockEntity, final BlockEntityTicker<T> ticker) {
            this.blockEntity = blockEntity;
            this.ticker = ticker;
        }

        @Override
        public void tick() {
            if (!this.blockEntity.isRemoved() && this.blockEntity.hasLevel()) {
                BlockPos blockPos = this.blockEntity.getBlockPos();
                if (LevelChunk.this.isTicking(blockPos)) {
                    try {
                        ProfilerFiller profilerFiller = Profiler.get();
                        profilerFiller.push(this::getType);
                        BlockState blockState = LevelChunk.this.getBlockState(blockPos);
                        if (this.blockEntity.getType().isValid(blockState)) {
                            this.ticker.tick(LevelChunk.this.level, this.blockEntity.getBlockPos(), blockState, this.blockEntity);
                            this.loggedInvalidBlockState = false;
                        } else if (!this.loggedInvalidBlockState) {
                            this.loggedInvalidBlockState = true;
                            LevelChunk.LOGGER
                                .warn(
                                    "Block entity {} @ {} state {} invalid for ticking:",
                                    LogUtils.defer(this::getType),
                                    LogUtils.defer(this::getPos),
                                    blockState
                                );
                        }

                        profilerFiller.pop();
                    } catch (Throwable var5) {
                        CrashReport crashReport = CrashReport.forThrowable(var5, "Ticking block entity");
                        CrashReportCategory crashReportCategory = crashReport.addCategory("Block entity being ticked");
                        this.blockEntity.fillCrashReportCategory(crashReportCategory);
                        throw new ReportedException(crashReport);
                    }
                }
            }
        }

        @Override
        public boolean isRemoved() {
            return this.blockEntity.isRemoved();
        }

        @Override
        public BlockPos getPos() {
            return this.blockEntity.getBlockPos();
        }

        @Override
        public String getType() {
            return BlockEntityType.getKey(this.blockEntity.getType()).toString();
        }

        @Override
        public String toString() {
            return "Level ticker for " + this.getType() + "@" + this.getPos();
        }
    }

    public static enum EntityCreationType {
        IMMEDIATE,
        QUEUED,
        CHECK;
    }

    @FunctionalInterface
    public interface PostLoadProcessor {
        void run(LevelChunk chunk);
    }

    static class RebindableTickingBlockEntityWrapper implements TickingBlockEntity {
        private TickingBlockEntity ticker;

        RebindableTickingBlockEntityWrapper(TickingBlockEntity ticker) {
            this.ticker = ticker;
        }

        void rebind(TickingBlockEntity ticker) {
            this.ticker = ticker;
        }

        @Override
        public void tick() {
            this.ticker.tick();
        }

        @Override
        public boolean isRemoved() {
            return this.ticker.isRemoved();
        }

        @Override
        public BlockPos getPos() {
            return this.ticker.getPos();
        }

        @Override
        public String getType() {
            return this.ticker.getType();
        }

        @Override
        public String toString() {
            return this.ticker + " <wrapped>";
        }
    }

    @FunctionalInterface
    public interface UnsavedListener {
        void setUnsaved(ChunkPos chunkPos);
    }
}
