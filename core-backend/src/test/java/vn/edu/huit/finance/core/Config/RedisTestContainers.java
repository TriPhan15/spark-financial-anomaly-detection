package vn.edu.huit.finance.core.Config;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * A real Redis for the integration tests, started the way the redis service of docker-compose.yml runs it: a password,
 * a memory cap, no persistence, and KEYS, FLUSHALL and FLUSHDB renamed away, so that a test that clears a cache also
 * proves the clear never used KEYS. The test classes that use it are skipped where Docker is not available.
 */
public final class RedisTestContainers {

    /** Keep in step with the redis service of docker-compose.yml. */
    public static final String IMAGE = "redis:8.10-alpine";
    public static final String PASSWORD = "integration-test-only";
    public static final int PORT = 6379;

    private RedisTestContainers() {
    }

    public static GenericContainer<?> newRedis() {
        return new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withExposedPorts(PORT)
                .withCommand("redis-server",
                        "--requirepass", PASSWORD,
                        "--maxmemory", "64mb",
                        "--maxmemory-policy", "allkeys-lru",
                        "--save", "",
                        "--appendonly", "no",
                        "--rename-command", "KEYS", "",
                        "--rename-command", "FLUSHALL", "",
                        "--rename-command", "FLUSHDB", "")
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));
    }

    /** The properties that point an application context at {@code redis}. */
    public static String[] connectionProperties(GenericContainer<?> redis) {
        return new String[]{
                "spring.data.redis.host=" + redis.getHost(),
                "spring.data.redis.port=" + redis.getMappedPort(PORT),
                "spring.data.redis.password=" + PASSWORD};
    }
}
