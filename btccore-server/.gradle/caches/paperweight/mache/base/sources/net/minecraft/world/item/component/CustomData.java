package net.minecraft.world.item.component;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

public final class CustomData {
    public static final CustomData EMPTY = new CustomData(new CompoundTag());
    public static final Codec<CompoundTag> COMPOUND_TAG_CODEC = Codec.withAlternative(CompoundTag.CODEC, TagParser.FLATTENED_CODEC);
    public static final Codec<CustomData> CODEC = COMPOUND_TAG_CODEC.xmap(CustomData::new, data -> data.tag);
    @Deprecated
    public static final StreamCodec<ByteBuf, CustomData> STREAM_CODEC = ByteBufCodecs.COMPOUND_TAG.map(CustomData::new, data -> data.tag);
    private final CompoundTag tag;

    private CustomData(CompoundTag tag) {
        this.tag = tag;
    }

    public static CustomData of(CompoundTag tag) {
        return new CustomData(tag.copy());
    }

    public boolean matchedBy(CompoundTag tag) {
        return NbtUtils.compareNbt(tag, this.tag, true);
    }

    public static void update(DataComponentType<CustomData> component, ItemStack stack, Consumer<CompoundTag> updater) {
        CustomData customData = stack.getOrDefault(component, EMPTY).update(updater);
        if (customData.tag.isEmpty()) {
            stack.remove(component);
        } else {
            stack.set(component, customData);
        }
    }

    public static void set(DataComponentType<CustomData> component, ItemStack stack, CompoundTag tag) {
        if (!tag.isEmpty()) {
            stack.set(component, of(tag));
        } else {
            stack.remove(component);
        }
    }

    public CustomData update(Consumer<CompoundTag> updater) {
        CompoundTag compoundTag = this.tag.copy();
        updater.accept(compoundTag);
        return new CustomData(compoundTag);
    }

    public boolean isEmpty() {
        return this.tag.isEmpty();
    }

    public CompoundTag copyTag() {
        return this.tag.copy();
    }

    @Override
    public boolean equals(Object other) {
        return other == this || other instanceof CustomData customData && this.tag.equals(customData.tag);
    }

    @Override
    public int hashCode() {
        return this.tag.hashCode();
    }

    @Override
    public String toString() {
        return this.tag.toString();
    }
}
