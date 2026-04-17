package dev.btc.core.serialization;

import dev.btc.core.api.world.SlimeWorld;

public interface SlimeWorldReader<T> {

    SlimeWorld readFromData(T data);
}

