package net.minecraft.network.protocol.game;

import com.google.common.base.MoreObjects;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.PropertyMap;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.Optionull;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.RemoteChatSession;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;

public class ClientboundPlayerInfoUpdatePacket implements Packet<ClientGamePacketListener> {
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundPlayerInfoUpdatePacket> STREAM_CODEC = Packet.codec(
        ClientboundPlayerInfoUpdatePacket::write, ClientboundPlayerInfoUpdatePacket::new
    );
    private final EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions;
    private final List<ClientboundPlayerInfoUpdatePacket.Entry> entries;

    public ClientboundPlayerInfoUpdatePacket(EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions, Collection<ServerPlayer> players) {
        this.actions = actions;
        this.entries = players.stream().map(ClientboundPlayerInfoUpdatePacket.Entry::new).toList();
    }

    public ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action action, ServerPlayer player) {
        this.actions = EnumSet.of(action);
        this.entries = List.of(new ClientboundPlayerInfoUpdatePacket.Entry(player));
    }

    public static ClientboundPlayerInfoUpdatePacket createPlayerInitializing(Collection<ServerPlayer> players) {
        EnumSet<ClientboundPlayerInfoUpdatePacket.Action> set = EnumSet.of(
            ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
            ClientboundPlayerInfoUpdatePacket.Action.INITIALIZE_CHAT,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_HAT,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LIST_ORDER
        );
        return new ClientboundPlayerInfoUpdatePacket(set, players);
    }

    private ClientboundPlayerInfoUpdatePacket(RegistryFriendlyByteBuf buffer) {
        this.actions = buffer.readEnumSet(ClientboundPlayerInfoUpdatePacket.Action.class);
        this.entries = buffer.readList(buffer1 -> {
            ClientboundPlayerInfoUpdatePacket.EntryBuilder entryBuilder = new ClientboundPlayerInfoUpdatePacket.EntryBuilder(buffer1.readUUID());

            for (ClientboundPlayerInfoUpdatePacket.Action action : this.actions) {
                action.reader.read(entryBuilder, (RegistryFriendlyByteBuf)buffer1);
            }

            return entryBuilder.build();
        });
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeEnumSet(this.actions, ClientboundPlayerInfoUpdatePacket.Action.class);
        buffer.writeCollection(this.entries, (buffer1, entry) -> {
            buffer1.writeUUID(entry.profileId());

            for (ClientboundPlayerInfoUpdatePacket.Action action : this.actions) {
                action.writer.write((RegistryFriendlyByteBuf)buffer1, entry);
            }
        });
    }

    @Override
    public PacketType<ClientboundPlayerInfoUpdatePacket> type() {
        return GamePacketTypes.CLIENTBOUND_PLAYER_INFO_UPDATE;
    }

    @Override
    public void handle(ClientGamePacketListener handler) {
        handler.handlePlayerInfoUpdate(this);
    }

    public EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions() {
        return this.actions;
    }

    public List<ClientboundPlayerInfoUpdatePacket.Entry> entries() {
        return this.entries;
    }

    public List<ClientboundPlayerInfoUpdatePacket.Entry> newEntries() {
        return this.actions.contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER) ? this.entries : List.of();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("actions", this.actions).add("entries", this.entries).toString();
    }

    public static enum Action {
        ADD_PLAYER((entryBuilder, buffer) -> {
            String string = ByteBufCodecs.PLAYER_NAME.decode(buffer);
            PropertyMap propertyMap = ByteBufCodecs.GAME_PROFILE_PROPERTIES.decode(buffer);
            entryBuilder.profile = new GameProfile(entryBuilder.profileId, string, propertyMap);
        }, (buffer, entry) -> {
            GameProfile gameProfile = Objects.requireNonNull(entry.profile());
            ByteBufCodecs.PLAYER_NAME.encode(buffer, gameProfile.name());
            ByteBufCodecs.GAME_PROFILE_PROPERTIES.encode(buffer, gameProfile.properties());
        }),
        INITIALIZE_CHAT(
            (entryBuilder, buffer) -> entryBuilder.chatSession = buffer.readNullable(RemoteChatSession.Data::read),
            (buffer, entry) -> buffer.writeNullable(entry.chatSession, RemoteChatSession.Data::write)
        ),
        UPDATE_GAME_MODE(
            (entryBuilder, buffer) -> entryBuilder.gameMode = GameType.byId(buffer.readVarInt()),
            (buffer, entry) -> buffer.writeVarInt(entry.gameMode().getId())
        ),
        UPDATE_LISTED((entryBuilder, buffer) -> entryBuilder.listed = buffer.readBoolean(), (buffer, entry) -> buffer.writeBoolean(entry.listed())),
        UPDATE_LATENCY((entryBuilder, buffer) -> entryBuilder.latency = buffer.readVarInt(), (buffer, entry) -> buffer.writeVarInt(entry.latency())),
        UPDATE_DISPLAY_NAME(
            (entryBuilder, buffer) -> entryBuilder.displayName = FriendlyByteBuf.readNullable(buffer, ComponentSerialization.TRUSTED_STREAM_CODEC),
            (buffer, entry) -> FriendlyByteBuf.writeNullable(buffer, entry.displayName(), ComponentSerialization.TRUSTED_STREAM_CODEC)
        ),
        UPDATE_LIST_ORDER((entryBuilder, buffer) -> entryBuilder.listOrder = buffer.readVarInt(), (buffer, entry) -> buffer.writeVarInt(entry.listOrder)),
        UPDATE_HAT((entryBuilder, buffer) -> entryBuilder.showHat = buffer.readBoolean(), (buffer, entry) -> buffer.writeBoolean(entry.showHat));

        final ClientboundPlayerInfoUpdatePacket.Action.Reader reader;
        final ClientboundPlayerInfoUpdatePacket.Action.Writer writer;

        private Action(final ClientboundPlayerInfoUpdatePacket.Action.Reader reader, final ClientboundPlayerInfoUpdatePacket.Action.Writer writer) {
            this.reader = reader;
            this.writer = writer;
        }

        public interface Reader {
            void read(ClientboundPlayerInfoUpdatePacket.EntryBuilder entryBuilder, RegistryFriendlyByteBuf buffer);
        }

        public interface Writer {
            void write(RegistryFriendlyByteBuf buffer, ClientboundPlayerInfoUpdatePacket.Entry entry);
        }
    }

    public record Entry(
        UUID profileId,
        @Nullable GameProfile profile,
        boolean listed,
        int latency,
        GameType gameMode,
        @Nullable Component displayName,
        boolean showHat,
        int listOrder,
        RemoteChatSession.@Nullable Data chatSession
    ) {
        Entry(ServerPlayer player) {
            this(
                player.getUUID(),
                player.getGameProfile(),
                true,
                player.connection.latency(),
                player.gameMode(),
                player.getTabListDisplayName(),
                player.isModelPartShown(PlayerModelPart.HAT),
                player.getTabListOrder(),
                Optionull.map(player.getChatSession(), RemoteChatSession::asData)
            );
        }
    }

    static class EntryBuilder {
        final UUID profileId;
        @Nullable GameProfile profile;
        boolean listed;
        int latency;
        GameType gameMode = GameType.DEFAULT_MODE;
        @Nullable Component displayName;
        boolean showHat;
        int listOrder;
        RemoteChatSession.@Nullable Data chatSession;

        EntryBuilder(UUID profileId) {
            this.profileId = profileId;
        }

        ClientboundPlayerInfoUpdatePacket.Entry build() {
            return new ClientboundPlayerInfoUpdatePacket.Entry(
                this.profileId, this.profile, this.listed, this.latency, this.gameMode, this.displayName, this.showHat, this.listOrder, this.chatSession
            );
        }
    }
}
