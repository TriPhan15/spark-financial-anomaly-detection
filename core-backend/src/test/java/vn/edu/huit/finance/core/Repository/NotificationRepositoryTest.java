package vn.edu.huit.finance.core.Repository;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import vn.edu.huit.finance.core.Entity.Notification;
import vn.edu.huit.finance.core.Entity.NotificationType;
import vn.edu.huit.finance.core.Entity.User;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the real mapping and queries on H2 (the project has no test PostgreSQL), so ordering, paging, the ownership
 * scoping, the bulk update and the "one query" promise are checked for real. The creation time is set by JPA auditing,
 * so the tests move it afterwards with a native update, which is the only way to control it.
 */
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
class NotificationRepositoryTest {

    private static final LocalDateTime NOON = LocalDateTime.of(2026, 9, 21, 12, 0);
    private static final LocalDateTime READ_AT = LocalDateTime.of(2026, 9, 29, 8, 30);
    private static final int WHOLE_LIST = 100;

    @Autowired private NotificationRepository notificationRepository;
    @Autowired private TestEntityManager entityManager;

    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        alice = persistUser("alice", "0900000001");
        bob = persistUser("bob", "0900000002");
    }

    private User persistUser(String username, String phone) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("hash");
        user.setEmail(username + "@example.com");
        user.setPhone(phone);
        return entityManager.persistAndFlush(user);
    }

    private Notification persistNotification(User owner, NotificationType type, String message, LocalDateTime createdAt) {
        Notification notification = new Notification();
        notification.setUser(owner);
        notification.setType(type);
        notification.setMessage(message);
        notification.setBudgetId(100L);
        Notification saved = entityManager.persistAndFlush(notification);
        entityManager.getEntityManager()
                .createNativeQuery("update notifications set created_at = :createdAt where id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", saved.getId())
                .executeUpdate();
        return saved;
    }

    private Notification persistRead(User owner, String message) {
        Notification notification = persistNotification(owner, NotificationType.BUDGET_WARNING, message, NOON);
        notification.setRead(true);
        return entityManager.persistAndFlush(notification);
    }

    private Statistics statistics() {
        return entityManager.getEntityManager().getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }

    private void forgetLoadedEntities() {
        entityManager.flush();
        entityManager.clear();
        statistics().clear();
    }

    private Page<Notification> pageOf(User owner, int page, int size) {
        return notificationRepository.findNewestByUserId(owner.getId(), PageRequest.of(page, size));
    }

    private List<String> messagesOf(User owner, int size) {
        return pageOf(owner, 0, size).stream().map(Notification::getMessage).toList();
    }

    private Notification reloaded(Notification notification) {
        return entityManager.find(Notification.class, notification.getId());
    }

    // ------------------------------------------------------------------------------------------------------ the listing

    @Test
    void findNewestByUserId_listsTheNewestFirst() {
        persistNotification(alice, NotificationType.BUDGET_WARNING, "oldest", NOON.minusDays(2));
        persistNotification(alice, NotificationType.BUDGET_EXCEEDED, "newest", NOON);
        persistNotification(alice, NotificationType.BUDGET_WARNING, "middle", NOON.minusDays(1));
        forgetLoadedEntities();

        assertEquals(List.of("newest", "middle", "oldest"), messagesOf(alice, WHOLE_LIST));
    }

    @Test
    void findNewestByUserId_putsTheLaterOneFirst_whenTwoWereRaisedInTheSameInstant() {
        persistNotification(alice, NotificationType.BUDGET_WARNING, "first", NOON);
        persistNotification(alice, NotificationType.BUDGET_EXCEEDED, "second", NOON);
        forgetLoadedEntities();

        assertEquals(List.of("second", "first"), messagesOf(alice, WHOLE_LIST));
    }

    @Test
    void findNewestByUserId_neverReturnsTheNotificationsOfAnotherUser() {
        persistNotification(alice, NotificationType.BUDGET_WARNING, "alice's", NOON);
        persistNotification(bob, NotificationType.BUDGET_WARNING, "bob's", NOON);
        forgetLoadedEntities();

        assertEquals(List.of("alice's"), messagesOf(alice, WHOLE_LIST));
        assertEquals(List.of("bob's"), messagesOf(bob, WHOLE_LIST));
    }

    @Test
    void findNewestByUserId_countsOnlyTheOwnersNotificationsInTheTotal() {
        for (int i = 0; i < 3; i++) {
            persistNotification(alice, NotificationType.BUDGET_WARNING, "alice " + i, NOON.plusMinutes(i));
            persistNotification(bob, NotificationType.BUDGET_WARNING, "bob " + i, NOON.plusMinutes(i));
        }
        forgetLoadedEntities();

        // A full first page, so the count query really runs.
        Page<Notification> page = pageOf(alice, 0, 2);

        assertEquals(3, page.getTotalElements());
        assertEquals(2, page.getTotalPages());
    }

    @Test
    void findNewestByUserId_returnsNothingForAUserWithoutNotifications() {
        persistNotification(bob, NotificationType.BUDGET_WARNING, "bob's", NOON);
        forgetLoadedEntities();

        Page<Notification> page = pageOf(alice, 0, WHOLE_LIST);

        assertTrue(page.getContent().isEmpty());
        assertEquals(0, page.getTotalElements());
        assertEquals(0, page.getTotalPages());
        assertFalse(page.hasNext());
    }

    @Test
    void findNewestByUserId_cutsTheListToThePageSize_keepingTheNewest() {
        for (int i = 0; i < 5; i++) {
            persistNotification(alice, NotificationType.BUDGET_WARNING, "n" + i, NOON.plusMinutes(i));
        }
        forgetLoadedEntities();

        assertEquals(List.of("n4", "n3"), messagesOf(alice, 2));
    }

    @Test
    void findNewestByUserId_walksThePagesInOrder_andSaysWhetherAnotherOneFollows() {
        for (int i = 0; i < 5; i++) {
            persistNotification(alice, NotificationType.BUDGET_WARNING, "n" + i, NOON.plusMinutes(i));
        }
        forgetLoadedEntities();

        Page<Notification> first = pageOf(alice, 0, 2);
        Page<Notification> second = pageOf(alice, 1, 2);
        Page<Notification> last = pageOf(alice, 2, 2);

        assertEquals(List.of("n4", "n3"), first.stream().map(Notification::getMessage).toList());
        assertEquals(List.of("n2", "n1"), second.stream().map(Notification::getMessage).toList());
        assertEquals(List.of("n0"), last.stream().map(Notification::getMessage).toList());
        for (Page<Notification> page : List.of(first, second, last)) {
            assertEquals(5, page.getTotalElements());
            assertEquals(3, page.getTotalPages());
            assertEquals(2, page.getSize());
        }
        assertTrue(first.hasNext());
        assertTrue(second.hasNext());
        assertFalse(last.hasNext());
        assertEquals(1, second.getNumber());
    }

    @Test
    void findNewestByUserId_aPageAfterTheLastOneIsEmpty_butStillKnowsTheTotal() {
        for (int i = 0; i < 3; i++) {
            persistNotification(alice, NotificationType.BUDGET_WARNING, "n" + i, NOON.plusMinutes(i));
        }
        forgetLoadedEntities();

        Page<Notification> beyond = pageOf(alice, 7, 2);

        assertTrue(beyond.getContent().isEmpty());
        assertEquals(3, beyond.getTotalElements());
        assertEquals(2, beyond.getTotalPages());
        assertFalse(beyond.hasNext());
    }

    @Test
    void findNewestByUserId_readsEverythingTheDtoNeedsInOneQuery_whenThePageIsNotFull() {
        for (int i = 0; i < 3; i++) {
            persistNotification(alice, NotificationType.BUDGET_WARNING, "n" + i, NOON.plusMinutes(i));
        }
        forgetLoadedEntities();

        List<Notification> found = pageOf(alice, 0, WHOLE_LIST).getContent();
        found.forEach(this::readWhatTheDtoReads);

        assertEquals(3, found.size());
        assertEquals(1, statistics().getPrepareStatementCount(), "listing must not issue a query per notification");
    }

    @Test
    void findNewestByUserId_aFullPageCostsTheListPlusOneCount_howeverManyRowsItHolds() {
        for (int i = 0; i < 12; i++) {
            persistNotification(alice, NotificationType.BUDGET_WARNING, "n" + i, NOON.plusMinutes(i));
        }
        forgetLoadedEntities();

        Page<Notification> page = pageOf(alice, 0, 10);
        page.getContent().forEach(this::readWhatTheDtoReads);

        assertEquals(10, page.getContent().size());
        assertEquals(12, page.getTotalElements());
        assertEquals(2, statistics().getPrepareStatementCount(), "the page and its count, never a query per notification");
    }

    /** What NotificationResponseDto.from reads. */
    private void readWhatTheDtoReads(Notification n) {
        n.getId();
        n.getType();
        n.getMessage();
        n.isRead();
        n.getCreatedAt();
        n.getTransactionId();
        n.getBudgetId();
        n.getReceiptId();
    }

    // --------------------------------------------------------------------------------------------------- unread count

    @Test
    void countUnreadByUserId_countsOnlyTheUnreadNotificationsOfThatUser() {
        persistNotification(alice, NotificationType.BUDGET_WARNING, "unread 1", NOON);
        persistNotification(alice, NotificationType.BUDGET_EXCEEDED, "unread 2", NOON);
        persistRead(alice, "read");
        persistNotification(bob, NotificationType.BUDGET_WARNING, "bob's unread", NOON);
        forgetLoadedEntities();

        assertEquals(2L, notificationRepository.countUnreadByUserId(alice.getId()));
        assertEquals(1, statistics().getPrepareStatementCount(), "the count is one statement");
        assertEquals(1L, notificationRepository.countUnreadByUserId(bob.getId()));
    }

    @Test
    void countUnreadByUserId_isZeroForAUserWithoutNotifications() {
        persistNotification(bob, NotificationType.BUDGET_WARNING, "bob's", NOON);
        forgetLoadedEntities();

        assertEquals(0L, notificationRepository.countUnreadByUserId(alice.getId()));
    }

    // ------------------------------------------------------------------------------------------------- owner lookup

    @Test
    void findByIdAndUserId_findsTheNotificationOnlyForItsOwner() {
        Notification alices = persistNotification(alice, NotificationType.BUDGET_WARNING, "alice's", NOON);
        forgetLoadedEntities();

        assertTrue(notificationRepository.findByIdAndUserId(alices.getId(), alice.getId()).isPresent());
        assertTrue(notificationRepository.findByIdAndUserId(alices.getId(), bob.getId()).isEmpty(),
                "someone else's notification must look exactly like a missing one");
        assertTrue(notificationRepository.findByIdAndUserId(alices.getId() + 1000, alice.getId()).isEmpty());
    }

    // --------------------------------------------------------------------------------------------- mark all as read

    @Test
    void markAllAsReadByUserId_marksEveryUnreadNotificationOfTheUser_andReturnsHowManyChanged() {
        Notification first = persistNotification(alice, NotificationType.BUDGET_WARNING, "unread 1", NOON);
        Notification second = persistNotification(alice, NotificationType.BUDGET_EXCEEDED, "unread 2", NOON);
        Notification alreadyRead = persistRead(alice, "read");
        forgetLoadedEntities();
        // Read back from the database: the column keeps microseconds, the clock of the auditing listener may not.
        LocalDateTime alreadyReadUpdatedAt = reloaded(alreadyRead).getUpdatedAt();

        int marked = notificationRepository.markAllAsReadByUserId(alice.getId(), READ_AT);

        assertEquals(2, marked, "only the rows that really went from unread to read");
        assertTrue(reloaded(first).isRead());
        assertTrue(reloaded(second).isRead());
        assertEquals(READ_AT, reloaded(first).getUpdatedAt());
        assertEquals(alreadyReadUpdatedAt, reloaded(alreadyRead).getUpdatedAt(), "a read row is not touched again");
        assertEquals(0L, notificationRepository.countUnreadByUserId(alice.getId()));
    }

    @Test
    void markAllAsReadByUserId_neverTouchesTheNotificationsOfAnotherUser() {
        persistNotification(alice, NotificationType.BUDGET_WARNING, "alice's", NOON);
        Notification bobs = persistNotification(bob, NotificationType.BUDGET_WARNING, "bob's", NOON);
        forgetLoadedEntities();

        notificationRepository.markAllAsReadByUserId(alice.getId(), READ_AT);

        assertFalse(reloaded(bobs).isRead());
        assertEquals(1L, notificationRepository.countUnreadByUserId(bob.getId()));
    }

    @Test
    void markAllAsReadByUserId_changesNothing_theSecondTime() {
        persistNotification(alice, NotificationType.BUDGET_WARNING, "unread", NOON);
        forgetLoadedEntities();

        assertEquals(1, notificationRepository.markAllAsReadByUserId(alice.getId(), READ_AT));
        assertEquals(0, notificationRepository.markAllAsReadByUserId(alice.getId(), READ_AT));
    }

    @Test
    void markAllAsReadByUserId_isOneStatement_howeverManyNotificationsItMarks() {
        for (int i = 0; i < 5; i++) {
            persistNotification(alice, NotificationType.BUDGET_WARNING, "n" + i, NOON.plusMinutes(i));
        }
        forgetLoadedEntities();

        notificationRepository.markAllAsReadByUserId(alice.getId(), READ_AT);

        assertEquals(1, statistics().getPrepareStatementCount());
    }

    @Test
    void markAllAsReadByUserId_clearsThePersistenceContext_soNoLoadedNotificationStaysStale() {
        Notification unread = persistNotification(alice, NotificationType.BUDGET_WARNING, "unread", NOON);
        forgetLoadedEntities();
        Notification loaded = entityManager.find(Notification.class, unread.getId());
        assertFalse(loaded.isRead());

        notificationRepository.markAllAsReadByUserId(alice.getId(), READ_AT);

        assertFalse(entityManager.getEntityManager().contains(loaded), "the stale copy was detached");
        assertTrue(reloaded(unread).isRead(), "a new read sees the database, not the old copy");
    }

    @Test
    void markAllAsReadByUserId_writesThePendingChangesBeforeClearing_soNoneIsLost() {
        Notification edited = persistRead(alice, "before");
        entityManager.find(Notification.class, edited.getId()).setMessage("changed in the same transaction, not flushed");

        notificationRepository.markAllAsReadByUserId(alice.getId(), READ_AT);

        assertEquals("changed in the same transaction, not flushed", reloaded(edited).getMessage());
    }

    // ------------------------------------------------------------------------------------------------------ the mapping

    @Test
    void theTypeIsStoredAsItsName_notItsOrdinal() {
        persistNotification(alice, NotificationType.BUDGET_EXCEEDED, "stored", NOON);
        entityManager.flush();

        Object stored = entityManager.getEntityManager()
                .createNativeQuery("select type from notifications").getSingleResult();

        assertEquals("BUDGET_EXCEEDED", stored);
    }

    @Test
    void theNewTypesAndTheirPayloadAreStored() {
        Notification transactionAlert = new Notification();
        transactionAlert.setUser(alice);
        transactionAlert.setType(NotificationType.TRANSACTION_ALERT);
        transactionAlert.setMessage("giao dịch");
        transactionAlert.setTransactionId(500L);
        Notification receiptProcessed = new Notification();
        receiptProcessed.setUser(alice);
        receiptProcessed.setType(NotificationType.RECEIPT_PROCESSED);
        receiptProcessed.setMessage("hóa đơn");
        receiptProcessed.setTransactionId(501L);
        receiptProcessed.setReceiptId(900L);
        entityManager.persistAndFlush(transactionAlert);
        entityManager.persistAndFlush(receiptProcessed);
        forgetLoadedEntities();

        Notification storedAlert = reloaded(transactionAlert);
        Notification storedReceipt = reloaded(receiptProcessed);

        assertEquals(NotificationType.TRANSACTION_ALERT, storedAlert.getType());
        assertEquals(500L, storedAlert.getTransactionId());
        assertNull(storedAlert.getBudgetId());
        assertNull(storedAlert.getReceiptId());
        assertEquals(NotificationType.RECEIPT_PROCESSED, storedReceipt.getType());
        assertEquals(501L, storedReceipt.getTransactionId());
        assertEquals(900L, storedReceipt.getReceiptId());
    }

    @Test
    void aNewNotificationIsUnread() {
        Notification saved = persistNotification(alice, NotificationType.BUDGET_WARNING, "new", NOON);
        forgetLoadedEntities();

        assertFalse(reloaded(saved).isRead());
    }

    @Test
    void theReadColumnDefaultsToUnread_forARowWrittenWithoutIt() {
        // What the rows that existed before the column get from the migration, or from ddl-auto=update.
        entityManager.getEntityManager()
                .createNativeQuery("insert into notifications (user_id, type, message, created_at) "
                        + "values (:userId, 'BUDGET_WARNING', 'older row', :createdAt)")
                .setParameter("userId", alice.getId())
                .setParameter("createdAt", NOON)
                .executeUpdate();
        forgetLoadedEntities();

        assertEquals(1L, notificationRepository.countUnreadByUserId(alice.getId()));
    }

    @Test
    void aNotificationGetsItsCreationTimeFromJpaAuditing() {
        Notification notification = new Notification();
        notification.setUser(alice);
        notification.setType(NotificationType.BUDGET_WARNING);
        notification.setMessage("audited");

        Notification saved = entityManager.persistAndFlush(notification);

        assertNotNull(saved.getCreatedAt(), "the list is ordered by it, so it must never be null");
    }
}
