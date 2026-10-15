package vn.edu.huit.finance.core.Service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.huit.finance.core.Dto.NotificationPageResponseDto;
import vn.edu.huit.finance.core.Dto.NotificationResponseDto;
import vn.edu.huit.finance.core.Entity.Notification;
import vn.edu.huit.finance.core.Entity.NotificationType;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.NotificationNotFoundException;
import vn.edu.huit.finance.core.Exception.UserNotFoundException;
import vn.edu.huit.finance.core.Repository.NotificationRepository;
import vn.edu.huit.finance.core.Security.CurrentUserProvider;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final Long ALICE_ID = 1L;
    private static final Long BOB_ID = 2L;
    private static final Long NOTIFICATION_ID = 7L;
    private static final Long TRANSACTION_ID = 500L;
    private static final Long BUDGET_ID = 100L;
    private static final Long RECEIPT_ID = 900L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 21, 10, 15, 30);
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Instant NOW = Instant.parse("2026-09-29T01:30:00Z");
    private static final String NOT_FOUND_MESSAGE = "Không tìm thấy thông báo";

    @Mock private NotificationRepository notificationRepository;
    @Mock private CurrentUserProvider currentUserProvider;
    @InjectMocks private NotificationService service;

    private final User alice = user(ALICE_ID);

    private static User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("user" + id);
        return user;
    }

    private static Notification notification(Long id, NotificationType type, String message, boolean read) {
        Notification notification = new Notification();
        notification.setId(id);
        notification.setType(type);
        notification.setMessage(message);
        notification.setRead(read);
        notification.setTransactionId(TRANSACTION_ID);
        notification.setBudgetId(BUDGET_ID);
        notification.setCreatedAt(CREATED_AT);
        return notification;
    }

    private void callerIsAlice() {
        when(currentUserProvider.getCurrentUser()).thenReturn(alice);
    }

    // ----------------------------------------------------------------------------------------------------- the listing

    @Test
    void getMyNotifications_asksForTheRequestedPageOfTheCaller_withoutAnySortFromOutside() {
        callerIsAlice();
        when(notificationRepository.findNewestByUserId(eq(ALICE_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 20), 40));

        service.getMyNotifications(2, 20);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(notificationRepository).findNewestByUserId(eq(ALICE_ID), page.capture());
        assertEquals(2, page.getValue().getPageNumber());
        assertEquals(20, page.getValue().getPageSize());
        assertTrue(page.getValue().getSort().isUnsorted(), "the order is fixed by the query, never by the request");
    }

    @Test
    void getMyNotifications_returnsDtosAndThePagingFigures_inTheRepositoryOrder() {
        callerIsAlice();
        when(notificationRepository.findNewestByUserId(eq(ALICE_ID), any(Pageable.class))).thenReturn(new PageImpl<>(
                List.of(notification(2L, NotificationType.BUDGET_EXCEEDED, "vượt", false),
                        notification(1L, NotificationType.BUDGET_WARNING, "đạt 80%", true)),
                PageRequest.of(1, 2), 5));

        NotificationPageResponseDto page = service.getMyNotifications(1, 2);

        assertEquals(1, page.getPage());
        assertEquals(2, page.getSize());
        assertEquals(5, page.getTotalElements());
        assertEquals(3, page.getTotalPages());
        assertTrue(page.isHasNext());
        assertEquals(2, page.getContent().size());
        NotificationResponseDto newest = page.getContent().get(0);
        assertEquals(2L, newest.getId());
        assertEquals(NotificationType.BUDGET_EXCEEDED, newest.getType());
        assertEquals("vượt", newest.getMessage());
        assertFalse(newest.isRead());
        assertEquals(CREATED_AT, newest.getCreatedAt());
        assertEquals(TRANSACTION_ID, newest.getTransactionId());
        assertEquals(BUDGET_ID, newest.getBudgetId());
        assertNull(newest.getReceiptId());
        assertTrue(page.getContent().get(1).isRead());
    }

    @Test
    void getMyNotifications_theLastPageHasNoNext() {
        callerIsAlice();
        when(notificationRepository.findNewestByUserId(eq(ALICE_ID), any(Pageable.class))).thenReturn(new PageImpl<>(
                List.of(notification(1L, NotificationType.BUDGET_WARNING, "đạt 80%", false)), PageRequest.of(2, 2), 5));

        assertFalse(service.getMyNotifications(2, 2).isHasNext());
    }

    @Test
    void getMyNotifications_isAnEmptyPageWhenThereIsNothing() {
        callerIsAlice();
        when(notificationRepository.findNewestByUserId(eq(ALICE_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        NotificationPageResponseDto page = service.getMyNotifications(0, 20);

        assertTrue(page.getContent().isEmpty());
        assertEquals(0, page.getTotalElements());
        assertFalse(page.isHasNext());
    }

    @Test
    void getMyNotifications_asksOnlyForTheCaller_neverForAnotherUser() {
        callerIsAlice();
        when(notificationRepository.findNewestByUserId(eq(ALICE_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        service.getMyNotifications(0, 20);

        verify(notificationRepository).findNewestByUserId(eq(ALICE_ID), any(Pageable.class));
        verify(notificationRepository, never()).findNewestByUserId(eq(BOB_ID), any(Pageable.class));
        verifyNoMoreInteractions(notificationRepository);
    }

    @Test
    void getMyNotifications_failsAndReadsNothing_whenTheCallerCannotBeResolved() {
        when(currentUserProvider.getCurrentUser()).thenThrow(new UserNotFoundException("gone"));

        assertThrows(UserNotFoundException.class, () -> service.getMyNotifications(0, 20));

        verifyNoInteractions(notificationRepository);
    }

    // ---------------------------------------------------------------------------------------------------- unread count

    @Test
    void countMyUnread_isTheCountOfTheCallersUnreadNotifications() {
        callerIsAlice();
        when(notificationRepository.countUnreadByUserId(ALICE_ID)).thenReturn(3L);

        assertEquals(3L, service.countMyUnread());

        verify(notificationRepository, never()).countUnreadByUserId(BOB_ID);
    }

    @Test
    void countMyUnread_failsAndCountsNothing_whenTheCallerCannotBeResolved() {
        when(currentUserProvider.getCurrentUser()).thenThrow(new UserNotFoundException("gone"));

        assertThrows(UserNotFoundException.class, () -> service.countMyUnread());

        verifyNoInteractions(notificationRepository);
    }

    // ------------------------------------------------------------------------------------------------ mark one as read

    @Test
    void markAsRead_marksTheCallersUnreadNotification_andReturnsItRead() {
        callerIsAlice();
        Notification unread = notification(NOTIFICATION_ID, NotificationType.BUDGET_WARNING, "đạt 80%", false);
        when(notificationRepository.findByIdAndUserId(NOTIFICATION_ID, ALICE_ID)).thenReturn(Optional.of(unread));

        NotificationResponseDto marked = service.markAsRead(NOTIFICATION_ID);

        assertTrue(unread.isRead(), "the managed entity is changed, so the transaction writes it");
        assertTrue(marked.isRead());
        assertEquals(NOTIFICATION_ID, marked.getId());
        assertEquals(BUDGET_ID, marked.getBudgetId());
    }

    @Test
    void markAsRead_ofANotificationThatIsAlreadyRead_changesNothing_andStillAnswersIt() {
        callerIsAlice();
        Notification read = spy(notification(NOTIFICATION_ID, NotificationType.BUDGET_WARNING, "đạt 80%", true));
        when(notificationRepository.findByIdAndUserId(NOTIFICATION_ID, ALICE_ID)).thenReturn(Optional.of(read));

        NotificationResponseDto answer = service.markAsRead(NOTIFICATION_ID);

        assertTrue(answer.isRead());
        verify(read, never()).setRead(anyBoolean());
    }

    @Test
    void markAsRead_looksTheNotificationUpForTheCallerOnly() {
        callerIsAlice();
        when(notificationRepository.findByIdAndUserId(NOTIFICATION_ID, ALICE_ID)).thenReturn(Optional.empty());

        assertThrows(NotificationNotFoundException.class, () -> service.markAsRead(NOTIFICATION_ID));

        verify(notificationRepository).findByIdAndUserId(NOTIFICATION_ID, ALICE_ID);
        verify(notificationRepository, never()).findById(anyLong());
        verify(notificationRepository, never()).findByIdAndUserId(anyLong(), eq(BOB_ID));
    }

    @Test
    void markAsRead_givesTheSameNotFound_forAMissingIdAndForSomeoneElsesNotification() {
        callerIsAlice();
        Long missingId = 404L;
        Long bobsNotificationId = 8L; // exists, but the lookup is scoped to Alice, so it finds nothing either
        when(notificationRepository.findByIdAndUserId(missingId, ALICE_ID)).thenReturn(Optional.empty());
        when(notificationRepository.findByIdAndUserId(bobsNotificationId, ALICE_ID)).thenReturn(Optional.empty());

        NotificationNotFoundException missing =
                assertThrows(NotificationNotFoundException.class, () -> service.markAsRead(missingId));
        NotificationNotFoundException someoneElses =
                assertThrows(NotificationNotFoundException.class, () -> service.markAsRead(bobsNotificationId));

        assertEquals(NOT_FOUND_MESSAGE, missing.getMessage());
        assertEquals(missing.getMessage(), someoneElses.getMessage(), "the answer must not tell the two apart");
    }

    @Test
    void markAsRead_failsAndReadsNothing_whenTheCallerCannotBeResolved() {
        when(currentUserProvider.getCurrentUser()).thenThrow(new UserNotFoundException("gone"));

        assertThrows(UserNotFoundException.class, () -> service.markAsRead(NOTIFICATION_ID));

        verifyNoInteractions(notificationRepository);
    }

    // ------------------------------------------------------------------------------------------------ mark all as read

    @Test
    void markAllAsRead_updatesTheCallersNotificationsAtTheCurrentTime_andReturnsHowManyChanged() {
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(NOW, ZONE));
        callerIsAlice();
        when(notificationRepository.markAllAsReadByUserId(eq(ALICE_ID), any(LocalDateTime.class))).thenReturn(4);

        assertEquals(4, service.markAllAsRead());

        verify(notificationRepository).markAllAsReadByUserId(ALICE_ID, LocalDateTime.ofInstant(NOW, ZONE));
        verify(notificationRepository, never()).markAllAsReadByUserId(eq(BOB_ID), any());
    }

    @Test
    void markAllAsRead_isZeroWhenNothingWasUnread() {
        callerIsAlice();
        when(notificationRepository.markAllAsReadByUserId(eq(ALICE_ID), any(LocalDateTime.class))).thenReturn(0);

        assertEquals(0, service.markAllAsRead());
    }

    @Test
    void markAllAsRead_failsAndUpdatesNothing_whenTheCallerCannotBeResolved() {
        when(currentUserProvider.getCurrentUser()).thenThrow(new UserNotFoundException("gone"));

        assertThrows(UserNotFoundException.class, () -> service.markAllAsRead());

        verifyNoInteractions(notificationRepository);
    }

    // ------------------------------------------------------------------------------------------------------ creating

    @Test
    void create_savesAnUnreadNotificationForTheGivenUser_withItsPayload() {
        service.create(alice, NotificationType.RECEIPT_PROCESSED, "hóa đơn", TRANSACTION_ID, null, RECEIPT_ID);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(saved.capture());
        assertSame(alice, saved.getValue().getUser());
        assertEquals(NotificationType.RECEIPT_PROCESSED, saved.getValue().getType());
        assertEquals("hóa đơn", saved.getValue().getMessage());
        assertEquals(TRANSACTION_ID, saved.getValue().getTransactionId());
        assertNull(saved.getValue().getBudgetId());
        assertEquals(RECEIPT_ID, saved.getValue().getReceiptId());
        assertFalse(saved.getValue().isRead());
    }

    @Test
    void create_keepsEachIdInItsOwnColumn() {
        service.create(alice, NotificationType.BUDGET_WARNING, "đạt 80%", TRANSACTION_ID, BUDGET_ID, null);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(saved.capture());
        assertEquals(TRANSACTION_ID, saved.getValue().getTransactionId());
        assertEquals(BUDGET_ID, saved.getValue().getBudgetId());
        assertNull(saved.getValue().getReceiptId());
    }

    @Test
    void create_doesNotSwallowADatabaseFailure_soTheCallersTransactionCanRollBack() {
        doThrow(new IllegalStateException("database went away")).when(notificationRepository).save(any(Notification.class));

        assertThrows(IllegalStateException.class,
                () -> service.create(alice, NotificationType.BUDGET_EXCEEDED, "vượt", TRANSACTION_ID, BUDGET_ID, null));
    }

    // ---------------------------------------------------------------------------------------------- structural guards

    @Test
    void theReadsAreReadOnly_andTheWritesRunInAWritableTransaction() throws NoSuchMethodException {
        List<Method> reads = List.of(
                NotificationService.class.getMethod("getMyNotifications", int.class, int.class),
                NotificationService.class.getMethod("countMyUnread"));
        List<Method> writes = List.of(
                NotificationService.class.getMethod("markAsRead", Long.class),
                NotificationService.class.getMethod("markAllAsRead"),
                NotificationService.class.getMethod("create", User.class, NotificationType.class, String.class,
                        Long.class, Long.class, Long.class));

        for (Method read : reads) {
            Transactional transactional = AnnotatedElementUtils.findMergedAnnotation(read, Transactional.class);
            assertNotNull(transactional, read.getName());
            assertTrue(transactional.readOnly(), read.getName());
        }
        for (Method write : writes) {
            Transactional transactional = AnnotatedElementUtils.findMergedAnnotation(write, Transactional.class);
            assertNotNull(transactional, write.getName());
            assertFalse(transactional.readOnly(), write.getName());
        }
    }

    @Test
    void thereIsOneWayToCreateANotification() {
        long creators = Arrays.stream(NotificationService.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("create"))
                .count();

        assertEquals(1, creators, "no overload may keep an older contract alive");
    }

    @Test
    void noPublicMethodTakesAUserIdFromItsCaller() {
        List<String> userIdParameters = Arrays.stream(NotificationService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .flatMap(method -> Arrays.stream(method.getParameters()))
                .map(Parameter::getName)
                .filter(name -> name.equalsIgnoreCase("userId"))
                .toList();

        assertTrue(userIdParameters.isEmpty(), "the owner always comes from the SecurityContext");
    }

    @Test
    void theBulkUpdateFlushesBeforeAndClearsAfter() throws NoSuchMethodException {
        Method bulk = NotificationRepository.class.getMethod("markAllAsReadByUserId", Long.class, LocalDateTime.class);

        Modifying modifying = bulk.getAnnotation(Modifying.class);

        assertNotNull(modifying);
        assertTrue(modifying.flushAutomatically());
        assertTrue(modifying.clearAutomatically());
    }
}
