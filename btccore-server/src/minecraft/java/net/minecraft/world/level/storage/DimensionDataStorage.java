package net.minecraft.world.level.storage;

import com.google.common.collect.Iterables;
import com.mojang.datafixers.DataFixer;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.FastBufferedInputStream;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class DimensionDataStorage implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    public final Map<SavedDataType<?>, Optional<SavedData>> cache = new HashMap<>();
    private final DataFixer fixerUpper;
    private final HolderLookup.Provider registries;
    private final Path dataFolder;
    private CompletableFuture<?> pendingWriteFuture = CompletableFuture.completedFuture(null);

    public DimensionDataStorage(Path dataFolder, DataFixer fixerUpper, HolderLookup.Provider registries) {
        this.fixerUpper = fixerUpper;
        this.dataFolder = dataFolder;
        this.registries = registries;
    }

    private Path getDataFile(String filename) {
        return this.dataFolder.resolve(filename + ".dat");
    }

    public <T extends SavedData> T computeIfAbsent(SavedDataType<T> type) {
        T savedData = this.get(type);
        if (savedData != null) {
            return savedData;
        } else {
            T savedData1 = (T)type.constructor().get();
            this.set(type, savedData1);
            return savedData1;
        }
    }

    public <T extends SavedData> @Nullable T get(SavedDataType<T> type) {
        Optional<SavedData> optional = this.cache.get(type);
        if (optional == null) {
            optional = Optional.ofNullable(this.readSavedData(type));
            this.cache.put(type, optional);
        }

        return (T)optional.orElse(null);
    }

    private <T extends SavedData> @Nullable T readSavedData(SavedDataType<T> type) {
        try {
            Path dataFile = this.getDataFile(type.id());
            if (Files.exists(dataFile)) {
                CompoundTag tagFromDisk = this.readTagFromDisk(type.id(), type.dataFixType(), SharedConstants.getCurrentVersion().dataVersion().version());
                RegistryOps<Tag> registryOps = this.registries.createSerializationContext(NbtOps.INSTANCE);
                return type.codec()
                    .parse(registryOps, tagFromDisk.get("data"))
                    .resultOrPartial(string -> LOGGER.error("Failed to parse saved data for '{}': {}", type, string))
                    .orElse(null);
            }
        } catch (Exception var5) {
            LOGGER.error("Error loading saved data: {}", type, var5);
        }

        return null;
    }

    public <T extends SavedData> void set(SavedDataType<T> type, T value) {
        this.cache.put(type, Optional.of(value));
        value.setDirty();
    }

    public CompoundTag readTagFromDisk(String filename, DataFixTypes dataFixType, int version) throws IOException {
        CompoundTag var8;
        try (
            InputStream inputStream = Files.newInputStream(this.getDataFile(filename));
            PushbackInputStream pushbackInputStream = new PushbackInputStream(new FastBufferedInputStream(inputStream), 2);
        ) {
            CompoundTag compressed;
            if (this.isGzip(pushbackInputStream)) {
                compressed = NbtIo.readCompressed(pushbackInputStream, NbtAccounter.unlimitedHeap());
            } else {
                try (DataInputStream dataInputStream = new DataInputStream(pushbackInputStream)) {
                    compressed = NbtIo.read(dataInputStream);
                }
            }

            int dataVersion = NbtUtils.getDataVersion(compressed, 1343);
            var8 = dataFixType.update(this.fixerUpper, compressed, dataVersion, version);
        }

        return var8;
    }

    private boolean isGzip(PushbackInputStream inputStream) throws IOException {
        byte[] bytes = new byte[2];
        boolean flag = false;
        int i = inputStream.read(bytes, 0, 2);
        if (i == 2) {
            int i1 = (bytes[1] & 255) << 8 | bytes[0] & 255;
            if (i1 == 35615) {
                flag = true;
            }
        }

        if (i != 0) {
            inputStream.unread(bytes, 0, i);
        }

        return flag;
    }

    public CompletableFuture<?> scheduleSave() {
        Map<SavedDataType<?>, CompoundTag> map = this.collectDirtyTagsToSave();
        if (map.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        } else {
            int i = Util.maxAllowedExecutorThreads();
            int size = map.size();
            if (false && size > i) { // Paper - Separate dimension data IO pool; just throw them into the fixed pool queue
                this.pendingWriteFuture = this.pendingWriteFuture.thenCompose(object -> {
                    List<CompletableFuture<?>> list = new ArrayList<>(i);
                    int i1 = Mth.positiveCeilDiv(size, i);

                    for (List<Entry<SavedDataType<?>, CompoundTag>> list1 : Iterables.partition(map.entrySet(), i1)) {
                        list.add(CompletableFuture.runAsync(() -> {
                            for (Entry<SavedDataType<?>, CompoundTag> entry : list1) {
                                this.tryWrite(entry.getKey(), entry.getValue());
                            }
                        }, Util.ioPool()));
                    }

                    return CompletableFuture.allOf(list.toArray(CompletableFuture[]::new));
                });
            } else {
                this.pendingWriteFuture = this.pendingWriteFuture
                    .thenCompose(
                        object -> CompletableFuture.allOf(
                            map.entrySet()
                                .stream()
                                .map(entry -> CompletableFuture.runAsync(() -> this.tryWrite(entry.getKey(), entry.getValue()), Util.DIMENSION_DATA_IO_POOL)) // Paper - Separate dimension data IO pool
                                .toArray(CompletableFuture[]::new)
                        )
                    );
            }

            return this.pendingWriteFuture;
        }
    }

    private Map<SavedDataType<?>, CompoundTag> collectDirtyTagsToSave() {
        Map<SavedDataType<?>, CompoundTag> map = new Object2ObjectArrayMap<>();
        RegistryOps<Tag> registryOps = this.registries.createSerializationContext(NbtOps.INSTANCE);
        this.cache.forEach((savedDataType, optional) -> optional.filter(SavedData::isDirty).ifPresent(savedData -> {
            map.put(savedDataType, this.encodeUnchecked(savedDataType, savedData, registryOps));
            savedData.setDirty(false);
        }));
        return map;
    }

    private <T extends SavedData> CompoundTag encodeUnchecked(SavedDataType<T> type, SavedData data, RegistryOps<Tag> ops) {
        Codec<T> codec = type.codec();
        CompoundTag compoundTag = new CompoundTag();
        compoundTag.put("data", codec.encodeStart(ops, (T)data).getOrThrow());
        NbtUtils.addCurrentDataVersion(compoundTag);
        return compoundTag;
    }

    private void tryWrite(SavedDataType<?> type, CompoundTag tag) {
        Path dataFile = this.getDataFile(type.id());

        try {
            NbtIo.writeCompressed(tag, dataFile);
        } catch (IOException var5) {
            LOGGER.error("Could not save data to {}", dataFile.getFileName(), var5);
        }
    }

    public void saveAndJoin() {
        this.scheduleSave().join();
    }

    @Override
    public void close() {
        this.saveAndJoin();
    }
}
