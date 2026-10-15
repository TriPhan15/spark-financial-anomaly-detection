package vn.edu.huit.finance.core.Controller;

import io.jsonwebtoken.MalformedJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.edu.huit.finance.core.Config.SecurityConfig;
import vn.edu.huit.finance.core.Dto.NotificationPageResponseDto;
import vn.edu.huit.finance.core.Dto.NotificationResponseDto;
import vn.edu.huit.finance.core.Entity.NotificationType;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.GlobalExceptionHandler;
import vn.edu.huit.finance.core.Exception.NotificationNotFoundException;
import vn.edu.huit.finance.core.Exception.UserNotFoundException;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Security.CustomUserDetailsService;
import vn.edu.huit.finance.core.Security.JwtAuthFilter;
import vn.edu.huit.finance.core.Security.JwtUtil;
import vn.edu.huit.finance.core.Security.TokenClaims;
import vn.edu.huit.finance.core.Security.TokenType;
import vn.edu.huit.finance.core.Service.LoginAttemptService;
import vn.edu.huit.finance.core.Service.NotificationService;
import vn.edu.huit.finance.core.Service.TokenBlacklistService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real security filter chain + JwtAuthFilter + GlobalExceptionHandler around the four notification
 * endpoints. Explicit context classes keep the app class (@EnableJpaAuditing) out of the slice.
 */
@WebMvcTest(NotificationController.class)
@ContextConfiguration(classes = {
        NotificationController.class, GlobalExceptionHandler.class, SecurityConfig.class, JwtAuthFilter.class})
class NotificationControllerTest {

    private static final String URL = "/api/notifications";
    private static final String UNREAD_COUNT_URL = URL + "/unread-count";
    private static final String READ_ALL_URL = URL + "/read-all";
    private static final String TOKEN = "valid.jwt.token";
    private static final String BAD_TOKEN = "invalid.jwt.token";
    private static final String REVOKED_TOKEN = "revoked.jwt.token";
    private static final String USERNAME = "alice";
    private static final Long NOTIFICATION_ID = 7L;
    private static final String MESSAGE = "Bạn đã chi 1.600.000 / 2.000.000 VNĐ, đạt 80% ngân sách Ăn uống.";

    @Autowired private MockMvc mockMvc;

    @MockBean private NotificationService notificationService;
    @MockBean private JwtUtil jwtUtil;
    @MockBean private CustomUserDetailsService userDetailsService;
    @MockBean private UserRepository userRepository;
    @MockBean private LoginAttemptService loginAttemptService;
    @MockBean private TokenBlacklistService tokenBlacklistService;

    @BeforeEach
    void stubJwtForAlice() {
        when(jwtUtil.parse(TOKEN, TokenType.ACCESS)).thenReturn(new TokenClaims(USERNAME, 0));
        when(jwtUtil.parse(BAD_TOKEN, TokenType.ACCESS)).thenThrow(new MalformedJwtException("not a valid token"));
        when(jwtUtil.parse(REVOKED_TOKEN, TokenType.ACCESS)).thenReturn(new TokenClaims(USERNAME, 0));
        when(tokenBlacklistService.isRevoked(REVOKED_TOKEN)).thenReturn(true);

        User alice = new User();
        alice.setUsername(USERNAME);
        alice.setActive(true);
        alice.setRole("ROLE_USER");
        alice.setLastActionTime(LocalDateTime.now());
        when(userDetailsService.loadApplicationUser(USERNAME)).thenReturn(alice);
        when(userDetailsService.loadUserByUsername(USERNAME)).thenReturn(
                new org.springframework.security.core.userdetails.User(
                        USERNAME, "hash", AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    private static MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder builder, String token) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static NotificationResponseDto sampleNotification(boolean read) {
        return NotificationResponseDto.builder()
                .id(NOTIFICATION_ID)
                .type(NotificationType.BUDGET_WARNING)
                .message(MESSAGE)
                .isRead(read)
                .createdAt(LocalDateTime.of(2026, 9, 21, 10, 15, 30))
                .transactionId(500L)
                .budgetId(100L)
                .build();
    }

    private static NotificationPageResponseDto pageOf(List<NotificationResponseDto> content, boolean hasNext) {
        return NotificationPageResponseDto.builder()
                .content(content)
                .page(0)
                .size(20)
                .totalElements(21)
                .totalPages(2)
                .hasNext(hasNext)
                .build();
    }

    // --------------------------------------------------------------------------------------------- GET the list

    @Test
    void list_returns200WithThePageInTheEnvelope_andEveryFieldOfTheContract() throws Exception {
        when(notificationService.getMyNotifications(0, 20)).thenReturn(pageOf(List.of(sampleNotification(false)), true));

        mockMvc.perform(signedIn(get(URL).param("page", "0").param("size", "20"), TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Lấy danh sách thông báo thành công"))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(21))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(7))
                .andExpect(jsonPath("$.data.content[0].type").value("BUDGET_WARNING"))
                .andExpect(jsonPath("$.data.content[0].message").value(MESSAGE))
                .andExpect(jsonPath("$.data.content[0].isRead").value(false))
                .andExpect(jsonPath("$.data.content[0].read").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].createdAt").value("2026-09-21T10:15:30"))
                .andExpect(jsonPath("$.data.content[0].transactionId").value(500))
                .andExpect(jsonPath("$.data.content[0].budgetId").value(100))
                .andExpect(jsonPath("$.data.content[0].receiptId").value(nullValue()));
    }

    @Test
    void list_withoutParameters_asksForTheFirstPageOfTwenty() throws Exception {
        when(notificationService.getMyNotifications(0, 20)).thenReturn(pageOf(List.of(), false));

        mockMvc.perform(signedIn(get(URL), TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.hasNext").value(false));

        verify(notificationService).getMyNotifications(0, 20);
    }

    @ParameterizedTest
    @CsvSource({"0, 1", "3, 50", "1000, 20"})
    void list_passesAValidPageAndSizeThrough(int page, int size) throws Exception {
        when(notificationService.getMyNotifications(page, size)).thenReturn(pageOf(List.of(), false));

        mockMvc.perform(signedIn(get(URL).param("page", String.valueOf(page)).param("size", String.valueOf(size)), TOKEN))
                .andExpect(status().isOk());

        verify(notificationService).getMyNotifications(page, size);
    }

    static Stream<Arguments> invalidPages() {
        return Stream.of(
                Arguments.of("-1", "20", "Số trang phải lớn hơn hoặc bằng 0"),
                Arguments.of("0", "0", "Số thông báo mỗi trang phải từ 1 đến 50"),
                Arguments.of("0", "-5", "Số thông báo mỗi trang phải từ 1 đến 50"),
                Arguments.of("0", "51", "Số thông báo mỗi trang phải từ 1 đến 50"));
    }

    @ParameterizedTest
    @MethodSource("invalidPages")
    void list_refusesAPageOrSizeOutOfBounds_with400_andNeverReachesTheService(
            String page, String size, String reason) throws Exception {
        mockMvc.perform(signedIn(get(URL).param("page", page).param("size", size), TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("Dữ liệu không hợp lệ: " + reason));

        verifyNoInteractions(notificationService);
    }

    @Test
    void list_refusesAPageThatIsNotANumber() throws Exception {
        mockMvc.perform(signedIn(get(URL).param("page", "abc"), TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(notificationService);
    }

    @Test
    void list_ignoresASortAndAUserIdSentByTheClient_andRunsAsTheJwtUser() throws Exception {
        AtomicReference<String> callerSeenByService = new AtomicReference<>();
        when(notificationService.getMyNotifications(0, 20)).thenAnswer(invocation -> {
            callerSeenByService.set(SecurityContextHolder.getContext().getAuthentication().getName());
            return pageOf(List.of(), false);
        });

        mockMvc.perform(signedIn(get(URL).param("sort", "createdAt,asc").param("userId", "999"), TOKEN))
                .andExpect(status().isOk());

        assertEquals(USERNAME, callerSeenByService.get());
        verify(notificationService, times(1)).getMyNotifications(0, 20);
    }

    // ------------------------------------------------------------------------------------------- GET the unread count

    @Test
    void unreadCount_returns200WithTheNumber() throws Exception {
        when(notificationService.countMyUnread()).thenReturn(3L);

        mockMvc.perform(signedIn(get(UNREAD_COUNT_URL), TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Lấy số thông báo chưa đọc thành công"))
                .andExpect(jsonPath("$.data").value(3));
    }

    @Test
    void unreadCount_isNotTakenForAnId() throws Exception {
        when(notificationService.countMyUnread()).thenReturn(0L);

        mockMvc.perform(signedIn(get(UNREAD_COUNT_URL), TOKEN)).andExpect(status().isOk());

        verify(notificationService).countMyUnread();
        verify(notificationService, never()).getMyNotifications(anyInt(), anyInt());
    }

    // --------------------------------------------------------------------------------------------- PATCH one as read

    @Test
    void markAsRead_returns200WithTheNotificationNowRead() throws Exception {
        when(notificationService.markAsRead(NOTIFICATION_ID)).thenReturn(sampleNotification(true));

        mockMvc.perform(signedIn(patch(URL + "/" + NOTIFICATION_ID + "/read"), TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Đã đánh dấu thông báo là đã đọc"))
                .andExpect(jsonPath("$.data.id").value(7))
                .andExpect(jsonPath("$.data.isRead").value(true))
                .andExpect(jsonPath("$.data.budgetId").value(100));

        verify(notificationService).markAsRead(NOTIFICATION_ID);
    }

    @Test
    void markAsRead_ofAMissingOrSomeoneElsesNotification_is404_withTheSameMessage() throws Exception {
        when(notificationService.markAsRead(NOTIFICATION_ID))
                .thenThrow(new NotificationNotFoundException("Không tìm thấy thông báo"));

        mockMvc.perform(signedIn(patch(URL + "/" + NOTIFICATION_ID + "/read"), TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("Không tìm thấy thông báo"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void markAsRead_withAnIdThatIsNotANumber_is400_andNeverReachesTheService() throws Exception {
        mockMvc.perform(signedIn(patch(URL + "/abc/read"), TOKEN))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(notificationService);
    }

    // --------------------------------------------------------------------------------------------- PATCH all as read

    @Test
    void readAll_returns200WithHowManyChanged() throws Exception {
        when(notificationService.markAllAsRead()).thenReturn(4);

        mockMvc.perform(signedIn(patch(READ_ALL_URL), TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Đã đánh dấu tất cả thông báo là đã đọc"))
                .andExpect(jsonPath("$.data").value(4));
    }

    @Test
    void readAll_isNeverTakenForTheReadOfOneNotification() throws Exception {
        when(notificationService.markAllAsRead()).thenReturn(0);

        mockMvc.perform(signedIn(patch(READ_ALL_URL).param("userId", "999"), TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(0));

        verify(notificationService).markAllAsRead();
        verify(notificationService, never()).markAsRead(anyLong());
    }

    // ------------------------------------------------------------------------------------------------------- security

    static Stream<Arguments> everyEndpoint() {
        return Stream.of(
                Arguments.of("list", get(URL)),
                Arguments.of("unread count", get(UNREAD_COUNT_URL)),
                Arguments.of("mark one", patch(URL + "/" + NOTIFICATION_ID + "/read")),
                Arguments.of("mark all", patch(READ_ALL_URL)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyEndpoint")
    void rejectsARequestWithoutAToken_andNeverReachesTheService(String name, MockHttpServletRequestBuilder request)
            throws Exception {
        mockMvc.perform(request).andExpect(status().is4xxClientError());

        verifyNoInteractions(notificationService);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyEndpoint")
    void rejectsARequestWithAnInvalidToken_andNeverReachesTheService(String name, MockHttpServletRequestBuilder request)
            throws Exception {
        mockMvc.perform(signedIn(request, BAD_TOKEN)).andExpect(status().is4xxClientError());

        verifyNoInteractions(notificationService);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyEndpoint")
    void aRevokedTokenGetsA401_andNeverReachesTheService(String name, MockHttpServletRequestBuilder request)
            throws Exception {
        mockMvc.perform(signedIn(request, REVOKED_TOKEN)).andExpect(status().isUnauthorized());

        verifyNoInteractions(notificationService);
    }

    @Test
    void mapsUserNotFoundException_to404() throws Exception {
        when(notificationService.countMyUnread()).thenThrow(new UserNotFoundException("Không tìm thấy người dùng đang đăng nhập"));

        mockMvc.perform(signedIn(get(UNREAD_COUNT_URL), TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("Không tìm thấy người dùng đang đăng nhập"));
    }
}
