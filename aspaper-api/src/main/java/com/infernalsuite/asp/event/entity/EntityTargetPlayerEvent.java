package com.infernalsuite.asp.event.entity;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fires when an entity targets a player, allowing for custom aggro rules.
 */
public class EntityTargetPlayerEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private boolean cancelled = false;

    private final Entity entity;
    private final Player target;
    private final TargetReason reason;

    public EntityTargetPlayerEvent(@NotNull Entity entity, @NotNull Player target, @NotNull TargetReason reason) {
        this.entity = entity;
        this.target = target;
        this.reason = reason;
    }

    @NotNull
    public Entity getEntity() {
        return entity;
    }

    @NotNull
    public Player getTarget() {
        return target;
    }

    @NotNull
    public TargetReason getReason() {
        return reason;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    public enum TargetReason {
        CLOSEST_PLAYER,
        ATTACKED_BY,
        COLLISION,
        RANDOM,
        CUSTOM
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
