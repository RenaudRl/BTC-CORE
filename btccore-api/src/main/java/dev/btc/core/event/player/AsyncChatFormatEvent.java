package dev.btc.core.event.player;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Event fired when a player chats, allowing for the format of the message to be changed.
 * <p>
 * This event is fired asynchronously.
 */
public class AsyncChatFormatEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private Component format;
    private Component message;
    private boolean cancelled;

    public AsyncChatFormatEvent(@NotNull Player player, @NotNull Component format, @NotNull Component message) {
        super(true); // Async
        this.player = player;
        this.format = format;
        this.message = message;
    }

    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * Gets the format of the message.
     *
     * @return the format
     */
    @NotNull
    public Component getFormat() {
        return format;
    }

    /**
     * Sets the format of the message.
     *
     * @param format the new format
     */
    public void setFormat(@NotNull Component format) {
        this.format = format;
    }

    /**
     * Gets the message to be displayed.
     *
     * @return the message
     */
    @NotNull
    public Component getMessage() {
        return message;
    }

    /**
     * Sets the message to be displayed.
     *
     * @param message the new message
     */
    public void setMessage(@NotNull Component message) {
        this.message = message;
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

