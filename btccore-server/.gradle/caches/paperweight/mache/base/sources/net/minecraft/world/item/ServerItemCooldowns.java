package net.minecraft.world.item;

import net.minecraft.network.protocol.game.ClientboundCooldownPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

public class ServerItemCooldowns extends ItemCooldowns {
    private final ServerPlayer player;

    public ServerItemCooldowns(ServerPlayer player) {
        this.player = player;
    }

    @Override
    protected void onCooldownStarted(Identifier group, int cooldown) {
        super.onCooldownStarted(group, cooldown);
        this.player.connection.send(new ClientboundCooldownPacket(group, cooldown));
    }

    @Override
    protected void onCooldownEnded(Identifier group) {
        super.onCooldownEnded(group);
        this.player.connection.send(new ClientboundCooldownPacket(group, 0));
    }
}
