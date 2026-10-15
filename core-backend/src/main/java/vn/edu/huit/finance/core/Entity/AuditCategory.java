package vn.edu.huit.finance.core.Entity;

/** The tab of the admin audit page an action belongs to. Not stored: it follows from the action code. */
public enum AuditCategory {
    /** Sign-in, sessions, credentials and refused requests. */
    SECURITY,
    /** Changes to the data of the application (transactions, receipts, budgets, accounts...). */
    DATA,
    /** Operations of the system itself: backups, restores, scheduled jobs, batch analyses. */
    SYSTEM
}
