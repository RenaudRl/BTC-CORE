package dev.btc.core.plugin.commands;

import dev.btc.core.api.BTCCoreAPI;
import dev.btc.core.api.exceptions.CorruptedWorldException;
import dev.btc.core.api.exceptions.NewerFormatException;
import dev.btc.core.api.exceptions.UnknownWorldException;
import dev.btc.core.api.loaders.SlimeLoader;
import dev.btc.core.api.world.SlimeWorld;
import dev.btc.core.api.world.properties.SlimePropertyMap;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.io.IOException;

public class SlimeCommand {
    public static final TextComponent COMMAND_PREFIX = LegacyComponentSerializer.legacySection().deserialize(
            "Â§9Â§lSWP Â§7Â§l>> Â§r"
    );

    protected final CommandManager commandManager;
    protected final dev.btc.core.plugin.SWPlugin plugin;
    protected final BTCCoreAPI asp = BTCCoreAPI.instance();

    public SlimeCommand(CommandManager commandManager) {
        this.commandManager = commandManager;
        this.plugin = commandManager.getPlugin();
    }

    // This method is here so that we can easily change the behavior in the future
    protected SlimeWorld getWorldReadyForCloning(String name, SlimeLoader loader, SlimePropertyMap propertyMap) throws CorruptedWorldException, NewerFormatException, UnknownWorldException, IOException {
        return asp.readWorld(loader, name, false, propertyMap);
    }
}


