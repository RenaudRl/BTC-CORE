package dev.btc.core.serialization.slime.reader.impl.v11;

public interface v11WorldFormat {

    dev.btc.core.serialization.slime.reader.impl.SimpleWorldFormat<dev.btc.core.api.world.SlimeWorld> FORMAT = new dev.btc.core.serialization.slime.reader.impl.SimpleWorldFormat<>(data -> data, new v11SlimeWorldDeSerializer());

}

