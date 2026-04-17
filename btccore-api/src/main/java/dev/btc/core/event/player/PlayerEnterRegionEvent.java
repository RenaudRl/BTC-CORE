package dev.btc.core.event.player;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fires when a player enters a new Folia region.
 */
public class PlayerEnterRegionEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private boolean cancelled = false;

    private final Player player;
    private final int fromRegionX;
    private final int fromRegionZ;
    private final int toRegionX;
    private final int toRegionZ;

    public PlayerEnterRegionEvent(@NotNull Player player, int fromRegionX, int fromRegionZ, int toRegionX, int toRegionZ) {
        this.player = player;
        this.fromRegionX = fromRegionX;
        this.fromRegionZ = fromRegionZ;
        this.toRegionX = toRegionX;
        this.toRegionZ = toRegionZ;
    }

    @NotNull
    public Player getPlayer() {
        return player;
    }

    public int getFromRegionX() {
        return fromRegionX;
    }

    public int getFromRegionZ() {
        return fromRegionZ;
    }

    public int getToRegionX() {
        return toRegionX;
    }

    public int getToRegionZ() {
        return toRegionZ;
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
        return HANDLERS;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

