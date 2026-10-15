package vn.edu.huit.finance.core.Service.Audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.CannotCreateTransactionException;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Exception.AuditLogWriteException;
import vn.edu.huit.finance.core.Service.AuditLogService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The failure policy of rows written on their own: the request they describe is answered as if the row had been written
 * (a refusal stays a refusal), and the failure is logged and counted, never thrown at the publisher.
 */
@ExtendWith(MockitoExtension.class)
class SecurityAuditListenerTest {

    @Mock private AuditLogService auditLogService;
    @InjectMocks private SecurityAuditListener listener;

    private static final SecurityAuditEvent REFUSED_LOGIN = SecurityAuditEvent.of(AuditAction.AUTH_LOGIN_FAILED,
            AuditActor.unauthenticated("mallory@example.com"), null, "Sai thông tin đăng nhập");

    @Test
    void theRowIsWrittenInATransactionOfItsOwn() {
        listener.record(REFUSED_LOGIN);

        verify(auditLogService).recordIndependently(REFUSED_LOGIN.entry());
        verifyNoMoreInteractions(auditLogService);
    }

    @Test
    void aRowThatCouldNotBeWrittenDoesNotChangeTheAnswerToTheRequest() {
        doThrow(new AuditLogWriteException("fixed", new IllegalStateException("db"))).when(auditLogService)
                .recordIndependently(REFUSED_LOGIN.entry());

        assertDoesNotThrow(() -> listener.record(REFUSED_LOGIN));
    }

    @Test
    void neitherDoesAFailureBeforeTheInsert_suchAsNoConnectionForTheNewTransaction() {
        doThrow(new CannotCreateTransactionException("pool exhausted")).when(auditLogService)
                .recordIndependently(REFUSED_LOGIN.entry());

        assertDoesNotThrow(() -> listener.record(REFUSED_LOGIN));
    }

    @Test
    void itListensSynchronously_soTheRowIsWrittenWhileTheRequestAndItsSecurityContextAreAtHand() throws NoSuchMethodException {
        EventListener annotation = SecurityAuditListener.class.getMethod("record", SecurityAuditEvent.class)
                .getAnnotation(EventListener.class);

        assertNotNull(annotation, "a plain @EventListener: no @Async, no @TransactionalEventListener waiting for a commit");
        assertNull(SecurityAuditListener.class.getMethod("record", SecurityAuditEvent.class)
                .getAnnotation(org.springframework.scheduling.annotation.Async.class));
    }
}
