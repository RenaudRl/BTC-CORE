package net.minecraft.world.level.chunk;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.IdMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import org.apache.commons.lang3.Validate;
import org.jspecify.annotations.Nullable;

public class SingleValuePalette<T> implements Palette<T> {
    private @Nullable T value;

    public SingleValuePalette(List<T> values) {
        if (!values.isEmpty()) {
            Validate.isTrue(values.size() <= 1, "Can't initialize SingleValuePalette with %d values.", (long)values.size());
            this.value = values.getFirst();
        }
    }

    public static <A> Palette<A> create(int bits, List<A> values) {
        return new SingleValuePalette<>(values);
    }

    @Override
    public int idFor(T state, PaletteResize<T> resizeHandler) {
        if (this.value != null && this.value != state) {
            return resizeHandler.onResize(1, state);
        } else {
            this.value = state;
            return 0;
        }
    }

    @Override
    public boolean maybeHas(Predicate<T> filter) {
        if (this.value == null) {
            throw new IllegalStateException("Use of an uninitialized palette");
        } else {
            return filter.test(this.value);
        }
    }

    @Override
    public T valueFor(int id) {
        if (this.value != null && id == 0) {
            return this.value;
        } else {
            throw new IllegalStateException("Missing Palette entry for id " + id + ".");
        }
    }

    @Override
    public void read(FriendlyByteBuf buffer, IdMap<T> map) {
        this.value = map.byIdOrThrow(buffer.readVarInt());
    }

    @Override
    public void write(FriendlyByteBuf buffer, IdMap<T> map) {
        if (this.value == null) {
            throw new IllegalStateException("Use of an uninitialized palette");
        } else {
            buffer.writeVarInt(map.getId(this.value));
        }
    }

    @Override
    public int getSerializedSize(IdMap<T> map) {
        if (this.value == null) {
            throw new IllegalStateException("Use of an uninitialized palette");
        } else {
            return VarInt.getByteSize(map.getId(this.value));
        }
    }

    @Override
    public int getSize() {
        return 1;
    }

    @Override
    public Palette<T> copy() {
        if (this.value == null) {
            throw new IllegalStateException("Use of an uninitialized palette");
        } else {
            return this;
        }
    }
}
