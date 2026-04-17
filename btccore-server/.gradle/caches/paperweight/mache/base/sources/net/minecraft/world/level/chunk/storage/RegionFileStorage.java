package net.minecraft.world.level.chunk.storage;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.util.ExceptionCollector;
import net.minecraft.util.FileUtil;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

public final class RegionFileStorage implements AutoCloseable {
    public static final String ANVIL_EXTENSION = ".mca";
    private static final int MAX_CACHE_SIZE = 256;
    public final Long2ObjectLinkedOpenHashMap<RegionFile> regionCache = new Long2ObjectLinkedOpenHashMap<>();
    private final RegionStorageInfo info;
    private final Path folder;
    private final boolean sync;

    RegionFileStorage(RegionStorageInfo info, Path folder, boolean sync) {
        this.folder = folder;
        this.sync = sync;
        this.info = info;
    }

    private RegionFile getRegionFile(ChunkPos chunkPos) throws IOException {
        long packedChunkPos = ChunkPos.asLong(chunkPos.getRegionX(), chunkPos.getRegionZ());
        RegionFile regionFile = this.regionCache.getAndMoveToFirst(packedChunkPos);
        if (regionFile != null) {
            return regionFile;
        } else {
            if (this.regionCache.size() >= 256) {
                this.regionCache.removeLast().close();
            }

            FileUtil.createDirectoriesSafe(this.folder);
            Path path = this.folder.resolve("r." + chunkPos.getRegionX() + "." + chunkPos.getRegionZ() + ".mca");
            RegionFile regionFile1 = new RegionFile(this.info, path, this.folder, this.sync);
            this.regionCache.putAndMoveToFirst(packedChunkPos, regionFile1);
            return regionFile1;
        }
    }

    public @Nullable CompoundTag read(ChunkPos chunkPos) throws IOException {
        RegionFile regionFile = this.getRegionFile(chunkPos);

        CompoundTag var4;
        try (DataInputStream chunkDataInputStream = regionFile.getChunkDataInputStream(chunkPos)) {
            if (chunkDataInputStream == null) {
                return null;
            }

            var4 = NbtIo.read(chunkDataInputStream);
        }

        return var4;
    }

    public void scanChunk(ChunkPos chunkPos, StreamTagVisitor visitor) throws IOException {
        RegionFile regionFile = this.getRegionFile(chunkPos);

        try (DataInputStream chunkDataInputStream = regionFile.getChunkDataInputStream(chunkPos)) {
            if (chunkDataInputStream != null) {
                NbtIo.parse(chunkDataInputStream, visitor, NbtAccounter.unlimitedHeap());
            }
        }
    }

    protected void write(ChunkPos chunkPos, @Nullable CompoundTag chunkData) throws IOException {
        if (!SharedConstants.DEBUG_DONT_SAVE_WORLD) {
            RegionFile regionFile = this.getRegionFile(chunkPos);
            if (chunkData == null) {
                regionFile.clear(chunkPos);
            } else {
                try (DataOutputStream chunkDataOutputStream = regionFile.getChunkDataOutputStream(chunkPos)) {
                    NbtIo.write(chunkData, chunkDataOutputStream);
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        ExceptionCollector<IOException> exceptionCollector = new ExceptionCollector<>();

        for (RegionFile regionFile : this.regionCache.values()) {
            try {
                regionFile.close();
            } catch (IOException var5) {
                exceptionCollector.add(var5);
            }
        }

        exceptionCollector.throwIfPresent();
    }

    public void flush() throws IOException {
        for (RegionFile regionFile : this.regionCache.values()) {
            regionFile.flush();
        }
    }

    public RegionStorageInfo info() {
        return this.info;
    }
}
