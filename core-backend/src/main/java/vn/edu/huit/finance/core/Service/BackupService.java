package vn.edu.huit.finance.core.Service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Service.Audit.SecurityAuditEvent;
import vn.edu.huit.finance.core.Service.Cache.StatisticsChange;
import vn.edu.huit.finance.core.Service.Cache.StatisticsChangedEvent;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Backups and restores of the database through pg_dump / pg_restore, for administrators. Their effect lies outside the
 * application's transactions, so each one that succeeded is recorded right after it as a SecurityAuditEvent (a row of its
 * own, naming the administrator who called); a row that cannot be written is logged and does not undo the backup.
 */
@Service
public class BackupService {
    private static final String AUDIT_BACKUP = "Sao lưu cơ sở dữ liệu (loại: %s)";
    private static final String AUDIT_RESTORE = "Phục hồi cơ sở dữ liệu từ file: %s";

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Value("${spring.datasource.username}")
    private String dbUser;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    private final String dbName = "finance_db";

    private final String backupDir = System.getProperty("user.dir") + "/backups/";

    @Value("${postgres.bin.path:}")
    private String pgBinPath;

    private String getCommand(String cmd) {
        if (pgBinPath == null || pgBinPath.trim().isEmpty()) {
            return cmd;
        }
        if (!pgBinPath.endsWith("/") && !pgBinPath.endsWith("\\")) {
            return pgBinPath + File.separator + cmd;
        }
        return pgBinPath + cmd;
    }

    public boolean backupDatabase(String type) {
        try {
            String timeStamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String fileName = backupDir + type + "_backup_" + timeStamp + ".sql";
            File dir = new File(backupDir);
            if (!dir.exists()) dir.mkdirs();

            ProcessBuilder processBuilder = new ProcessBuilder(
                    getCommand("pg_dump"),
                    "-U", dbUser,
                    "-F", "c",
                    "-b",
                    "-v",
                    "-f", fileName,
                    dbName
            );

            processBuilder.environment().put("PGPASSWORD", dbPassword);
            processBuilder.inheritIO();

            Process process = processBuilder.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                eventPublisher.publishEvent(SecurityAuditEvent.ofCurrentCaller(AuditAction.SYSTEM_BACKUP_CREATED,
                        String.format(AUDIT_BACKUP, type)));
            }
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean restoreDatabase(String fileName) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    getCommand("pg_restore"),
                    "-U", dbUser,
                    "-d", dbName,
                    "-1",
                    "--clean",
                    "--if-exists",
                    backupDir + fileName
            );
            processBuilder.environment().put("PGPASSWORD", dbPassword);
            processBuilder.inheritIO();

            Process process = processBuilder.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                // Written into the restored database, after pg_restore has replaced action_logs with the backup's.
                eventPublisher.publishEvent(SecurityAuditEvent.ofCurrentCaller(AuditAction.SYSTEM_DATABASE_RESTORED,
                        String.format(AUDIT_RESTORE, fileName)));
                // Every cached statistic now describes data that is gone.
                eventPublisher.publishEvent(StatisticsChangedEvent.of(StatisticsChange.DATABASE_RESTORED));
            }

            return exitCode == 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public String latestBackup(String type) {
        File dir = new File(backupDir);
        File[] files = dir.listFiles((d, n) -> n.startsWith(type) && n.endsWith(".sql"));
        if (files == null || files.length == 0) return null;
        return Arrays.stream(files)
                .max(Comparator.comparingLong(File::lastModified))
                .map(File::getName)
                .orElse(null);
    }

    public boolean restoreLatest(String type) {
        String fileName = latestBackup(type);
        if (fileName == null) return false;
        return restoreDatabase(fileName);
    }
}