package dev.btc.core.plugin.commands.sub;


import dev.btc.core.api.loaders.SlimeLoader;
import dev.btc.core.api.world.SlimeWorld;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.paper.util.sender.Source;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Command to clone an existing Slime world.
 */
public class CloneWorldCmd extends dev.btc.core.plugin.commands.SlimeCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(CloneWorldCmd.class);

    /**
     * Constructs the clone world command.
     *
     * @param commandManager the command manager.
     */
    public CloneWorldCmd(dev.btc.core.plugin.commands.CommandManager commandManager) {
        super(commandManager);
    }

    /**
     * Clones a world from a template.
     *
     * @param sender        the command source.
     * @param templateWorld the world to use as template.
     * @param worldName     the name of the new world.
     * @param slimeLoader   the optional data source for the new world.
     * @return a future that completes when the operation is done.
     */
    @Command("swp|aswm|swm clone-world <template-world> <world-name> [new-data-source]")
    @CommandDescription("Clones a world")
    @Permission("swm.cloneworld")
    public CompletableFuture<Void> cloneWorld(Source sender, @Argument(value = "template-world") dev.btc.core.plugin.commands.parser.NamedWorldData templateWorld,
                                              @Argument(value = "world-name") String worldName,
                                              @Argument(value = "new-data-source") @Nullable dev.btc.core.plugin.commands.parser.NamedSlimeLoader slimeLoader) {
        World world = Bukkit.getWorld(worldName);

        if (world != null) {
            throw new dev.btc.core.plugin.commands.exception.MessageCommandException(COMMAND_PREFIX.append(
                    Component.text("World " + worldName + " is already loaded!")).color(NamedTextColor.RED)
            );
        }

        dev.btc.core.plugin.config.WorldsConfig config = dev.btc.core.plugin.config.ConfigManager.getWorldConfig();
        dev.btc.core.plugin.config.WorldData worldData = templateWorld.worldData();

        if (templateWorld.name().equals(worldName)) {
            throw new dev.btc.core.plugin.commands.exception.MessageCommandException(COMMAND_PREFIX.append(
                    Component.text("The template world name cannot be the same as the cloned world one!")).color(NamedTextColor.RED)
            );
        }

        if (commandManager.getWorldsInUse().contains(worldName)) {
            throw new dev.btc.core.plugin.commands.exception.MessageCommandException(COMMAND_PREFIX.append(
                    Component.text("World " + worldName + " is already being used on another command! Wait some time and try again.")).color(NamedTextColor.RED)
            );
        }

        SlimeLoader initLoader = plugin.getLoaderManager().getLoader(worldData.getDataSource());
        SlimeLoader dataSource = slimeLoader == null ? initLoader : slimeLoader.slimeLoader();

        commandManager.getWorldsInUse().add(worldName);
        sender.source().sendMessage(COMMAND_PREFIX.append(
                Component.text("Creating world ").color(NamedTextColor.GRAY)
                        .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                        .append(Component.text(" using ").color(NamedTextColor.GRAY))
                        .append(Component.text(templateWorld.name()).color(NamedTextColor.YELLOW))
                        .append(Component.text(" as a template...").color(NamedTextColor.GRAY))
        ));

        // It's best to read the world async, and then just go back to the server thread and add it to the world list
        return CompletableFuture.runAsync(() -> {
            try {
                long start = System.currentTimeMillis();

                SlimeWorld slimeWorld = getWorldReadyForCloning(templateWorld.name(), initLoader, templateWorld.worldData().toPropertyMap());
                SlimeWorld finalSlimeWorld = slimeWorld.clone(worldName, dataSource);

                dev.btc.core.plugin.util.ExecutorUtil.runSyncAndWait(plugin, () -> {
                    try {
                        asp.loadWorld(finalSlimeWorld, true);

                        config.getWorlds().put(worldName, worldData);
                    } catch (IllegalArgumentException ex) {
                        throw new dev.btc.core.plugin.commands.exception.MessageCommandException(COMMAND_PREFIX.append(
                                Component.text("Failed to generate world " + worldName + ": " + ex.getMessage() + ".").color(NamedTextColor.RED)
                        ));
                    }
                    sender.source().sendMessage(COMMAND_PREFIX.append(
                            Component.text("World ").color(NamedTextColor.GREEN)
                                    .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                                    .append(Component.text(" loaded and generated in " + (System.currentTimeMillis() - start) + "ms!").color(NamedTextColor.GREEN))
                    ));
                });
                config.save();
            } catch (dev.btc.core.api.exceptions.WorldAlreadyExistsException ex) {
                throw new dev.btc.core.plugin.commands.exception.MessageCommandException(COMMAND_PREFIX.append(
                        Component.text("There is already a world called " + worldName + " stored in " + dataSource + ".").color(NamedTextColor.RED)
                ));
            } catch (dev.btc.core.api.exceptions.CorruptedWorldException ex) {
                LOGGER.error("Failed to load world {}: world seems to be corrupted.", templateWorld.name(), ex);
                throw new dev.btc.core.plugin.commands.exception.MessageCommandException(COMMAND_PREFIX.append(
                        Component.text("Failed to load world " + templateWorld.name() + ": world seems to be corrupted.").color(NamedTextColor.RED)
                ));
            } catch (dev.btc.core.api.exceptions.NewerFormatException ex) {
                throw new dev.btc.core.plugin.commands.exception.MessageCommandException(COMMAND_PREFIX.append(
                        Component.text("Failed to load world " + templateWorld.name() + ": this world was serialized with a newer version of the Slime Format (" + ex.getMessage() + ") that SWM cannot understand.").color(NamedTextColor.RED)
                ));
            } catch (dev.btc.core.api.exceptions.UnknownWorldException ex) {
                throw new dev.btc.core.plugin.commands.exception.MessageCommandException(COMMAND_PREFIX.append(
                        Component.text("Failed to load world " + templateWorld.name() + ": world could not be found (using data source '" + worldData.getDataSource() + "').").color(NamedTextColor.RED)
                ));
            } catch (IllegalArgumentException ex) {
                throw new dev.btc.core.plugin.commands.exception.MessageCommandException(COMMAND_PREFIX.append(
                        Component.text("Failed to load world " + templateWorld.name() + ": " + ex.getMessage()).color(NamedTextColor.RED)
                ));
            } catch (IOException ex) {
                LOGGER.error("Failed to load world {}.", templateWorld.name(), ex);
                throw new dev.btc.core.plugin.commands.exception.MessageCommandException(COMMAND_PREFIX.append(
                        Component.text("Failed to load world " + templateWorld.name() + ". Take a look at the server console for more information.").color(NamedTextColor.RED)
                ));
            } finally {
                commandManager.getWorldsInUse().remove(worldName);
            }
        });
    }
}


