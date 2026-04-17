package net.minecraft.world.level.chunk;

import com.google.common.annotations.VisibleForTesting;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.LongStream;
import net.minecraft.core.IdMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.BitStorage;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ThreadingDetector;
import net.minecraft.util.ZeroBitStorage;
import org.jspecify.annotations.Nullable;

public class PalettedContainer<T> implements PaletteResize<T>, PalettedContainerRO<T> {
    private static final int MIN_PALETTE_BITS = 0;
    private volatile PalettedContainer.Data<T> data;
    private final Strategy<T> strategy;
    private final ThreadingDetector threadingDetector = new ThreadingDetector("PalettedContainer");

    public void acquire() {
        this.threadingDetector.checkAndLock();
    }

    public void release() {
        this.threadingDetector.checkAndUnlock();
    }

    public static <T> Codec<PalettedContainer<T>> codecRW(Codec<T> valueCodec, Strategy<T> strategy, T defaultValue) {
        PalettedContainerRO.Unpacker<T, PalettedContainer<T>> unpacker = PalettedContainer::unpack;
        return codec(valueCodec, strategy, defaultValue, unpacker);
    }

    public static <T> Codec<PalettedContainerRO<T>> codecRO(Codec<T> valueCodec, Strategy<T> strategy, T defaultValue) {
        PalettedContainerRO.Unpacker<T, PalettedContainerRO<T>> unpacker = (strategy1, packedData) -> unpack(strategy1, packedData)
            .map(container -> (PalettedContainerRO<T>)container);
        return codec(valueCodec, strategy, defaultValue, unpacker);
    }

    private static <T, C extends PalettedContainerRO<T>> Codec<C> codec(
        Codec<T> valueCodec, Strategy<T> strategy, T defaultValue, PalettedContainerRO.Unpacker<T, C> unpacker
    ) {
        return RecordCodecBuilder.<PalettedContainerRO.PackedData>create(
                instance -> instance.group(
                        valueCodec.mapResult(ExtraCodecs.orElsePartial(defaultValue))
                            .listOf()
                            .fieldOf("palette")
                            .forGetter(PalettedContainerRO.PackedData::paletteEntries),
                        Codec.LONG_STREAM.lenientOptionalFieldOf("data").forGetter(PalettedContainerRO.PackedData::storage)
                    )
                    .apply(instance, PalettedContainerRO.PackedData::new)
            )
            .comapFlatMap(packedData -> unpacker.read(strategy, (PalettedContainerRO.PackedData<T>)packedData), container -> container.pack(strategy));
    }

    private PalettedContainer(Strategy<T> strategy, Configuration configuration, BitStorage storage, Palette<T> palette) {
        this.strategy = strategy;
        this.data = new PalettedContainer.Data<>(configuration, storage, palette);
    }

    private PalettedContainer(PalettedContainer<T> other) {
        this.strategy = other.strategy;
        this.data = other.data.copy();
    }

    public PalettedContainer(T defaultValue, Strategy<T> strategy) {
        this.strategy = strategy;
        this.data = this.createOrReuseData(null, 0);
        this.data.palette.idFor(defaultValue, this);
    }

    private PalettedContainer.Data<T> createOrReuseData(PalettedContainer.@Nullable Data<T> data, int bits) {
        Configuration configurationForBitCount = this.strategy.getConfigurationForBitCount(bits);
        if (data != null && configurationForBitCount.equals(data.configuration())) {
            return data;
        } else {
            BitStorage bitStorage = (BitStorage)(configurationForBitCount.bitsInMemory() == 0
                ? new ZeroBitStorage(this.strategy.entryCount())
                : new SimpleBitStorage(configurationForBitCount.bitsInMemory(), this.strategy.entryCount()));
            Palette<T> palette = configurationForBitCount.createPalette(this.strategy, List.of());
            return new PalettedContainer.Data<>(configurationForBitCount, bitStorage, palette);
        }
    }

    @Override
    public int onResize(int bits, T addedValue) {
        PalettedContainer.Data<T> data = this.data;
        PalettedContainer.Data<T> data1 = this.createOrReuseData(data, bits);
        data1.copyFrom(data.palette, data.storage);
        this.data = data1;
        return data1.palette.idFor(addedValue, PaletteResize.noResizeExpected());
    }

    public T getAndSet(int x, int y, int z, T state) {
        this.acquire();

        Object var5;
        try {
            var5 = this.getAndSet(this.strategy.getIndex(x, y, z), state);
        } finally {
            this.release();
        }

        return (T)var5;
    }

    public T getAndSetUnchecked(int x, int y, int z, T state) {
        return this.getAndSet(this.strategy.getIndex(x, y, z), state);
    }

    private T getAndSet(int index, T state) {
        int i = this.data.palette.idFor(state, this);
        int andSet = this.data.storage.getAndSet(index, i);
        return this.data.palette.valueFor(andSet);
    }

    public void set(int x, int y, int z, T state) {
        this.acquire();

        try {
            this.set(this.strategy.getIndex(x, y, z), state);
        } finally {
            this.release();
        }
    }

    private void set(int index, T state) {
        int i = this.data.palette.idFor(state, this);
        this.data.storage.set(index, i);
    }

    @Override
    public T get(int x, int y, int z) {
        return this.get(this.strategy.getIndex(x, y, z));
    }

    protected T get(int index) {
        PalettedContainer.Data<T> data = this.data;
        return data.palette.valueFor(data.storage.get(index));
    }

    @Override
    public void getAll(Consumer<T> consumer) {
        Palette<T> palette = this.data.palette();
        IntSet set = new IntArraySet();
        this.data.storage.getAll(set::add);
        set.forEach(id -> consumer.accept(palette.valueFor(id)));
    }

    public void read(FriendlyByteBuf buffer) {
        this.acquire();

        try {
            int _byte = buffer.readByte();
            PalettedContainer.Data<T> data = this.createOrReuseData(this.data, _byte);
            data.palette.read(buffer, this.strategy.globalMap());
            buffer.readFixedSizeLongArray(data.storage.getRaw());
            this.data = data;
        } finally {
            this.release();
        }
    }

    @Override
    public void write(FriendlyByteBuf buffer) {
        this.acquire();

        try {
            this.data.write(buffer, this.strategy.globalMap());
        } finally {
            this.release();
        }
    }

    @VisibleForTesting
    public static <T> DataResult<PalettedContainer<T>> unpack(Strategy<T> strategy, PalettedContainerRO.PackedData<T> packedData) {
        List<T> list = packedData.paletteEntries();
        int entryCount = strategy.entryCount();
        Configuration configurationForPaletteSize = strategy.getConfigurationForPaletteSize(list.size());
        int i = configurationForPaletteSize.bitsInStorage();
        if (packedData.bitsPerEntry() != -1 && i != packedData.bitsPerEntry()) {
            return DataResult.error(() -> "Invalid bit count, calculated " + i + ", but container declared " + packedData.bitsPerEntry());
        } else {
            BitStorage bitStorage;
            Palette<T> palette;
            if (configurationForPaletteSize.bitsInMemory() == 0) {
                palette = configurationForPaletteSize.createPalette(strategy, list);
                bitStorage = new ZeroBitStorage(entryCount);
            } else {
                Optional<LongStream> optional = packedData.storage();
                if (optional.isEmpty()) {
                    return DataResult.error(() -> "Missing values for non-zero storage");
                }

                long[] longs = optional.get().toArray();

                try {
                    if (!configurationForPaletteSize.alwaysRepack() && configurationForPaletteSize.bitsInMemory() == i) {
                        palette = configurationForPaletteSize.createPalette(strategy, list);
                        bitStorage = new SimpleBitStorage(configurationForPaletteSize.bitsInMemory(), entryCount, longs);
                    } else {
                        Palette<T> palette1 = new HashMapPalette<>(i, list);
                        SimpleBitStorage simpleBitStorage = new SimpleBitStorage(i, entryCount, longs);
                        Palette<T> palette2 = configurationForPaletteSize.createPalette(strategy, list);
                        int[] ints = reencodeContents(simpleBitStorage, palette1, palette2);
                        palette = palette2;
                        bitStorage = new SimpleBitStorage(configurationForPaletteSize.bitsInMemory(), entryCount, ints);
                    }
                } catch (SimpleBitStorage.InitializationException var14) {
                    return DataResult.error(() -> "Failed to read PalettedContainer: " + var14.getMessage());
                }
            }

            return DataResult.success(new PalettedContainer<>(strategy, configurationForPaletteSize, bitStorage, palette));
        }
    }

    @Override
    public PalettedContainerRO.PackedData<T> pack(Strategy<T> strategy) {
        this.acquire();

        PalettedContainerRO.PackedData var14;
        try {
            BitStorage bitStorage = this.data.storage;
            Palette<T> palette = this.data.palette;
            HashMapPalette<T> hashMapPalette = new HashMapPalette<>(bitStorage.getBits());
            int entryCount = strategy.entryCount();
            int[] ints = reencodeContents(bitStorage, palette, hashMapPalette);
            Configuration configurationForPaletteSize = strategy.getConfigurationForPaletteSize(hashMapPalette.getSize());
            int i = configurationForPaletteSize.bitsInStorage();
            Optional<LongStream> optional;
            if (i != 0) {
                SimpleBitStorage simpleBitStorage = new SimpleBitStorage(i, entryCount, ints);
                optional = Optional.of(Arrays.stream(simpleBitStorage.getRaw()));
            } else {
                optional = Optional.empty();
            }

            var14 = new PalettedContainerRO.PackedData<>(hashMapPalette.getEntries(), optional, i);
        } finally {
            this.release();
        }

        return var14;
    }

    private static <T> int[] reencodeContents(BitStorage bitStorage, Palette<T> oldPalette, Palette<T> newPalette) {
        int[] ints = new int[bitStorage.getSize()];
        bitStorage.unpack(ints);
        PaletteResize<T> paletteResize = PaletteResize.noResizeExpected();
        int i = -1;
        int i1 = -1;

        for (int i2 = 0; i2 < ints.length; i2++) {
            int i3 = ints[i2];
            if (i3 != i) {
                i = i3;
                i1 = newPalette.idFor(oldPalette.valueFor(i3), paletteResize);
            }

            ints[i2] = i1;
        }

        return ints;
    }

    @Override
    public int getSerializedSize() {
        return this.data.getSerializedSize(this.strategy.globalMap());
    }

    @Override
    public int bitsPerEntry() {
        return this.data.storage().getBits();
    }

    @Override
    public boolean maybeHas(Predicate<T> predicate) {
        return this.data.palette.maybeHas(predicate);
    }

    @Override
    public PalettedContainer<T> copy() {
        return new PalettedContainer<>(this);
    }

    @Override
    public PalettedContainer<T> recreate() {
        return new PalettedContainer<>(this.data.palette.valueFor(0), this.strategy);
    }

    @Override
    public void count(PalettedContainer.CountConsumer<T> countConsumer) {
        if (this.data.palette.getSize() == 1) {
            countConsumer.accept(this.data.palette.valueFor(0), this.data.storage.getSize());
        } else {
            Int2IntOpenHashMap map = new Int2IntOpenHashMap();
            this.data.storage.getAll(id -> map.addTo(id, 1));
            map.int2IntEntrySet().forEach(idEntry -> countConsumer.accept(this.data.palette.valueFor(idEntry.getIntKey()), idEntry.getIntValue()));
        }
    }

    @FunctionalInterface
    public interface CountConsumer<T> {
        void accept(T state, int count);
    }

    record Data<T>(Configuration configuration, BitStorage storage, Palette<T> palette) {
        public void copyFrom(Palette<T> palette, BitStorage bitStorage) {
            PaletteResize<T> paletteResize = PaletteResize.noResizeExpected();

            for (int i = 0; i < bitStorage.getSize(); i++) {
                T object = palette.valueFor(bitStorage.get(i));
                this.storage.set(i, this.palette.idFor(object, paletteResize));
            }
        }

        public int getSerializedSize(IdMap<T> map) {
            return 1 + this.palette.getSerializedSize(map) + this.storage.getRaw().length * 8;
        }

        public void write(FriendlyByteBuf buffer, IdMap<T> map) {
            buffer.writeByte(this.storage.getBits());
            this.palette.write(buffer, map);
            buffer.writeFixedSizeLongArray(this.storage.getRaw());
        }

        public PalettedContainer.Data<T> copy() {
            return new PalettedContainer.Data<>(this.configuration, this.storage.copy(), this.palette.copy());
        }
    }
}
