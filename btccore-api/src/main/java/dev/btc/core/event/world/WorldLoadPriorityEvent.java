package dev.btc.core.event.world;

import org.bukkit.World;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.world.WorldEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Event fired when a world is about to be loaded, allowing plugins to set its loading priority.
 * This can be used to ensure critical worlds (like lobbies or active dungeon instances) are processed first.
 */
public class WorldLoadPriorityEvent extends WorldEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private int priority;

    public WorldLoadPriorityEvent(@NotNull World world, int defaultPriority) {
        super(world);
        this.priority = defaultPriority;
    }

    /**
     * Gets the current load priority.
     * Higher values indicate higher priority.
     *
     * @return the priority
     */
    public int getPriority() {
        return priority;
    }

    /**
     * Sets the load priority.
     *
     * @param priority the new priority (higher is better)
     */
    public void setPriority(int priority) {
        this.priority = priority;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

