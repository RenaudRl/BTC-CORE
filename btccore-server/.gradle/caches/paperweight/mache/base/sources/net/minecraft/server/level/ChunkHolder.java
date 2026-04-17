package net.minecraft.server.level;

import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jspecify.annotations.Nullable;

public class ChunkHolder extends GenerationChunkHolder {
    public static final ChunkResult<LevelChunk> UNLOADED_LEVEL_CHUNK = ChunkResult.error("Unloaded level chunk");
    private static final CompletableFuture<ChunkResult<LevelChunk>> UNLOADED_LEVEL_CHUNK_FUTURE = CompletableFuture.completedFuture(UNLOADED_LEVEL_CHUNK);
    private final LevelHeightAccessor levelHeightAccessor;
    private volatile CompletableFuture<ChunkResult<LevelChunk>> fullChunkFuture = UNLOADED_LEVEL_CHUNK_FUTURE;
    private volatile CompletableFuture<ChunkResult<LevelChunk>> tickingChunkFuture = UNLOADED_LEVEL_CHUNK_FUTURE;
    private volatile CompletableFuture<ChunkResult<LevelChunk>> entityTickingChunkFuture = UNLOADED_LEVEL_CHUNK_FUTURE;
    public int oldTicketLevel;
    private int ticketLevel;
    private int queueLevel;
    private boolean hasChangedSections;
    private final @Nullable ShortSet[] changedBlocksPerSection;
    private final BitSet blockChangedLightSectionFilter = new BitSet();
    private final BitSet skyChangedLightSectionFilter = new BitSet();
    private final LevelLightEngine lightEngine;
    private final ChunkHolder.LevelChangeListener onLevelChange;
    public final ChunkHolder.PlayerProvider playerProvider;
    private boolean wasAccessibleSinceLastSave;
    private CompletableFuture<?> pendingFullStateConfirmation = CompletableFuture.completedFuture(null);
    private CompletableFuture<?> sendSync = CompletableFuture.completedFuture(null);
    private CompletableFuture<?> saveSync = CompletableFuture.completedFuture(null);

    public ChunkHolder(
        ChunkPos pos,
        int ticketLevel,
        LevelHeightAccessor levelHeightAccessor,
        LevelLightEngine lightEngine,
        ChunkHolder.LevelChangeListener onLevelChange,
        ChunkHolder.PlayerProvider playerProvider
    ) {
        super(pos);
        this.levelHeightAccessor = levelHeightAccessor;
        this.lightEngine = lightEngine;
        this.onLevelChange = onLevelChange;
        this.playerProvider = playerProvider;
        this.oldTicketLevel = ChunkLevel.MAX_LEVEL + 1;
        this.ticketLevel = this.oldTicketLevel;
        this.queueLevel = this.oldTicketLevel;
        this.setTicketLevel(ticketLevel);
        this.changedBlocksPerSection = new ShortSet[levelHeightAccessor.getSectionsCount()];
    }

    public CompletableFuture<ChunkResult<LevelChunk>> getTickingChunkFuture() {
        return this.tickingChunkFuture;
    }

    public CompletableFuture<ChunkResult<LevelChunk>> getEntityTickingChunkFuture() {
        return this.entityTickingChunkFuture;
    }

    public CompletableFuture<ChunkResult<LevelChunk>> getFullChunkFuture() {
        return this.fullChunkFuture;
    }

    public @Nullable LevelChunk getTickingChunk() {
        return this.getTickingChunkFuture().getNow(UNLOADED_LEVEL_CHUNK).orElse(null);
    }

    public @Nullable LevelChunk getChunkToSend() {
        return !this.sendSync.isDone() ? null : this.getTickingChunk();
    }

    public CompletableFuture<?> getSendSyncFuture() {
        return this.sendSync;
    }

    public void addSendDependency(CompletableFuture<?> dependency) {
        if (this.sendSync.isDone()) {
            this.sendSync = dependency;
        } else {
            this.sendSync = this.sendSync.thenCombine((CompletionStage<? extends Object>)dependency, (object, object1) -> null);
        }
    }

    public CompletableFuture<?> getSaveSyncFuture() {
        return this.saveSync;
    }

    public boolean isReadyForSaving() {
        return this.saveSync.isDone();
    }

    @Override
    protected void addSaveDependency(CompletableFuture<?> dependency) {
        if (this.saveSync.isDone()) {
            this.saveSync = dependency;
        } else {
            this.saveSync = this.saveSync.thenCombine((CompletionStage<? extends Object>)dependency, (object, object1) -> null);
        }
    }

    public boolean blockChanged(BlockPos pos) {
        LevelChunk tickingChunk = this.getTickingChunk();
        if (tickingChunk == null) {
            return false;
        } else {
            boolean flag = this.hasChangedSections;
            int sectionIndex = this.levelHeightAccessor.getSectionIndex(pos.getY());
            ShortSet set = this.changedBlocksPerSection[sectionIndex];
            if (set == null) {
                this.hasChangedSections = true;
                set = new ShortOpenHashSet();
                this.changedBlocksPerSection[sectionIndex] = set;
            }

            set.add(SectionPos.sectionRelativePos(pos));
            return !flag;
        }
    }

    public boolean sectionLightChanged(LightLayer lightLayer, int y) {
        ChunkAccess chunkIfPresent = this.getChunkIfPresent(ChunkStatus.INITIALIZE_LIGHT);
        if (chunkIfPresent == null) {
            return false;
        } else {
            chunkIfPresent.markUnsaved();
            LevelChunk tickingChunk = this.getTickingChunk();
            if (tickingChunk == null) {
                return false;
            } else {
                int minLightSection = this.lightEngine.getMinLightSection();
                int maxLightSection = this.lightEngine.getMaxLightSection();
                if (y >= minLightSection && y <= maxLightSection) {
                    BitSet bitSet = lightLayer == LightLayer.SKY ? this.skyChangedLightSectionFilter : this.blockChangedLightSectionFilter;
                    int i = y - minLightSection;
                    if (!bitSet.get(i)) {
                        bitSet.set(i);
                        return true;
                    } else {
                        return false;
                    }
                } else {
                    return false;
                }
            }
        }
    }

    public boolean hasChangesToBroadcast() {
        return this.hasChangedSections || !this.skyChangedLightSectionFilter.isEmpty() || !this.blockChangedLightSectionFilter.isEmpty();
    }

    public void broadcastChanges(LevelChunk chunk) {
        if (this.hasChangesToBroadcast()) {
            Level level = chunk.getLevel();
            if (!this.skyChangedLightSectionFilter.isEmpty() || !this.blockChangedLightSectionFilter.isEmpty()) {
                List<ServerPlayer> players = this.playerProvider.getPlayers(this.pos, true);
                if (!players.isEmpty()) {
                    ClientboundLightUpdatePacket clientboundLightUpdatePacket = new ClientboundLightUpdatePacket(
                        chunk.getPos(), this.lightEngine, this.skyChangedLightSectionFilter, this.blockChangedLightSectionFilter
                    );
                    this.broadcast(players, clientboundLightUpdatePacket);
                }

                this.skyChangedLightSectionFilter.clear();
                this.blockChangedLightSectionFilter.clear();
            }

            if (this.hasChangedSections) {
                List<ServerPlayer> players = this.playerProvider.getPlayers(this.pos, false);

                for (int i = 0; i < this.changedBlocksPerSection.length; i++) {
                    ShortSet set = this.changedBlocksPerSection[i];
                    if (set != null) {
                        this.changedBlocksPerSection[i] = null;
                        if (!players.isEmpty()) {
                            int sectionYFromSectionIndex = this.levelHeightAccessor.getSectionYFromSectionIndex(i);
                            SectionPos sectionPos = SectionPos.of(chunk.getPos(), sectionYFromSectionIndex);
                            if (set.size() == 1) {
                                BlockPos blockPos = sectionPos.relativeToBlockPos(set.iterator().nextShort());
                                BlockState blockState = level.getBlockState(blockPos);
                                this.broadcast(players, new ClientboundBlockUpdatePacket(blockPos, blockState));
                                this.broadcastBlockEntityIfNeeded(players, level, blockPos, blockState);
                            } else {
                                LevelChunkSection section = chunk.getSection(i);
                                ClientboundSectionBlocksUpdatePacket clientboundSectionBlocksUpdatePacket = new ClientboundSectionBlocksUpdatePacket(
                                    sectionPos, set, section
                                );
                                this.broadcast(players, clientboundSectionBlocksUpdatePacket);
                                clientboundSectionBlocksUpdatePacket.runUpdates(
                                    (blockPos1, blockState1) -> this.broadcastBlockEntityIfNeeded(players, level, blockPos1, blockState1)
                                );
                            }
                        }
                    }
                }

                this.hasChangedSections = false;
            }
        }
    }

    private void broadcastBlockEntityIfNeeded(List<ServerPlayer> players, Level level, BlockPos pos, BlockState state) {
        if (state.hasBlockEntity()) {
            this.broadcastBlockEntity(players, level, pos);
        }
    }

    private void broadcastBlockEntity(List<ServerPlayer> players, Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null) {
            Packet<?> updatePacket = blockEntity.getUpdatePacket();
            if (updatePacket != null) {
                this.broadcast(players, updatePacket);
            }
        }
    }

    private void broadcast(List<ServerPlayer> players, Packet<?> packet) {
        players.forEach(player -> player.connection.send(packet));
    }

    @Override
    public int getTicketLevel() {
        return this.ticketLevel;
    }

    @Override
    public int getQueueLevel() {
        return this.queueLevel;
    }

    private void setQueueLevel(int queueLevel) {
        this.queueLevel = queueLevel;
    }

    public void setTicketLevel(int level) {
        this.ticketLevel = level;
    }

    private void scheduleFullChunkPromotion(
        ChunkMap chunkMap, CompletableFuture<ChunkResult<LevelChunk>> future, Executor executor, FullChunkStatus fullChunkStatus
    ) {
        this.pendingFullStateConfirmation.cancel(false);
        CompletableFuture<Void> completableFuture = new CompletableFuture<>();
        completableFuture.thenRunAsync(() -> chunkMap.onFullChunkStatusChange(this.pos, fullChunkStatus), executor);
        this.pendingFullStateConfirmation = completableFuture;
        future.thenAccept(chunkResult -> chunkResult.ifSuccess(levelChunk -> completableFuture.complete(null)));
    }

    private void demoteFullChunk(ChunkMap chunkMap, FullChunkStatus fullChunkStatus) {
        this.pendingFullStateConfirmation.cancel(false);
        chunkMap.onFullChunkStatusChange(this.pos, fullChunkStatus);
    }

    protected void updateFutures(ChunkMap chunkMap, Executor executor) {
        FullChunkStatus fullChunkStatus = ChunkLevel.fullStatus(this.oldTicketLevel);
        FullChunkStatus fullChunkStatus1 = ChunkLevel.fullStatus(this.ticketLevel);
        boolean isOrAfter = fullChunkStatus.isOrAfter(FullChunkStatus.FULL);
        boolean isOrAfter1 = fullChunkStatus1.isOrAfter(FullChunkStatus.FULL);
        this.wasAccessibleSinceLastSave |= isOrAfter1;
        if (!isOrAfter && isOrAfter1) {
            this.fullChunkFuture = chunkMap.prepareAccessibleChunk(this);
            this.scheduleFullChunkPromotion(chunkMap, this.fullChunkFuture, executor, FullChunkStatus.FULL);
            this.addSaveDependency(this.fullChunkFuture);
        }

        if (isOrAfter && !isOrAfter1) {
            this.fullChunkFuture.complete(UNLOADED_LEVEL_CHUNK);
            this.fullChunkFuture = UNLOADED_LEVEL_CHUNK_FUTURE;
        }

        boolean isOrAfter2 = fullChunkStatus.isOrAfter(FullChunkStatus.BLOCK_TICKING);
        boolean isOrAfter3 = fullChunkStatus1.isOrAfter(FullChunkStatus.BLOCK_TICKING);
        if (!isOrAfter2 && isOrAfter3) {
            this.tickingChunkFuture = chunkMap.prepareTickingChunk(this);
            this.scheduleFullChunkPromotion(chunkMap, this.tickingChunkFuture, executor, FullChunkStatus.BLOCK_TICKING);
            this.addSaveDependency(this.tickingChunkFuture);
        }

        if (isOrAfter2 && !isOrAfter3) {
            this.tickingChunkFuture.complete(UNLOADED_LEVEL_CHUNK);
            this.tickingChunkFuture = UNLOADED_LEVEL_CHUNK_FUTURE;
        }

        boolean isOrAfter4 = fullChunkStatus.isOrAfter(FullChunkStatus.ENTITY_TICKING);
        boolean isOrAfter5 = fullChunkStatus1.isOrAfter(FullChunkStatus.ENTITY_TICKING);
        if (!isOrAfter4 && isOrAfter5) {
            if (this.entityTickingChunkFuture != UNLOADED_LEVEL_CHUNK_FUTURE) {
                throw (IllegalStateException)Util.pauseInIde(new IllegalStateException());
            }

            this.entityTickingChunkFuture = chunkMap.prepareEntityTickingChunk(this);
            this.scheduleFullChunkPromotion(chunkMap, this.entityTickingChunkFuture, executor, FullChunkStatus.ENTITY_TICKING);
            this.addSaveDependency(this.entityTickingChunkFuture);
        }

        if (isOrAfter4 && !isOrAfter5) {
            this.entityTickingChunkFuture.complete(UNLOADED_LEVEL_CHUNK);
            this.entityTickingChunkFuture = UNLOADED_LEVEL_CHUNK_FUTURE;
        }

        if (!fullChunkStatus1.isOrAfter(fullChunkStatus)) {
            this.demoteFullChunk(chunkMap, fullChunkStatus1);
        }

        this.onLevelChange.onLevelChange(this.pos, this::getQueueLevel, this.ticketLevel, this::setQueueLevel);
        this.oldTicketLevel = this.ticketLevel;
    }

    public boolean wasAccessibleSinceLastSave() {
        return this.wasAccessibleSinceLastSave;
    }

    public void refreshAccessibility() {
        this.wasAccessibleSinceLastSave = ChunkLevel.fullStatus(this.ticketLevel).isOrAfter(FullChunkStatus.FULL);
    }

    @FunctionalInterface
    public interface LevelChangeListener {
        void onLevelChange(ChunkPos chunkPos, IntSupplier queueLevelGetter, int ticketLevel, IntConsumer queueLevelSetter);
    }

    public interface PlayerProvider {
        List<ServerPlayer> getPlayers(ChunkPos pos, boolean boundaryOnly);
    }
}
