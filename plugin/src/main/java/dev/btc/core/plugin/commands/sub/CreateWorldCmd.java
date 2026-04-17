package dev.btc.core.plugin.commands.sub;


import dev.btc.core.api.exceptions.WorldAlreadyExistsException;
import dev.btc.core.api.world.SlimeWorld;
import dev.btc.core.api.world.properties.SlimeProperties;
import dev.btc.core.api.world.properties.SlimePropertyMap;
import dev.btc.core.plugin.commands.CommandManager;
import dev.btc.core.plugin.commands.SlimeCommand;
import dev.btc.core.plugin.commands.exception.MessageCommandException;
import dev.btc.core.plugin.commands.parser.NamedSlimeLoader;
import dev.btc.core.plugin.config.ConfigManager;
import dev.btc.core.plugin.config.WorldData;
import dev.btc.core.plugin.config.WorldsConfig;
import dev.btc.core.plugin.util.ExecutorUtil;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.annotations.*;
import org.incendo.cloud.paper.util.sender.Source;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Command to create a new Slime world.
 */
public class CreateWorldCmd extends SlimeCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger(CreateWorldCmd.class);

    /**
     * Constructs the create world command.
     *
     * @param commandManager the command manager.
     */
    public CreateWorldCmd(CommandManager commandManager) {
        super(commandManager);
    }

    /**
     * Creates a new empty Slime world.
     *
     * @param sender      the command source.
     * @param worldName   the name of the world to create.
     * @param loader      the data source to use.
     * @param biome       the default biome for the world.
     * @param environment the world environment.
     * @return a future that completes when the operation is done.
     */
    @Command("swp|aswm|swm create <world> <data-source>")
    @CommandDescription("Create an empty world")
    @Permission("swm.createworld")
    public CompletableFuture<Void> createWorld(
            Source sender,
            @Argument(value = "world") String worldName,
            @Argument(value = "data-source") NamedSlimeLoader loader,
            @Flag(value = "biome") @Nullable NamespacedKey biome,
            @Flag(value = "environment") @Nullable String environment
    ) {
        if (commandManager.getWorldsInUse().contains(worldName)) {
            throw new MessageCommandException(COMMAND_PREFIX.append(
                    Component.text("World " + worldName + " is already being used on another command! Wait some time and try again.")).color(NamedTextColor.RED)
            );
        }

        World world = Bukkit.getWorld(worldName);

        if (world != null) {
            throw new MessageCommandException(COMMAND_PREFIX.append(
                    Component.text("World " + worldName + " already exists!")).color(NamedTextColor.RED)
            );
        }

        WorldsConfig config = ConfigManager.getWorldConfig();

        if (config.getWorlds().containsKey(worldName)) {
            throw new MessageCommandException(COMMAND_PREFIX.append(
                    Component.text("There is already a world called " + worldName + " inside the worlds config file.")).color(NamedTextColor.RED)
            );
        }

        NamespacedKey defaultBiome = Objects.requireNonNull(NamespacedKey.fromString(SlimeProperties.DEFAULT_BIOME.getDefaultValue()));
        Biome actualBiome = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME)
                .get(biome == null ? defaultBiome : biome);

        if(actualBiome == null) {
            throw new MessageCommandException(COMMAND_PREFIX.append(
                    Component.text("Biome " + biome + "does not exist")).color(NamedTextColor.RED)
            );
        }

        if(environment != null && !SlimeProperties.ENVIRONMENT.applyValidator(environment)) {
            throw new MessageCommandException(COMMAND_PREFIX.append(
                    Component.text("Environment " + environment + " is not a valid environment. Valid options are: normal, nether, the_end")).color(NamedTextColor.RED)
            );
        }

        commandManager.getWorldsInUse().add(worldName);
        sender.source().sendMessage(COMMAND_PREFIX.append(
                Component.text("Creating empty world ").color(NamedTextColor.GRAY)
                        .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                        .append(Component.text("...")).color(NamedTextColor.GRAY)
        ));

        // It's best to create the world async, and then just go back to the server thread and add it to the world list
        return CompletableFuture.runAsync(() -> {

            try {
                long start = System.currentTimeMillis();

                if (loader.slimeLoader().worldExists(worldName)) {
                    throw new WorldAlreadyExistsException("World already exists");
                }

                WorldData worldData = new WorldData();
                worldData.setSpawn("0, 64, 0");
                worldData.setDataSource(loader.name());
                worldData.setDefaultBiome(actualBiome.key().asString());
                if(environment != null) {
                    worldData.setEnvironment(environment);
                }

                SlimePropertyMap propertyMap = worldData.toPropertyMap();
                SlimeWorld slimeWorld = asp.createEmptyWorld(worldName, false, propertyMap, loader.slimeLoader());
                asp.saveWorld(slimeWorld);

                ExecutorUtil.runSyncAndWait(plugin, () -> {
                    try {
                        asp.loadWorld(slimeWorld, true);

                        // Bedrock block
                        Location location = new Location(Bukkit.getWorld(worldName), 0, 61, 0);
                        location.getBlock().setType(Material.BEDROCK);

                        // Config
                        config.getWorlds().put(worldName, worldData);
                    } catch (IllegalArgumentException ex) {
                        throw new MessageCommandException(COMMAND_PREFIX.append(
                                Component.text("Failed to create world " + worldName + ": " + ex.getMessage() + ".").color(NamedTextColor.RED)
                        ));
                    }
                });
                config.save();

                sender.source().sendMessage(COMMAND_PREFIX.append(
                        Component.text("World ").color(NamedTextColor.GREEN)
                                .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                                .append(Component.text(" created in " + (System.currentTimeMillis() - start) + "ms!").color(NamedTextColor.GREEN))
                ));
            } catch (WorldAlreadyExistsException ex) {
                throw new MessageCommandException(COMMAND_PREFIX.append(
                        Component.text("Failed to create world " + worldName + ": world already exists (using data source '" + loader.name() + "').").color(NamedTextColor.RED)
                ));
            } catch (IOException ex) {
                LOGGER.error("Failed to create world {}:", worldName, ex);
                throw new MessageCommandException(COMMAND_PREFIX.append(
                        Component.text("Failed to create world " + worldName + ". Take a look at the server console for more information.").color(NamedTextColor.RED)
                ));
            } finally {
                commandManager.getWorldsInUse().remove(worldName);
            }
        });
    }
}


