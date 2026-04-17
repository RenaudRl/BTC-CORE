package dev.btc.core.serialization.slime.reader.impl.v13;

import dev.btc.core.api.world.SlimeWorld;
import dev.btc.core.serialization.slime.reader.impl.SimpleWorldFormat;

public interface v13WorldFormat {

    SimpleWorldFormat<SlimeWorld> FORMAT = new SimpleWorldFormat<>(data -> data, new v13SlimeWorldDeSerializer());

}

