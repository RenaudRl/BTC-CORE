package com.infernalsuite.asp.event.player;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

public class PlayerAFKEvent extends PlayerEvent {
    private static final HandlerList handlers = new HandlerList();
    private boolean setAfk;
    private boolean shouldKick;
    private boolean broadcast;

    public PlayerAFKEvent(@NotNull Player player, boolean setAfk, boolean shouldKick, boolean broadcast) {
        super(player);
        this.setAfk = setAfk;
        this.shouldKick = shouldKick;
        this.broadcast = broadcast;
    }

    public boolean isGoingAfk() {
        return setAfk;
    }

    public void setGoingAfk(boolean setAfk) {
        this.setAfk = setAfk;
    }

    public boolean shouldKick() {
        return shouldKick;
    }

    public void setShouldKick(boolean shouldKick) {
        this.shouldKick = shouldKick;
    }

    public boolean shouldBroadcast() {
        return broadcast;
    }

    public void setShouldBroadcast(boolean broadcast) {
        this.broadcast = broadcast;
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
