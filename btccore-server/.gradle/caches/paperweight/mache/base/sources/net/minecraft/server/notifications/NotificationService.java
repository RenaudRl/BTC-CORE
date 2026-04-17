package net.minecraft.server.notifications;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.IpBanListEntry;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.server.players.UserBanListEntry;
import net.minecraft.world.level.gamerules.GameRule;

public interface NotificationService {
    void playerJoined(ServerPlayer player);

    void playerLeft(ServerPlayer player);

    void serverStarted();

    void serverShuttingDown();

    void serverSaveStarted();

    void serverSaveCompleted();

    void serverActivityOccured();

    void playerOped(ServerOpListEntry entry);

    void playerDeoped(ServerOpListEntry entry);

    void playerAddedToAllowlist(NameAndId nameAndId);

    void playerRemovedFromAllowlist(NameAndId nameAndId);

    void ipBanned(IpBanListEntry entry);

    void ipUnbanned(String ip);

    void playerBanned(UserBanListEntry entry);

    void playerUnbanned(NameAndId nameAndId);

    <T> void onGameRuleChanged(GameRule<T> rule, T value);

    void statusHeartbeat();
}
