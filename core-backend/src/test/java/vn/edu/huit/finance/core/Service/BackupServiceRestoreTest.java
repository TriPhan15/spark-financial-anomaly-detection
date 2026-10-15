package vn.edu.huit.finance.core.Service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Service.Audit.AuditActor;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;
import vn.edu.huit.finance.core.Service.Audit.SecurityAuditEvent;
import vn.edu.huit.finance.core.Service.Cache.StatisticsChange;
import vn.edu.huit.finance.core.Service.Cache.StatisticsChangedEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** A restore replaces every row the cached statistics were computed from. pg_restore is a stand-in script here. */
@ExtendWith(MockitoExtension.class)
@EnabledOnOs({OS.LINUX, OS.MAC})
class BackupServiceRestoreTest {

    @TempDir private Path pgBin;
    @Mock private ApplicationEventPublisher eventPublisher;
    @InjectMocks private BackupService service;

    private void pgRestoreExitsWith(int exitCode) throws IOException {
        Path script = pgBin.resolve("pg_restore");
        Files.writeString(script, "#!/bin/sh\nexit " + exitCode + "\n");
        assertTrue(script.toFile().setExecutable(true));
        ReflectionTestUtils.setField(service, "pgBinPath", pgBin.toString());
        ReflectionTestUtils.setField(service, "dbUser", "finance");
        ReflectionTestUtils.setField(service, "dbPassword", "test-only");
    }

    @Test
    void aRestoreThatSucceededEmptiesEveryStatisticsCache() throws IOException {
        pgRestoreExitsWith(0);

        assertTrue(service.restoreDatabase("FULL_backup_20260930_120000.sql"));

        verify(eventPublisher).publishEvent(StatisticsChangedEvent.of(StatisticsChange.DATABASE_RESTORED));
    }

    @Test
    void aRestoreThatSucceededIsRecordedOnItsOwnWithTheAdministratorWhoCalled() throws IOException {
        pgRestoreExitsWith(0);

        service.restoreDatabase("FULL_backup_20260930_120000.sql");

        ArgumentCaptor<SecurityAuditEvent> event = ArgumentCaptor.forClass(SecurityAuditEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        AuditEntry entry = event.getValue().entry();
        assertEquals(AuditAction.SYSTEM_DATABASE_RESTORED, entry.action());
        assertEquals(AuditActor.currentCaller(), entry.actor());
        assertEquals("Phục hồi cơ sở dữ liệu từ file: FULL_backup_20260930_120000.sql", entry.description());
        assertFalse(entry.description().contains("test-only"), "never the database password");
    }

    @Test
    void aRestoreThatFailedChangedNothingAndEvictsNothing() throws IOException {
        pgRestoreExitsWith(1);

        assertFalse(service.restoreDatabase("FULL_backup_20260930_120000.sql"));

        verifyNoInteractions(eventPublisher);
    }
}
