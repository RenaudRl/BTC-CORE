package net.minecraft.server.level;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

public record TicketType(long timeout, @TicketType.Flags int flags) {
    public static final long NO_TIMEOUT = 0L;
    public static final int FLAG_PERSIST = 1;
    public static final int FLAG_LOADING = 2;
    public static final int FLAG_SIMULATION = 4;
    public static final int FLAG_KEEP_DIMENSION_ACTIVE = 8;
    public static final int FLAG_CAN_EXPIRE_IF_UNLOADED = 16;
    public static final TicketType PLAYER_SPAWN = register("player_spawn", 20L, FLAG_LOADING);
    public static final TicketType SPAWN_SEARCH = register("spawn_search", 1L, FLAG_LOADING);
    public static final TicketType DRAGON = register("dragon", NO_TIMEOUT, FLAG_LOADING | FLAG_SIMULATION);
    public static final TicketType PLAYER_LOADING = register("player_loading", NO_TIMEOUT, FLAG_LOADING);
    public static final TicketType PLAYER_SIMULATION = register("player_simulation", NO_TIMEOUT, FLAG_SIMULATION | FLAG_KEEP_DIMENSION_ACTIVE);
    public static final TicketType FORCED = register("forced", NO_TIMEOUT, FLAG_PERSIST | FLAG_LOADING | FLAG_SIMULATION | FLAG_KEEP_DIMENSION_ACTIVE);
    public static final TicketType PORTAL = register("portal", 300L, FLAG_PERSIST | FLAG_LOADING | FLAG_SIMULATION | FLAG_KEEP_DIMENSION_ACTIVE);
    public static final TicketType ENDER_PEARL = register("ender_pearl", 40L, FLAG_LOADING | FLAG_SIMULATION | FLAG_KEEP_DIMENSION_ACTIVE);
    public static final TicketType UNKNOWN = register("unknown", 1L, FLAG_CAN_EXPIRE_IF_UNLOADED | FLAG_LOADING);

    private static TicketType register(String name, long timeout, @TicketType.Flags int flags) {
        return Registry.register(BuiltInRegistries.TICKET_TYPE, name, new TicketType(timeout, flags));
    }

    public boolean persist() {
        return (this.flags & FLAG_PERSIST) != 0;
    }

    public boolean doesLoad() {
        return (this.flags & FLAG_LOADING) != 0;
    }

    public boolean doesSimulate() {
        return (this.flags & FLAG_SIMULATION) != 0;
    }

    public boolean shouldKeepDimensionActive() {
        return (this.flags & FLAG_KEEP_DIMENSION_ACTIVE) != 0;
    }

    public boolean canExpireIfUnloaded() {
        return (this.flags & FLAG_CAN_EXPIRE_IF_UNLOADED) != 0;
    }

    public boolean hasTimeout() {
        return this.timeout != 0L;
    }

    @Retention(RetentionPolicy.CLASS)
    @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE, ElementType.METHOD, ElementType.TYPE_USE})
    public @interface Flags {
    }
}
