package dev.btc.core;

import dev.btc.core.api.SlimeNMSBridge;
import dev.btc.core.api.BTCCoreAPI;
import dev.btc.core.api.events.LoadSlimeWorldEvent;
import dev.btc.core.api.exceptions.*;
import dev.btc.core.api.loaders.SlimeLoader;
import dev.btc.core.api.loaders.SlimeSerializationAdapter;
import dev.btc.core.api.world.SlimeWorld;
import dev.btc.core.api.world.SlimeWorldInstance;
import dev.btc.core.api.world.properties.SlimePropertyMap;
import dev.btc.core.level.SlimeLevelInstance;
import dev.btc.core.serialization.SlimeSerializationAdapterImpl;
import dev.btc.core.serialization.anvil.AnvilImportData;
import dev.btc.core.serialization.anvil.AnvilWorldReader;
import dev.btc.core.serialization.slime.SlimeSerializer;
import dev.btc.core.serialization.slime.reader.SlimeWorldReaderRegistry;
import dev.btc.core.skeleton.SkeletonSlimeWorld;
import dev.btc.core.util.NmsUtil;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.world.WorldLoadEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spigotmc.AsyncCatcher;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class BTCCore implements BTCCoreAPI {

    private static final Logger LOGGER = LoggerFactory.getLogger(BTCCore.class);
    private static final SlimeNMSBridge BRIDGE_INSTANCE = SlimeNMSBridge.instance();

    private final Map<String, SlimeWorldInstance> loadedWorlds = new ConcurrentHashMap<>();

    static {
        System.setProperty("org.slf4j.simpleLogger.showShortLogName", "true");
    }

    private final SlimeSerializationAdapter serializationAdapter = new SlimeSerializationAdapterImpl();

    public static BTCCore instance() {
        return (BTCCore) BTCCoreAPI.instance();
    }

    @Override
    public SlimeWorld readWorld(SlimeLoader loader, String worldName, boolean readOnly, SlimePropertyMap propertyMap)
            throws UnknownWorldException, IOException, CorruptedWorldException, NewerFormatException {
        Objects.requireNonNull(loader, "Loader cannot be null");
        Objects.requireNonNull(worldName, "World name cannot be null");
        Objects.requireNonNull(propertyMap, "Properties cannot be null");

        long start = System.currentTimeMillis();

        LOGGER.debug("Reading world {}.", worldName);
        byte[] serializedWorld = loader.readWorld(worldName);

        SlimeWorld slimeWorld = SlimeWorldReaderRegistry.readWorld(loader, worldName, serializedWorld, propertyMap,
                readOnly);
        LOGGER.debug("Applying datafixers for {}.", worldName);
        SlimeWorld dataFixed = SlimeNMSBridge.instance().getSlimeDataConverter().applyDataFixers(slimeWorld);

        // If the dataFixed and slimeWorld are same, then no datafixers were applied
        if (!readOnly && dataFixed != slimeWorld)
            loader.saveWorld(worldName, SlimeSerializer.serialize(dataFixed)); // Write dataFixed world back to loader

        LOGGER.info("World {} read in {}ms.", worldName, System.currentTimeMillis() - start);

        return dataFixed;
    }

    @Override
    public SlimeWorldInstance loadWorld(SlimeWorld world, boolean callWorldLoadEvent) throws IllegalArgumentException {
        AsyncCatcher.catchOp("SWM world load");
        Objects.requireNonNull(world, "SlimeWorld cannot be null");

        if (Bukkit.getWorld(world.getName()) != null) {
            throw new IllegalArgumentException("World " + world.getName() + " is already loaded");
        }

        LOGGER.debug("Loading world {}...", world.getName());
        long start = System.currentTimeMillis();

        SlimeWorldInstance instance = BRIDGE_INSTANCE.loadInstance(world);

        // BTCCore: Apply Switc-specific GameRules on load
        dev.btc.core.config.SlimeWorldConfig.getInstance().applyRules(world.getName(),
                ((CraftWorld) instance.getBukkitWorld()).getHandle().getGameRules());

        Bukkit.getPluginManager().callEvent(new LoadSlimeWorldEvent(instance));
        if (callWorldLoadEvent) {
            Bukkit.getPluginManager().callEvent(new WorldLoadEvent(instance.getBukkitWorld()));
        }

        registerWorld(instance);

        LOGGER.info("World {} loaded in {}ms.", world.getName(), System.currentTimeMillis() - start);
        return instance;
    }

    @Override
    public boolean worldLoaded(SlimeWorld world) {
        return loadedWorlds.containsKey(world.getName());
    }

    @Override
    public void saveWorld(SlimeWorld world) throws IOException {
        Objects.requireNonNull(world, "SlimeWorld cannot be null");
        if (worldLoaded(world)) {
            Future<?>[] future = new Future[1];

            // This is not pretty, but we really need to hop onto the main thread
            NmsUtil.runSyncAndWait(() -> {
                World bukkitWorld = Bukkit.getWorld(world.getName());

                ServerLevel level = ((CraftWorld) bukkitWorld).getHandle();
                if (level instanceof SlimeLevelInstance slimeLevel) {
                    future[0] = slimeLevel.save();
                } else {
                    // Shouldn't happen
                    LOGGER.warn(
                            "ServerLevel based off of SlimeWorld is not an instance of SlimeLevelInstance. Falling back to default save method.");
                    bukkitWorld.save();
                }
            });

            if (future[0] != null) {
                try {
                    future[0].get();
                } catch (InterruptedException exception) {
                    throw new RuntimeException(exception);
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof IOException ioException) {
                        throw ioException;
                    } else {
                        throw new RuntimeException(e.getCause());
                    }
                }
            }
        } else {
            LOGGER.info("Saving unloaded world {}...", world.getName());
            Objects.requireNonNull(world.getLoader(), "World loader cannot be null");
            long start = System.currentTimeMillis();

            byte[] serializedWorld = SlimeSerializer.serialize(world);

            long saveStart = System.currentTimeMillis();
            world.getLoader().saveWorld(world.getName(), serializedWorld);

            LOGGER.info("World {} serialized in {}ms and saved in {}ms.", world.getName(), saveStart - start,
                    System.currentTimeMillis() - saveStart);
        }

    }

    @Override
    public SlimeWorldInstance getLoadedWorld(String worldName) {
        return loadedWorlds.get(worldName);
    }

    @Override
    public List<SlimeWorldInstance> getLoadedWorlds() {
        return List.copyOf(loadedWorlds.values());
    }

    @Override
    public SlimeWorld createEmptyWorld(String worldName, boolean readOnly, SlimePropertyMap propertyMap,
            SlimeLoader loader) {
        Objects.requireNonNull(worldName, "World name cannot be null");
        Objects.requireNonNull(propertyMap, "Properties cannot be null");

        return new SkeletonSlimeWorld(worldName, loader, readOnly, new Long2ObjectOpenHashMap<>(0),
                new ConcurrentHashMap<>(), propertyMap, BRIDGE_INSTANCE.getCurrentVersion());
    }

    @Override
    public void migrateWorld(String worldName, SlimeLoader currentLoader, SlimeLoader newLoader)
            throws IOException, WorldAlreadyExistsException, UnknownWorldException {
        Objects.requireNonNull(worldName, "World name cannot be null");
        Objects.requireNonNull(currentLoader, "Current loader cannot be null");
        Objects.requireNonNull(newLoader, "New loader cannot be null");

        if (newLoader.worldExists(worldName)) {
            throw new WorldAlreadyExistsException(worldName);
        }

        byte[] serializedWorld = currentLoader.readWorld(worldName);
        newLoader.saveWorld(worldName, serializedWorld);
        currentLoader.deleteWorld(worldName);
    }

    @Override
    public SlimeWorld readVanillaWorld(File worldDir, String worldName, SlimeLoader loader)
            throws InvalidWorldException, WorldLoadedException, WorldTooBigException, IOException,
            WorldAlreadyExistsException {
        Objects.requireNonNull(worldDir, "World directory cannot be null");
        Objects.requireNonNull(worldName, "World name cannot be null");

        if (loader != null && loader.worldExists(worldName)) {
            throw new WorldAlreadyExistsException(worldName);
        }

        World bukkitWorld = Bukkit.getWorld(worldName);

        if (bukkitWorld != null && BRIDGE_INSTANCE.getInstance(bukkitWorld) == null) {
            throw new WorldLoadedException(worldDir.getName());
        }

        SlimeWorld world;

        try {
            world = AnvilWorldReader.INSTANCE.readFromData(AnvilImportData.legacy(worldDir, worldName, loader));
        } catch (RuntimeException e) {
            if (e.getCause() == null) {
                throw e;
            }
            if (e.getCause() instanceof IOException ioException) {
                throw ioException;
            } else if (e.getCause() instanceof InvalidWorldException invalidWorldException) {
                throw invalidWorldException;
            } else {
                throw e;
            }
        }

        // A sanity check to make sure the world is not too big to be serialized
        try {
            SlimeSerializer.serialize(world);
        } catch (IndexOutOfBoundsException ex) {
            throw new WorldTooBigException(worldDir.getName());
        }

        return world;
    }

    @Override
    public SlimeSerializationAdapter getSerializer() {
        return this.serializationAdapter;
    }

    @Override
    public SlimeWorld cloneUnloadedWorld(String sourceWorldName, String targetWorldName, SlimeLoader loader,
            SlimePropertyMap propertyMap) throws UnknownWorldException, WorldAlreadyExistsException, IOException,
            CorruptedWorldException, NewerFormatException {
        Objects.requireNonNull(sourceWorldName, "Source world name cannot be null");
        Objects.requireNonNull(targetWorldName, "Target world name cannot be null");
        Objects.requireNonNull(loader, "Loader cannot be null");

        LOGGER.info("Cloning unloaded world '{}' to '{}'...", sourceWorldName, targetWorldName);
        long start = System.currentTimeMillis();

        // Check if target already exists
        if (loader.worldExists(targetWorldName)) {
            throw new WorldAlreadyExistsException(targetWorldName);
        }

        // Read source world data directly from loader
        byte[] serializedWorld = loader.readWorld(sourceWorldName);

        // Parse and optionally update properties
        SlimeWorld sourceWorld = SlimeWorldReaderRegistry.readWorld(loader, targetWorldName, serializedWorld,
                propertyMap != null ? propertyMap : new SlimePropertyMap(), false);

        // Apply data fixers
        SlimeWorld dataFixed = BRIDGE_INSTANCE.getSlimeDataConverter().applyDataFixers(sourceWorld);

        // Serialize and save to loader under new name
        byte[] clonedData = SlimeSerializer.serialize(dataFixed);
        loader.saveWorld(targetWorldName, clonedData);

        LOGGER.info("World '{}' cloned to '{}' in {}ms.", sourceWorldName, targetWorldName,
                System.currentTimeMillis() - start);

        // Return the cloned world (not loaded)
        return dataFixed;
    }

    /**
     * Utility method to register a <b>loaded</b> {@link SlimeWorld} with the
     * internal map (for {@link #getLoadedWorld} calls)
     *
     * @param world the world to register
     */
    private void registerWorld(SlimeWorldInstance world) {
        this.loadedWorlds.put(world.getName(), world);
    }

    public void onWorldUnload(String name) {
        this.loadedWorlds.remove(name);
    }
}


