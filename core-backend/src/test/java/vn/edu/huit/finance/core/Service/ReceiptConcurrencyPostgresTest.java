package vn.edu.huit.finance.core.Service;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * {@link ReceiptConcurrencyContract} on PostgreSQL, the database of the deployment: row locks, READ COMMITTED and the
 * re-check of a blocked UPDATE behave here exactly as they will in production. Skipped where Docker is not available.
 * <p>
 * The database is a container of its own, started for this class and thrown away after it, on a random port. Its user,
 * password and name exist nowhere else, so even a datasource that was pointed at another server by mistake could not
 * sign in there: the schema this class creates can only ever be the one of its own container. The schema is created and
 * never dropped ("create", not "create-drop"): the container is gone by the time the application context closes, and a
 * drop would only make the JVM wait for a connection that can no longer be made.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class ReceiptConcurrencyPostgresTest extends ReceiptConcurrencyContract {

    /** Keep in step with the db service of docker-compose.yml. */
    private static final String IMAGE = "postgres:15-alpine";
    private static final int PORT = 5432;
    private static final String DATABASE = "receipt_concurrency_it";
    private static final String USERNAME = "receipt_concurrency_it";
    private static final String PASSWORD = "integration-test-only";
    /** The server logs this once while it initialises the data directory and once more when it really accepts clients. */
    private static final String READY_LOG = ".*database system is ready to accept connections.*\\n";
    private static final int READY_LOG_TIMES = 2;

    @Container
    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withExposedPorts(PORT)
            .withEnv("POSTGRES_DB", DATABASE)
            .withEnv("POSTGRES_USER", USERNAME)
            .withEnv("POSTGRES_PASSWORD", PASSWORD)
            .waitingFor(Wait.forLogMessage(READY_LOG, READY_LOG_TIMES));

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(PORT) + "/" + DATABASE);
        registry.add("spring.datasource.username", () -> USERNAME);
        registry.add("spring.datasource.password", () -> PASSWORD);
    }
}
