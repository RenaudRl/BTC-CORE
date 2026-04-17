package net.minecraft.world.entity.ai.village.poi;

import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.SectionTracker;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.util.VisibleForDebug;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.debug.DebugPoiInfo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkIOErrorReporter;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.jspecify.annotations.Nullable;

public class PoiManager extends SectionStorage<PoiSection, PoiSection.Packed> {
    public static final int MAX_VILLAGE_DISTANCE = 6;
    public static final int VILLAGE_SECTION_SIZE = 1;
    private final PoiManager.DistanceTracker distanceTracker;
    private final LongSet loadedChunks = new LongOpenHashSet();

    public PoiManager(
        RegionStorageInfo info,
        Path folder,
        DataFixer fixerUpper,
        boolean sync,
        RegistryAccess registryAccess,
        ChunkIOErrorReporter errorReporter,
        LevelHeightAccessor levelHeightAccessor
    ) {
        super(
            new SimpleRegionStorage(info, folder, fixerUpper, sync, DataFixTypes.POI_CHUNK),
            PoiSection.Packed.CODEC,
            PoiSection::pack,
            PoiSection.Packed::unpack,
            PoiSection::new,
            registryAccess,
            errorReporter,
            levelHeightAccessor
        );
        this.distanceTracker = new PoiManager.DistanceTracker();
    }

    public @Nullable PoiRecord add(BlockPos pos, Holder<PoiType> type) {
        return this.getOrCreate(SectionPos.asLong(pos)).add(pos, type);
    }

    public void remove(BlockPos pos) {
        this.getOrLoad(SectionPos.asLong(pos)).ifPresent(section -> section.remove(pos));
    }

    public long getCountInRange(Predicate<Holder<PoiType>> typePredicate, BlockPos pos, int distance, PoiManager.Occupancy status) {
        return this.getInRange(typePredicate, pos, distance, status).count();
    }

    public boolean existsAtPosition(ResourceKey<PoiType> poiType, BlockPos pos) {
        return this.exists(pos, holder -> holder.is(poiType));
    }

    public Stream<PoiRecord> getInSquare(Predicate<Holder<PoiType>> typePredicate, BlockPos pos, int distance, PoiManager.Occupancy status) {
        int i = Math.floorDiv(distance, 16) + 1;
        return ChunkPos.rangeClosed(new ChunkPos(pos), i).flatMap(chunkPos -> this.getInChunk(typePredicate, chunkPos, status)).filter(poiRecord -> {
            BlockPos pos1 = poiRecord.getPos();
            return Math.abs(pos1.getX() - pos.getX()) <= distance && Math.abs(pos1.getZ() - pos.getZ()) <= distance;
        });
    }

    public Stream<PoiRecord> getInRange(Predicate<Holder<PoiType>> typePredicate, BlockPos pos, int distance, PoiManager.Occupancy status) {
        int i = distance * distance;
        return this.getInSquare(typePredicate, pos, distance, status).filter(poiRecord -> poiRecord.getPos().distSqr(pos) <= i);
    }

    @VisibleForDebug
    public Stream<PoiRecord> getInChunk(Predicate<Holder<PoiType>> typePredicate, ChunkPos posChunk, PoiManager.Occupancy status) {
        return IntStream.rangeClosed(this.levelHeightAccessor.getMinSectionY(), this.levelHeightAccessor.getMaxSectionY())
            .boxed()
            .map(integer -> this.getOrLoad(SectionPos.of(posChunk, integer).asLong()))
            .filter(Optional::isPresent)
            .flatMap(optional -> optional.get().getRecords(typePredicate, status));
    }

    public Stream<BlockPos> findAll(
        Predicate<Holder<PoiType>> typePredicate, Predicate<BlockPos> posPredicate, BlockPos pos, int distance, PoiManager.Occupancy status
    ) {
        return this.getInRange(typePredicate, pos, distance, status).map(PoiRecord::getPos).filter(posPredicate);
    }

    public Stream<Pair<Holder<PoiType>, BlockPos>> findAllWithType(
        Predicate<Holder<PoiType>> typePredicate, Predicate<BlockPos> posPredicate, BlockPos pos, int distance, PoiManager.Occupancy status
    ) {
        return this.getInRange(typePredicate, pos, distance, status)
            .filter(poiRecord -> posPredicate.test(poiRecord.getPos()))
            .map(poiRecord -> Pair.of(poiRecord.getPoiType(), poiRecord.getPos()));
    }

    public Stream<Pair<Holder<PoiType>, BlockPos>> findAllClosestFirstWithType(
        Predicate<Holder<PoiType>> typePredicate, Predicate<BlockPos> posPredicate, BlockPos pos, int distance, PoiManager.Occupancy status
    ) {
        return this.findAllWithType(typePredicate, posPredicate, pos, distance, status)
            .sorted(Comparator.comparingDouble(pair -> pair.getSecond().distSqr(pos)));
    }

    public Optional<BlockPos> find(
        Predicate<Holder<PoiType>> typePredicate, Predicate<BlockPos> posPredicate, BlockPos pos, int distance, PoiManager.Occupancy status
    ) {
        return this.findAll(typePredicate, posPredicate, pos, distance, status).findFirst();
    }

    public Optional<BlockPos> findClosest(Predicate<Holder<PoiType>> typePredicate, BlockPos pos, int distance, PoiManager.Occupancy status) {
        return this.getInRange(typePredicate, pos, distance, status).map(PoiRecord::getPos).min(Comparator.comparingDouble(blockPos -> blockPos.distSqr(pos)));
    }

    public Optional<Pair<Holder<PoiType>, BlockPos>> findClosestWithType(
        Predicate<Holder<PoiType>> typePredicate, BlockPos pos, int distance, PoiManager.Occupancy status
    ) {
        return this.getInRange(typePredicate, pos, distance, status)
            .min(Comparator.comparingDouble(poiRecord -> poiRecord.getPos().distSqr(pos)))
            .map(poiRecord -> Pair.of(poiRecord.getPoiType(), poiRecord.getPos()));
    }

    public Optional<BlockPos> findClosest(
        Predicate<Holder<PoiType>> typePredicate, Predicate<BlockPos> posPredicate, BlockPos pos, int distance, PoiManager.Occupancy status
    ) {
        return this.getInRange(typePredicate, pos, distance, status)
            .map(PoiRecord::getPos)
            .filter(posPredicate)
            .min(Comparator.comparingDouble(blockPos -> blockPos.distSqr(pos)));
    }

    public Optional<BlockPos> take(
        Predicate<Holder<PoiType>> typePredicate, BiPredicate<Holder<PoiType>, BlockPos> combinedTypePosPredicate, BlockPos pos, int distance
    ) {
        return this.getInRange(typePredicate, pos, distance, PoiManager.Occupancy.HAS_SPACE)
            .filter(poiRecord -> combinedTypePosPredicate.test(poiRecord.getPoiType(), poiRecord.getPos()))
            .findFirst()
            .map(poiRecord -> {
                poiRecord.acquireTicket();
                return poiRecord.getPos();
            });
    }

    public Optional<BlockPos> getRandom(
        Predicate<Holder<PoiType>> typePredicate,
        Predicate<BlockPos> posPredicate,
        PoiManager.Occupancy status,
        BlockPos pos,
        int distance,
        RandomSource random
    ) {
        List<PoiRecord> list = Util.toShuffledList(this.getInRange(typePredicate, pos, distance, status), random);
        return list.stream().filter(poiRecord -> posPredicate.test(poiRecord.getPos())).findFirst().map(PoiRecord::getPos);
    }

    public boolean release(BlockPos pos) {
        return this.getOrLoad(SectionPos.asLong(pos))
            .map(poiSection -> poiSection.release(pos))
            .orElseThrow(() -> Util.pauseInIde(new IllegalStateException("POI never registered at " + pos)));
    }

    public boolean exists(BlockPos pos, Predicate<Holder<PoiType>> typePredicate) {
        return this.getOrLoad(SectionPos.asLong(pos)).map(poiSection -> poiSection.exists(pos, typePredicate)).orElse(false);
    }

    public Optional<Holder<PoiType>> getType(BlockPos pos) {
        return this.getOrLoad(SectionPos.asLong(pos)).flatMap(poiSection -> poiSection.getType(pos));
    }

    @VisibleForDebug
    public @Nullable DebugPoiInfo getDebugPoiInfo(BlockPos pos) {
        return this.getOrLoad(SectionPos.asLong(pos)).flatMap(poiSection -> poiSection.getDebugPoiInfo(pos)).orElse(null);
    }

    public int sectionsToVillage(SectionPos sectionPos) {
        this.distanceTracker.runAllUpdates();
        return this.distanceTracker.getLevel(sectionPos.asLong());
    }

    boolean isVillageCenter(long chunkPos) {
        Optional<PoiSection> optional = this.get(chunkPos);
        return optional != null
            && optional.<Boolean>map(
                    poiSection -> poiSection.getRecords(holder -> holder.is(PoiTypeTags.VILLAGE), PoiManager.Occupancy.IS_OCCUPIED).findAny().isPresent()
                )
                .orElse(false);
    }

    @Override
    public void tick(BooleanSupplier aheadOfTime) {
        super.tick(aheadOfTime);
        this.distanceTracker.runAllUpdates();
    }

    @Override
    protected void setDirty(long sectionPos) {
        super.setDirty(sectionPos);
        this.distanceTracker.update(sectionPos, this.distanceTracker.getLevelFromSource(sectionPos), false);
    }

    @Override
    protected void onSectionLoad(long sectionKey) {
        this.distanceTracker.update(sectionKey, this.distanceTracker.getLevelFromSource(sectionKey), false);
    }

    public void checkConsistencyWithBlocks(SectionPos sectionPos, LevelChunkSection levelChunkSection) {
        Util.ifElse(this.getOrLoad(sectionPos.asLong()), poiSection -> poiSection.refresh(biConsumer -> {
            if (mayHavePoi(levelChunkSection)) {
                this.updateFromSection(levelChunkSection, sectionPos, biConsumer);
            }
        }), () -> {
            if (mayHavePoi(levelChunkSection)) {
                PoiSection poiSection = this.getOrCreate(sectionPos.asLong());
                this.updateFromSection(levelChunkSection, sectionPos, poiSection::add);
            }
        });
    }

    private static boolean mayHavePoi(LevelChunkSection section) {
        return section.maybeHas(PoiTypes::hasPoi);
    }

    private void updateFromSection(LevelChunkSection section, SectionPos sectionPos, BiConsumer<BlockPos, Holder<PoiType>> posToTypeConsumer) {
        sectionPos.blocksInside()
            .forEach(
                blockPos -> {
                    BlockState blockState = section.getBlockState(
                        SectionPos.sectionRelative(blockPos.getX()), SectionPos.sectionRelative(blockPos.getY()), SectionPos.sectionRelative(blockPos.getZ())
                    );
                    PoiTypes.forState(blockState).ifPresent(holder -> posToTypeConsumer.accept(blockPos, (Holder<PoiType>)holder));
                }
            );
    }

    public void ensureLoadedAndValid(LevelReader level, BlockPos pos, int radius) {
        SectionPos.aroundChunk(
                new ChunkPos(pos), Math.floorDiv(radius, 16), this.levelHeightAccessor.getMinSectionY(), this.levelHeightAccessor.getMaxSectionY()
            )
            .map(sectionPos -> Pair.of(sectionPos, this.getOrLoad(sectionPos.asLong())))
            .filter(pair -> !pair.getSecond().map(PoiSection::isValid).orElse(false))
            .map(pair -> pair.getFirst().chunk())
            .filter(chunkPos -> this.loadedChunks.add(chunkPos.toLong()))
            .forEach(chunkPos -> level.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.EMPTY));
    }

    final class DistanceTracker extends SectionTracker {
        private final Long2ByteMap levels = new Long2ByteOpenHashMap();

        protected DistanceTracker() {
            super(7, 16, 256);
            this.levels.defaultReturnValue((byte)7);
        }

        @Override
        protected int getLevelFromSource(long pos) {
            return PoiManager.this.isVillageCenter(pos) ? 0 : 7;
        }

        @Override
        protected int getLevel(long sectionPos) {
            return this.levels.get(sectionPos);
        }

        @Override
        protected void setLevel(long sectionPos, int level) {
            if (level > 6) {
                this.levels.remove(sectionPos);
            } else {
                this.levels.put(sectionPos, (byte)level);
            }
        }

        public void runAllUpdates() {
            super.runUpdates(Integer.MAX_VALUE);
        }
    }

    public static enum Occupancy {
        HAS_SPACE(PoiRecord::hasSpace),
        IS_OCCUPIED(PoiRecord::isOccupied),
        ANY(test -> true);

        private final Predicate<? super PoiRecord> test;

        private Occupancy(final Predicate<? super PoiRecord> test) {
            this.test = test;
        }

        public Predicate<? super PoiRecord> getTest() {
            return this.test;
        }
    }
}
