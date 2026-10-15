package vn.edu.huit.finance.core.Service;

import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

/**
 * {@link ReceiptConcurrencyContract} on H2, the database of the rest of the suite: always available, no Docker needed.
 * H2 agrees with PostgreSQL on everything the contract checks (a write that waited for a row is evaluated again against
 * what was committed meanwhile); ReceiptConcurrencyPostgresTest is what shows that.
 */
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
class ReceiptConcurrencyH2Test extends ReceiptConcurrencyContract {
}
