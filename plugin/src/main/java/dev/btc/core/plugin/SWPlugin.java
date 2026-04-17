package dev.btc.core.plugin;

import dev.btc.core.plugin.commands.CommandManager;
import dev.btc.core.plugin.config.ConfigManager;
import dev.btc.core.plugin.config.WorldData;
import dev.btc.core.plugin.config.WorldsConfig;
import dev.btc.core.plugin.loader.LoaderManager;
import dev.btc.core.api.BTCCoreAPI;
import dev.btc.core.api.SlimeNMSBridge;
import dev.btc.core.api.exceptions.CorruptedWorldException;
import dev.btc.core.api.exceptions.NewerFormatException;
import dev.btc.core.api.exceptions.UnknownWorldException;
import dev.btc.core.api.loaders.SlimeLoader;
import dev.btc.core.api.world.SlimeWorld;
import dev.btc.core.api.world.properties.SlimePropertyMap;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

public class SWPlugin extends JavaPlugin {

    private static SWPlugin instance;
    private static final BTCCoreAPI ASP = BTCCoreAPI.instance();
    private static final int BSTATS_ID = 5419;

    private final Map<String, SlimeWorld> worldsToLoad = new HashMap<>();
    private LoaderManager loaderManager;

    public static SWPlugin getInstance() {
        return instance;
    }

    public LoaderManager getLoaderManager() {
        return loaderManager;
    }

    @Override
    public void onLoad() {
        instance = this;
        try {
            ConfigManager.initialize();
        } catch (NullPointerException | IOException ex) {
            getSLF4JLogger().error("Failed to load config files", ex);
            return;
        }

        this.loaderManager = new LoaderManager();

        List<String> erroredWorlds = loadWorlds();

        // Default world override
        try {
            Properties props = new Properties();

            props.load(new FileInputStream("server.properties"));
            String defaultWorldName = props.getProperty("level-name");

            if (erroredWorlds.contains(defaultWorldName)) {
                getSLF4JLogger().error("Shutting down server, as the default world could not be loaded.");
                Bukkit.getServer().shutdown();
            } else if (getServer().getAllowNether() && erroredWorlds.contains(defaultWorldName + "_nether")) {
                getSLF4JLogger().error("Shutting down server, as the default nether world could not be loaded.");
                Bukkit.getServer().shutdown();
            } else if (getServer().getAllowEnd() && erroredWorlds.contains(defaultWorldName + "_the_end")) {
                getSLF4JLogger().error("Shutting down server, as the default end world could not be loaded.");
                Bukkit.getServer().shutdown();
            }

            SlimeWorld defaultWorld = worldsToLoad.get(defaultWorldName);
            SlimeWorld netherWorld = getServer().getAllowNether() ? worldsToLoad.get(defaultWorldName + "_nether") : null;
            SlimeWorld endWorld = getServer().getAllowEnd() ? worldsToLoad.get(defaultWorldName + "_the_end") : null;

            SlimeNMSBridge.instance().setDefaultWorlds(defaultWorld, netherWorld, endWorld);
        } catch (IOException ex) {
            getSLF4JLogger().error("Failed to retrieve default world name", ex);
        }
    }

    @Override
    public void onEnable() {
        try {
            Metrics metrics = new Metrics(this, BSTATS_ID);
        } catch (Exception ex) {
            getSLF4JLogger().warn("Failed to initialize bStats Metrics. This is normal in test environments if relocation is not present.");
        }

        try {
            CommandManager commandManager = new CommandManager(this);
        } catch (Throwable ex) {
            getSLF4JLogger().warn("Failed to initialize CommandManager (Cloud). This is normal in MockBukkit environment as it lacks NMS reflection.");
        }

        worldsToLoad.values().stream()
                .filter(slimeWorld -> Objects.isNull(Bukkit.getWorld(slimeWorld.getName())))
                .forEach(slimeWorld -> {
                    try {
                        ASP.loadWorld(slimeWorld, true);
                    } catch (RuntimeException exception) {
                        getSLF4JLogger().error("Failed to load world: {}", slimeWorld.getName(), exception);
                    }
                });

        worldsToLoad.clear(); // Don't unnecessarily hog up memory
    }

    @Override
    public void onDisable() {
        WorldsConfig config = ConfigManager.getWorldConfig();

        for (Map.Entry<String, WorldData> entry : config.getWorlds().entrySet()) {
            SlimeWorld world = ASP.getLoadedWorld(entry.getKey());
            if(world == null) {
                continue;
            }

            if (!world.isReadOnly()) {
                try {
                    ASP.saveWorld(world); //Save the world sync
                } catch (RuntimeException | IOException ex) {
                    getLogger().log(Level.SEVERE, "Failed to save world " + world.getName(), ex);
                }
            }
            Bukkit.unloadWorld(world.getName(), false); //Unload without saving as we have just saved (if not read only)
        }
    }

    private List<String> loadWorlds() {
        List<String> erroredWorlds = new ArrayList<>();
        WorldsConfig config = ConfigManager.getWorldConfig();

        for (Map.Entry<String, WorldData> entry : config.getWorlds().entrySet()) {
            String worldName = entry.getKey();
            WorldData worldData = entry.getValue();

            if (worldData.isLoadOnStartup()) {
                try {
                    SlimeLoader loader = loaderManager.getLoader(worldData.getDataSource());

                    if (loader == null) {
                        throw new IllegalArgumentException("invalid data source " + worldData.getDataSource());
                    }

                    SlimePropertyMap propertyMap = worldData.toPropertyMap();
                    SlimeWorld world = ASP.readWorld(loader, worldName, worldData.isReadOnly(), propertyMap);

                    worldsToLoad.put(worldName, world);
                } catch (IllegalArgumentException | UnknownWorldException | NewerFormatException |
                         CorruptedWorldException | IOException ex) {
                    String message;

                    if (ex instanceof IllegalArgumentException) {
                        message = ex.getMessage();

                        //noinspection CallToPrintStackTrace
                        ex.printStackTrace();
                    } else if (ex instanceof UnknownWorldException) {
                        message = "world does not exist, are you sure you've set the correct data source?";
                    } else if (ex instanceof NewerFormatException) {
                        message = "world is serialized in a newer Slime Format version (" + ex.getMessage() + ") that this version of ASP does not understand.";
                    } else if (ex instanceof CorruptedWorldException) {
                        message = "world seems to be corrupted.";
                    } else {
                        message = "";

                        //noinspection CallToPrintStackTrace
                        ex.printStackTrace();
                    }

                    getSLF4JLogger().error("Failed to load world {}{}", worldName, message.isEmpty() ? "." : ": " + message);
                    erroredWorlds.add(worldName);
                }
            }
        }

        config.save();
        return erroredWorlds;
    }
}


