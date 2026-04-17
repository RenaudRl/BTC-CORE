package dev.btc.core.serialization.slime.reader.impl.v10;

public interface v10WorldFormat {

    // Latest, returns same
    dev.btc.core.serialization.slime.reader.impl.SimpleWorldFormat<dev.btc.core.api.world.SlimeWorld> FORMAT = new dev.btc.core.serialization.slime.reader.impl.SimpleWorldFormat<>(data -> data, new v10SlimeWorldDeSerializer());

}

