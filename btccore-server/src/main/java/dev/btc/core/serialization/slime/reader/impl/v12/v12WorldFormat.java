package dev.btc.core.serialization.slime.reader.impl.v12;

import dev.btc.core.serialization.slime.reader.impl.SimpleWorldFormat;

public interface v12WorldFormat {

    SimpleWorldFormat<dev.btc.core.api.world.SlimeWorld> FORMAT = new SimpleWorldFormat<>(data -> data, new v12SlimeWorldDeSerializer());

}

