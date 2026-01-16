package com.infernalsuite.asp.event.entity;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Bee;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.entity.EntityEvent;
import org.jetbrains.annotations.NotNull;

public class BeeFoundFlowerEvent extends EntityEvent implements Cancellable {
    private static final HandlerList handlers = new HandlerList();
    private boolean cancelled;
    private final Block flower;

    public BeeFoundFlowerEvent(@NotNull Bee what, @NotNull Block flower) {
        super(what);
        this.flower = flower;
    }

    @NotNull
    @Override
    public Bee getEntity() {
        return (Bee) super.getEntity();
    }

    @NotNull
    public Block getFlower() {
        return flower;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return handlers;
    }
}
