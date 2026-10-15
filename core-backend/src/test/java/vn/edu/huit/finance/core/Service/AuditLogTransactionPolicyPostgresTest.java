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
 * {@link AuditLogTransactionPolicyContract} on PostgreSQL, the database of the deployment: there an error aborts the
 * whole transaction it happens in, so a refused row must roll the change back and a row of its own must be written in
 * a transaction of its own to stay. Skipped where Docker is not available. A container of its own, with a user, password
 * and database that exist nowhere else (see ReceiptConcurrencyPostgresTest); the schema is created, never dropped.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class AuditLogTransactionPolicyPostgresTest extends AuditLogTransactionPolicyContract {

    /** Keep in step with the db service of docker-compose.yml. */
    private static final String IMAGE = "postgres:15-alpine";
    private static final int PORT = 5432;
    private static final String DATABASE = "audit_policy_it";
    private static final String USERNAME = "audit_policy_it";
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
