package net.minecraft.network.protocol.game;

import com.google.common.collect.Lists;
import com.mojang.datafixers.util.Pair;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

public class ClientboundSetEquipmentPacket implements Packet<ClientGamePacketListener> {
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundSetEquipmentPacket> STREAM_CODEC = Packet.codec(
        ClientboundSetEquipmentPacket::write, ClientboundSetEquipmentPacket::new
    );
    private static final byte CONTINUE_MASK = -128;
    private final int entity;
    private final List<Pair<EquipmentSlot, ItemStack>> slots;

    public ClientboundSetEquipmentPacket(int entity, List<Pair<EquipmentSlot, ItemStack>> slots) {
        this.entity = entity;
        this.slots = slots;
    }

    private ClientboundSetEquipmentPacket(RegistryFriendlyByteBuf buffer) {
        this.entity = buffer.readVarInt();
        this.slots = Lists.newArrayList();

        int _byte;
        do {
            _byte = buffer.readByte();
            EquipmentSlot equipmentSlot = EquipmentSlot.VALUES.get(_byte & 127);
            ItemStack itemStack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
            this.slots.add(Pair.of(equipmentSlot, itemStack));
        } while ((_byte & -128) != 0);
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(this.entity);
        int size = this.slots.size();

        for (int i = 0; i < size; i++) {
            Pair<EquipmentSlot, ItemStack> pair = this.slots.get(i);
            EquipmentSlot equipmentSlot = pair.getFirst();
            boolean flag = i != size - 1;
            int ordinal = equipmentSlot.ordinal();
            buffer.writeByte(flag ? ordinal | -128 : ordinal);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, pair.getSecond());
        }
    }

    @Override
    public PacketType<ClientboundSetEquipmentPacket> type() {
        return GamePacketTypes.CLIENTBOUND_SET_EQUIPMENT;
    }

    @Override
    public void handle(ClientGamePacketListener handler) {
        handler.handleSetEquipment(this);
    }

    public int getEntity() {
        return this.entity;
    }

    public List<Pair<EquipmentSlot, ItemStack>> getSlots() {
        return this.slots;
    }
}
