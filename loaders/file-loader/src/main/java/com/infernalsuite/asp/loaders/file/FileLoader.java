package com.infernalsuite.asp.loaders.file;

import com.github.luben.zstd.Zstd;
import com.infernalsuite.asp.api.exceptions.UnknownWorldException;
import com.infernalsuite.asp.api.loaders.SlimeLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.NotDirectoryException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class FileLoader implements SlimeLoader {

    private static final FilenameFilter WORLD_FILE_FILTER = (dir, name) -> name.endsWith(".slime");
    private static final Logger LOGGER = LoggerFactory.getLogger(FileLoader.class);

    private final File worldDir;
    private final File archiveDir;

    public FileLoader(File worldDir) throws IllegalStateException {
        this.worldDir = worldDir;
        this.archiveDir = new File(worldDir, "archive");

        if (worldDir.exists() && !worldDir.isDirectory()) {
            LOGGER.warn("A file named '{}' has been deleted, as this is the name used for the worlds directory.",
                    worldDir.getName());
            if (!worldDir.delete())
                throw new IllegalStateException("Failed to delete the file named '" + worldDir.getName() + "'.");
        }

        if (!worldDir.exists() && !worldDir.mkdirs())
            throw new IllegalStateException("Failed to create the worlds directory.");
        if (!archiveDir.exists() && !archiveDir.mkdirs())
            LOGGER.info("Created archive directory.");
    }

    @Override
    public byte[] readWorld(String worldName) throws UnknownWorldException, IOException {
        File activeFile = new File(worldDir, worldName + ".slime");

        // 1. Check Active
        if (activeFile.exists()) {
            try (FileInputStream fis = new FileInputStream(activeFile)) {
                return fis.readAllBytes();
            }
        }

        // 2. Check Archive
        File archiveFile = new File(archiveDir, worldName + ".slime.zst");
        if (archiveFile.exists()) {
            LOGGER.info("Restoring world '{}' from cold storage...", worldName);
            try (FileInputStream fis = new FileInputStream(archiveFile)) {
                byte[] compressed = fis.readAllBytes();
                // Decompress
                long originalSize = Zstd.decompressedSize(compressed);
                byte[] decompressed = Zstd.decompress(compressed, (int) originalSize);

                // Restore to Active
                try (FileOutputStream fos = new FileOutputStream(activeFile)) {
                    fos.write(decompressed);
                }

                // Delete Archive
                archiveFile.delete();

                return decompressed;
            } catch (Exception e) {
                LOGGER.error("Failed to restore world '{}' from archive!", worldName, e);
                throw new IOException("Corruption in archive for " + worldName, e);
            }
        }

        throw new UnknownWorldException(worldName);
    }

    @Override
    public boolean worldExists(String worldName) {
        return new File(worldDir, worldName + ".slime").exists()
                || new File(archiveDir, worldName + ".slime.zst").exists();
    }

    @Override
    public List<String> listWorlds() throws NotDirectoryException {
        String[] worlds = worldDir.list(WORLD_FILE_FILTER);

        if (worlds == null) {
            throw new NotDirectoryException(worldDir.getPath());
        }

        return Arrays.stream(worlds).map((c) -> c.substring(0, c.length() - 6)).collect(Collectors.toList());
    }

    @Override
    public void saveWorld(String worldName, byte[] serializedWorld) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(new File(worldDir, worldName + ".slime"))) {
            fos.write(serializedWorld);
        }
    }

    @Override
    public void deleteWorld(String worldName) throws UnknownWorldException, IOException {
        if (!worldExists(worldName)) {
            throw new UnknownWorldException(worldName);
        } else {
            if (!new File(worldDir, worldName + ".slime").delete()) {
                throw new IOException("Failed to delete the world file. File#delete() returned false.");
            }
        }
    }

    /**
     * Moves a world to cold storage (archive) and compresses it with Zstd.
     * 
     * @param worldName World to archive
     */
    public void archiveWorld(String worldName) throws IOException {
        File activeFile = new File(worldDir, worldName + ".slime");
        if (!activeFile.exists())
            return; // Nothing to archive

        File archiveFile = new File(archiveDir, worldName + ".slime.zst");

        try (FileInputStream fis = new FileInputStream(activeFile)) {
            byte[] data = fis.readAllBytes();
            byte[] compressed = Zstd.compress(data, 22); // Level 22 (Max) for cold storage

            try (FileOutputStream fos = new FileOutputStream(archiveFile)) {
                fos.write(compressed);
            }

            // Delete active file only if archive succeeded
            activeFile.delete();
            LOGGER.info("World '{}' moved to cold storage (Size: {} -> {} bytes)", worldName, data.length,
                    compressed.length);
        }
    }
}
