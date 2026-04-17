package dev.btc.core.serialization.slime.reader.impl.v1_9;

public interface v1_9WorldFormat {

    dev.btc.core.serialization.slime.reader.impl.SimpleWorldFormat<v1_9SlimeWorld> FORMAT = new dev.btc.core.serialization.slime.reader.impl.SimpleWorldFormat<>(new v1_v9SlimeConverter(), new v1_9SlimeWorldDeserializer());

}

