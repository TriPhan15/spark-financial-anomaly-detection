package vn.edu.huit.finance.core.Service;

import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

/** {@link AuditLogTransactionPolicyContract} on H2, the database of the rest of the suite: always available, no Docker needed. */
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
class AuditLogTransactionPolicyH2Test extends AuditLogTransactionPolicyContract {
}
