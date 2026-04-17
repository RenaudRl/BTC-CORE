package dev.btc.core.api.world;

import dev.btc.core.api.loaders.SlimeLoader;
import dev.btc.core.api.world.properties.SlimePropertyMap;
import net.kyori.adventure.nbt.BinaryTag;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentMap;

/*
 * Represents a loaded SlimeWorld. This world is synchronized with the state of the bukkit world.
 */
public interface SlimeWorldInstance extends SlimeWorld {

    /**
     * Returns the bukkit instance of the loaded world.
     *
     * @return The bukkit world.
     */
    @NotNull World getBukkitWorld();

    SlimeWorld getSerializableCopy();

}

