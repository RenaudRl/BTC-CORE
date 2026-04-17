package dev.btc.core.serialization.slime.reader.impl.v1_9;

import dev.btc.core.api.SlimeDataConverter;

public interface Upgrade {

    void upgrade(v1_9SlimeWorld world, SlimeDataConverter converter);

}
