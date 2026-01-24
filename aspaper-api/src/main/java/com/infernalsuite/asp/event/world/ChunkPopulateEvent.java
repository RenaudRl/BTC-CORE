package com.infernalsuite.asp.event.world;

import org.bukkit.Chunk;
import org.bukkit.event.HandlerList;
import org.bukkit.event.world.ChunkEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Event fired when a chunk is populated (features generated).
 */
public class ChunkPopulateEvent extends ChunkEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    public ChunkPopulateEvent(@NotNull Chunk chunk) {
        super(chunk);
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
