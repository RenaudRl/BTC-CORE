package net.minecraft.world.level.chunk.storage;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.OptionalDynamic;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class SectionStorage<R, P> implements AutoCloseable {
    static final Logger LOGGER = LogUtils.getLogger();
    private static final String SECTIONS_TAG = "Sections";
    private final SimpleRegionStorage simpleRegionStorage;
    private final Long2ObjectMap<Optional<R>> storage = new Long2ObjectOpenHashMap<>();
    private final LongLinkedOpenHashSet dirtyChunks = new LongLinkedOpenHashSet();
    private final Codec<P> codec;
    private final Function<R, P> packer;
    private final BiFunction<P, Runnable, R> unpacker;
    private final Function<Runnable, R> factory;
    private final RegistryAccess registryAccess;
    private final ChunkIOErrorReporter errorReporter;
    protected final LevelHeightAccessor levelHeightAccessor;
    private final LongSet loadedChunks = new LongOpenHashSet();
    private final Long2ObjectMap<CompletableFuture<Optional<SectionStorage.PackedChunk<P>>>> pendingLoads = new Long2ObjectOpenHashMap<>();
    private final Object loadLock = new Object();

    public SectionStorage(
        SimpleRegionStorage simpleRegionStorage,
        Codec<P> codec,
        Function<R, P> packer,
        BiFunction<P, Runnable, R> unpacker,
        Function<Runnable, R> factory,
        RegistryAccess registryAccess,
        ChunkIOErrorReporter errorReporter,
        LevelHeightAccessor levelHeightAccessor
    ) {
        this.simpleRegionStorage = simpleRegionStorage;
        this.codec = codec;
        this.packer = packer;
        this.unpacker = unpacker;
        this.factory = factory;
        this.registryAccess = registryAccess;
        this.errorReporter = errorReporter;
        this.levelHeightAccessor = levelHeightAccessor;
    }

    protected void tick(BooleanSupplier aheadOfTime) {
        LongIterator longIterator = this.dirtyChunks.iterator();

        while (longIterator.hasNext() && aheadOfTime.getAsBoolean()) {
            ChunkPos chunkPos = new ChunkPos(longIterator.nextLong());
            longIterator.remove();
            this.writeChunk(chunkPos);
        }

        this.unpackPendingLoads();
    }

    private void unpackPendingLoads() {
        synchronized (this.loadLock) {
            Iterator<Entry<CompletableFuture<Optional<SectionStorage.PackedChunk<P>>>>> iterator = Long2ObjectMaps.fastIterator(this.pendingLoads);

            while (iterator.hasNext()) {
                Entry<CompletableFuture<Optional<SectionStorage.PackedChunk<P>>>> entry = iterator.next();
                Optional<SectionStorage.PackedChunk<P>> optional = entry.getValue().getNow(null);
                if (optional != null) {
                    long longKey = entry.getLongKey();
                    this.unpackChunk(new ChunkPos(longKey), optional.orElse(null));
                    iterator.remove();
                    this.loadedChunks.add(longKey);
                }
            }
        }
    }

    public void flushAll() {
        if (!this.dirtyChunks.isEmpty()) {
            this.dirtyChunks.forEach(l -> this.writeChunk(new ChunkPos(l)));
            this.dirtyChunks.clear();
        }
    }

    public boolean hasWork() {
        return !this.dirtyChunks.isEmpty();
    }

    protected @Nullable Optional<R> get(long sectionKey) {
        return this.storage.get(sectionKey);
    }

    protected Optional<R> getOrLoad(long sectionKey) {
        if (this.outsideStoredRange(sectionKey)) {
            return Optional.empty();
        } else {
            Optional<R> optional = this.get(sectionKey);
            if (optional != null) {
                return optional;
            } else {
                this.unpackChunk(SectionPos.of(sectionKey).chunk());
                optional = this.get(sectionKey);
                if (optional == null) {
                    throw (IllegalStateException)Util.pauseInIde(new IllegalStateException());
                } else {
                    return optional;
                }
            }
        }
    }

    protected boolean outsideStoredRange(long sectionKey) {
        int blockPosY = SectionPos.sectionToBlockCoord(SectionPos.y(sectionKey));
        return this.levelHeightAccessor.isOutsideBuildHeight(blockPosY);
    }

    protected R getOrCreate(long sectionKey) {
        if (this.outsideStoredRange(sectionKey)) {
            throw (IllegalArgumentException)Util.pauseInIde(new IllegalArgumentException("sectionPos out of bounds"));
        } else {
            Optional<R> orLoad = this.getOrLoad(sectionKey);
            if (orLoad.isPresent()) {
                return orLoad.get();
            } else {
                R object = this.factory.apply(() -> this.setDirty(sectionKey));
                this.storage.put(sectionKey, Optional.of(object));
                return object;
            }
        }
    }

    public CompletableFuture<?> prefetch(ChunkPos pos) {
        synchronized (this.loadLock) {
            long packedChunkPos = pos.toLong();
            return this.loadedChunks.contains(packedChunkPos)
                ? CompletableFuture.completedFuture(null)
                : this.pendingLoads.computeIfAbsent(packedChunkPos, l -> this.tryRead(pos));
        }
    }

    private void unpackChunk(ChunkPos pos) {
        long packedChunkPos = pos.toLong();
        CompletableFuture<Optional<SectionStorage.PackedChunk<P>>> completableFuture;
        synchronized (this.loadLock) {
            if (!this.loadedChunks.add(packedChunkPos)) {
                return;
            }

            completableFuture = this.pendingLoads.computeIfAbsent(packedChunkPos, l -> this.tryRead(pos));
        }

        this.unpackChunk(pos, completableFuture.join().orElse(null));
        synchronized (this.loadLock) {
            this.pendingLoads.remove(packedChunkPos);
        }
    }

    private CompletableFuture<Optional<SectionStorage.PackedChunk<P>>> tryRead(ChunkPos chunkPos) {
        RegistryOps<Tag> registryOps = this.registryAccess.createSerializationContext(NbtOps.INSTANCE);
        return this.simpleRegionStorage
            .read(chunkPos)
            .thenApplyAsync(
                optional -> optional.map(
                    compoundTag -> SectionStorage.PackedChunk.parse(this.codec, registryOps, compoundTag, this.simpleRegionStorage, this.levelHeightAccessor)
                ),
                Util.backgroundExecutor().forName("parseSection")
            )
            .exceptionally(cause -> {
                if (cause instanceof CompletionException) {
                    cause = cause.getCause();
                }

                if (cause instanceof IOException ioException) {
                    LOGGER.error("Error reading chunk {} data from disk", chunkPos, ioException);
                    this.errorReporter.reportChunkLoadFailure(ioException, this.simpleRegionStorage.storageInfo(), chunkPos);
                    return Optional.empty();
                } else {
                    throw new CompletionException(cause);
                }
            });
    }

    private void unpackChunk(ChunkPos pos, SectionStorage.@Nullable PackedChunk<P> packedChunk) {
        if (packedChunk == null) {
            for (int sectionY = this.levelHeightAccessor.getMinSectionY(); sectionY <= this.levelHeightAccessor.getMaxSectionY(); sectionY++) {
                this.storage.put(getKey(pos, sectionY), Optional.empty());
            }
        } else {
            boolean versionChanged = packedChunk.versionChanged();

            for (int sectionY1 = this.levelHeightAccessor.getMinSectionY(); sectionY1 <= this.levelHeightAccessor.getMaxSectionY(); sectionY1++) {
                long key = getKey(pos, sectionY1);
                Optional<R> optional = Optional.ofNullable(packedChunk.sectionsByY.get(sectionY1))
                    .map(object -> this.unpacker.apply((P)object, () -> this.setDirty(key)));
                this.storage.put(key, optional);
                optional.ifPresent(object -> {
                    this.onSectionLoad(key);
                    if (versionChanged) {
                        this.setDirty(key);
                    }
                });
            }
        }
    }

    private void writeChunk(ChunkPos pos) {
        RegistryOps<Tag> registryOps = this.registryAccess.createSerializationContext(NbtOps.INSTANCE);
        Dynamic<Tag> dynamic = this.writeChunk(pos, registryOps);
        Tag tag = dynamic.getValue();
        if (tag instanceof CompoundTag compoundTag) {
            this.simpleRegionStorage.write(pos, compoundTag).exceptionally(throwable -> {
                this.errorReporter.reportChunkSaveFailure(throwable, this.simpleRegionStorage.storageInfo(), pos);
                return null;
            });
        } else {
            LOGGER.error("Expected compound tag, got {}", tag);
        }
    }

    private <T> Dynamic<T> writeChunk(ChunkPos pos, DynamicOps<T> ops) {
        Map<T, T> map = Maps.newHashMap();

        for (int sectionY = this.levelHeightAccessor.getMinSectionY(); sectionY <= this.levelHeightAccessor.getMaxSectionY(); sectionY++) {
            long key = getKey(pos, sectionY);
            Optional<R> optional = this.storage.get(key);
            if (optional != null && !optional.isEmpty()) {
                DataResult<T> dataResult = this.codec.encodeStart(ops, this.packer.apply(optional.get()));
                String string = Integer.toString(sectionY);
                dataResult.resultOrPartial(LOGGER::error).ifPresent(object -> map.put(ops.createString(string), (T)object));
            }
        }

        return new Dynamic<>(
            ops,
            ops.createMap(
                ImmutableMap.of(
                    ops.createString("Sections"),
                    ops.createMap(map),
                    ops.createString("DataVersion"),
                    ops.createInt(SharedConstants.getCurrentVersion().dataVersion().version())
                )
            )
        );
    }

    private static long getKey(ChunkPos chunkPos, int sectionY) {
        return SectionPos.asLong(chunkPos.x, sectionY, chunkPos.z);
    }

    protected void onSectionLoad(long sectionKey) {
    }

    protected void setDirty(long sectionPos) {
        Optional<R> optional = this.storage.get(sectionPos);
        if (optional != null && !optional.isEmpty()) {
            this.dirtyChunks.add(ChunkPos.asLong(SectionPos.x(sectionPos), SectionPos.z(sectionPos)));
        } else {
            LOGGER.warn("No data for position: {}", SectionPos.of(sectionPos));
        }
    }

    public void flush(ChunkPos chunkPos) {
        if (this.dirtyChunks.remove(chunkPos.toLong())) {
            this.writeChunk(chunkPos);
        }
    }

    @Override
    public void close() throws IOException {
        this.simpleRegionStorage.close();
    }

    record PackedChunk<T>(Int2ObjectMap<T> sectionsByY, boolean versionChanged) {
        public static <T> SectionStorage.PackedChunk<T> parse(
            Codec<T> codec, DynamicOps<Tag> ops, Tag value, SimpleRegionStorage simpleRegionStorage, LevelHeightAccessor level
        ) {
            Dynamic<Tag> dynamic = new Dynamic<>(ops, value);
            Dynamic<Tag> dynamic1 = simpleRegionStorage.upgradeChunkTag(dynamic, 1945);
            boolean flag = dynamic != dynamic1;
            OptionalDynamic<Tag> optionalDynamic = dynamic1.get("Sections");
            Int2ObjectMap<T> map = new Int2ObjectOpenHashMap<>();

            for (int sectionY = level.getMinSectionY(); sectionY <= level.getMaxSectionY(); sectionY++) {
                Optional<T> optional = optionalDynamic.get(Integer.toString(sectionY))
                    .result()
                    .flatMap(dynamic2 -> codec.parse((Dynamic<Tag>)dynamic2).resultOrPartial(SectionStorage.LOGGER::error));
                if (optional.isPresent()) {
                    map.put(sectionY, optional.get());
                }
            }

            return new SectionStorage.PackedChunk<>(map, flag);
        }
    }
}
