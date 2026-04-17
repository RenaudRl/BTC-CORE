package dev.btc.core.plugin;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class WarpPerformanceTest {

    private ServerMock server;
    private static final int PLAYER_COUNT = 10;
    private static final int WORLD_COUNT = 5;
    private SWPlugin plugin;
    private static final Logger LOGGER = Logger.getLogger("WarpTest");

    @BeforeEach
    public void setUp() {
        try {
            Class.forName("dev.btc.core.api.world.SlimeWorld");
            LOGGER.info("SlimeWorld class found on classpath!");
        } catch (ClassNotFoundException e) {
            LOGGER.severe("SlimeWorld class NOT found on classpath!");
        }
        server = MockBukkit.mock();
        plugin = MockBukkit.load(SWPlugin.class);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void testWarpPerformance() {
        int playerCount = 50;
        int worldCount = 5;

        List<World> worlds = new ArrayList<>();
        for (int i = 0; i < worldCount; i++) {
            worlds.add(server.addSimpleWorld("island_" + i));
        }

        List<PlayerMock> players = new ArrayList<>();
        for (int i = 0; i < playerCount; i++) {
            players.add(server.addPlayer("Player" + i));
        }

        LOGGER.info("Starting warp test for " + playerCount + " players across " + worldCount + " worlds...");

        long startTime = System.currentTimeMillis();

        List<CompletableFuture<Void>> warps = new ArrayList<>();

        for (int i = 0; i < playerCount; i++) {
            PlayerMock player = players.get(i);
            World targetWorld = worlds.get(i % worldCount);

            // Simulating parallel warping if the server supports it properly
            // In MockBukkit, teleport is synchronous, but we use CompletableFuture to
            // simulate load
            warps.add(CompletableFuture.runAsync(() -> {
                player.teleport(targetWorld.getSpawnLocation());
            }, ForkJoinPool.commonPool()));
        }

        CompletableFuture.allOf(warps.toArray(new CompletableFuture[0])).join();

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        LOGGER.info("Warp test completed in " + duration + "ms for 50 players.");

        for (PlayerMock player : players) {
            assertNotNull(player.getWorld());
        }
    }
}

