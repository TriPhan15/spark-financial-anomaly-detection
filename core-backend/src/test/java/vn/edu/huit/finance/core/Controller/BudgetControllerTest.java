package vn.edu.huit.finance.core.Controller;

import io.jsonwebtoken.MalformedJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.edu.huit.finance.core.Config.SecurityConfig;
import vn.edu.huit.finance.core.Dto.BudgetRequestDto;
import vn.edu.huit.finance.core.Dto.BudgetResponseDto;
import vn.edu.huit.finance.core.Entity.BudgetPeriodType;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.BudgetNotFoundException;
import vn.edu.huit.finance.core.Exception.DuplicateBudgetException;
import vn.edu.huit.finance.core.Exception.GlobalExceptionHandler;
import vn.edu.huit.finance.core.Exception.InvalidCategoryException;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Security.CustomUserDetailsService;
import vn.edu.huit.finance.core.Security.JwtAuthFilter;
import vn.edu.huit.finance.core.Security.JwtUtil;
import vn.edu.huit.finance.core.Security.TokenClaims;
import vn.edu.huit.finance.core.Security.TokenType;
import vn.edu.huit.finance.core.Service.BudgetService;
import vn.edu.huit.finance.core.Service.LoginAttemptService;
import vn.edu.huit.finance.core.Service.TokenBlacklistService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real security filter chain + JwtAuthFilter + GlobalExceptionHandler around /api/budgets. Explicit
 * context classes keep the app class (@EnableJpaAuditing) out of the slice.
 */
@WebMvcTest(BudgetController.class)
@ContextConfiguration(classes = {
        BudgetController.class, GlobalExceptionHandler.class, SecurityConfig.class, JwtAuthFilter.class})
class BudgetControllerTest {

    private static final String URL = "/api/budgets";
    private static final String TOKEN = "valid.jwt.token";
    private static final String BAD_TOKEN = "invalid.jwt.token";
    private static final String USERNAME = "alice";
    private static final String VALIDATION_MESSAGE_PREFIX = "Dữ liệu không hợp lệ";

    private static final String CATEGORY = "\"categoryId\":10";
    private static final String AMOUNT = "\"amount\":2000000";
    private static final String MONTH = "\"periodType\":\"MONTH\"";
    private static final String WEEK = "\"periodType\":\"WEEK\"";
    private static final String SEPTEMBER_FIRST = "\"startDate\":\"2026-09-01\"";
    private static final String SEPTEMBER_LAST = "\"endDate\":\"2026-09-30\"";
    private static final String VALID_BODY = json(CATEGORY, AMOUNT, MONTH, SEPTEMBER_FIRST, SEPTEMBER_LAST);
    // userId in the payload must be ignored: the owner is always the caller taken from the JWT.
    private static final String BODY_WITH_FOREIGN_USER_ID =
            json("\"userId\":999", CATEGORY, AMOUNT, MONTH, SEPTEMBER_FIRST, SEPTEMBER_LAST);

    @Autowired private MockMvc mockMvc;

    @MockBean private BudgetService budgetService;
    @MockBean private JwtUtil jwtUtil;
    @MockBean private CustomUserDetailsService userDetailsService;
    @MockBean private UserRepository userRepository;
    @MockBean private LoginAttemptService loginAttemptService;
    @MockBean private TokenBlacklistService tokenBlacklistService;

    private static String json(String... fields) {
        return "{" + String.join(",", fields) + "}";
    }

    private static BudgetResponseDto sampleBudget() {
        return BudgetResponseDto.builder()
                .id(7L).categoryId(10L).categoryName("Ăn uống")
                .amount(new BigDecimal("2000000.00")).periodType(BudgetPeriodType.MONTH)
                .startDate(LocalDate.of(2026, 9, 1)).endDate(LocalDate.of(2026, 9, 30))
                .build();
    }

    @BeforeEach
    void stubJwtForAlice() {
        when(jwtUtil.parse(TOKEN, TokenType.ACCESS)).thenReturn(new TokenClaims(USERNAME, 0));
        when(jwtUtil.parse(BAD_TOKEN, TokenType.ACCESS)).thenThrow(new MalformedJwtException("not a valid token"));

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

    private MockHttpServletRequestBuilder withBody(HttpMethod method, String path, String body) {
        return request(method, path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
    }

    // ---- authentication ----

    static Stream<Arguments> everyEndpoint() {
        return Stream.of(
                Arguments.of(HttpMethod.GET, URL),
                Arguments.of(HttpMethod.GET, URL + "/7"),
                Arguments.of(HttpMethod.POST, URL),
                Arguments.of(HttpMethod.PUT, URL + "/7"),
                Arguments.of(HttpMethod.DELETE, URL + "/7"));
    }

    @ParameterizedTest
    @MethodSource("everyEndpoint")
    void rejectsRequestsWithoutAToken_andNeverReachesTheService(HttpMethod method, String path) throws Exception {
        mockMvc.perform(withBody(method, path, VALID_BODY))
                .andExpect(status().is4xxClientError());

        verifyNoInteractions(budgetService);
    }

    @ParameterizedTest
    @MethodSource("everyEndpoint")
    void rejectsRequestsWithAnInvalidToken_andNeverReachesTheService(HttpMethod method, String path) throws Exception {
        mockMvc.perform(withBody(method, path, VALID_BODY).header(HttpHeaders.AUTHORIZATION, "Bearer " + BAD_TOKEN))
                .andExpect(status().is4xxClientError());

        verifyNoInteractions(budgetService);
    }

    // ---- CRUD contract ----

    @Test
    void list_returns200InTheApiResponseEnvelope_andTheServiceRunsAsTheJwtUser_ignoringUserIdInTheQuery() throws Exception {
        AtomicReference<String> callerSeenByService = new AtomicReference<>();
        when(budgetService.getMyBudgets()).thenAnswer(invocation -> {
            callerSeenByService.set(SecurityContextHolder.getContext().getAuthentication().getName());
            return List.of(sampleBudget());
        });

        // The mobile app currently sends its own userId, month and year; none of them may influence the result.
        mockMvc.perform(signedIn(request(HttpMethod.GET, URL + "?userId=999&month=9&year=2026")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Lấy danh sách ngân sách thành công"))
                .andExpect(jsonPath("$.data[0].id").value(7))
                .andExpect(jsonPath("$.data[0].categoryId").value(10))
                .andExpect(jsonPath("$.data[0].categoryName").value("Ăn uống"))
                .andExpect(jsonPath("$.data[0].amount").value(2000000.00))
                .andExpect(jsonPath("$.data[0].periodType").value("MONTH"))
                .andExpect(jsonPath("$.data[0].startDate").value("2026-09-01"))
                .andExpect(jsonPath("$.data[0].endDate").value("2026-09-30"));

        assertEquals(USERNAME, callerSeenByService.get());
        verify(budgetService).getMyBudgets();
        verifyNoMoreInteractions(budgetService);
    }

    @Test
    void get_returns200WithTheBudget() throws Exception {
        when(budgetService.getMyBudget(7L)).thenReturn(sampleBudget());

        mockMvc.perform(signedIn(request(HttpMethod.GET, URL + "/7")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(7));
    }

    @Test
    void create_returns201WithTheCreatedBudget_andTheServiceRunsAsTheJwtUser_ignoringUserIdInThePayload() throws Exception {
        AtomicReference<String> callerSeenByService = new AtomicReference<>();
        when(budgetService.createBudget(any())).thenAnswer(invocation -> {
            callerSeenByService.set(SecurityContextHolder.getContext().getAuthentication().getName());
            return sampleBudget();
        });

        mockMvc.perform(signedIn(withBody(HttpMethod.POST, URL, BODY_WITH_FOREIGN_USER_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(201))
                .andExpect(jsonPath("$.message").value("Tạo ngân sách thành công"))
                .andExpect(jsonPath("$.data.id").value(7));

        assertEquals(USERNAME, callerSeenByService.get());
        ArgumentCaptor<BudgetRequestDto> sent = ArgumentCaptor.forClass(BudgetRequestDto.class);
        verify(budgetService).createBudget(sent.capture());
        assertEquals(10L, sent.getValue().getCategoryId());
        assertEquals(new BigDecimal("2000000"), sent.getValue().getAmount());
        assertEquals(BudgetPeriodType.MONTH, sent.getValue().getPeriodType());
        assertEquals(LocalDate.of(2026, 9, 1), sent.getValue().getStartDate());
        assertEquals(LocalDate.of(2026, 9, 30), sent.getValue().getEndDate());
    }

    @Test
    void update_returns200WithTheUpdatedBudget() throws Exception {
        when(budgetService.updateBudget(eq(7L), any())).thenReturn(sampleBudget());

        mockMvc.perform(signedIn(withBody(HttpMethod.PUT, URL + "/7", VALID_BODY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Cập nhật ngân sách thành công"))
                .andExpect(jsonPath("$.data.id").value(7));
    }

    @Test
    void delete_returns200WithoutData() throws Exception {
        mockMvc.perform(signedIn(request(HttpMethod.DELETE, URL + "/7")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Xóa ngân sách thành công"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(budgetService).deleteBudget(7L);
    }

    // ---- validation ----

    static Stream<Arguments> validBodies() {
        return Stream.of(
                Arguments.of("a budget for every category (no categoryId)", json(AMOUNT, MONTH, SEPTEMBER_FIRST, SEPTEMBER_LAST)),
                Arguments.of("a week of exactly 7 days", json(CATEGORY, AMOUNT, WEEK, SEPTEMBER_FIRST, "\"endDate\":\"2026-09-07\"")),
                Arguments.of("a month of exactly 31 days", json(CATEGORY, AMOUNT, MONTH, "\"startDate\":\"2026-08-01\"", "\"endDate\":\"2026-08-31\"")),
                Arguments.of("a single-day range", json(CATEGORY, AMOUNT, WEEK, SEPTEMBER_FIRST, SEPTEMBER_FIRST.replace("startDate", "endDate"))),
                Arguments.of("an amount with two decimals", json(CATEGORY, "\"amount\":1234.56", MONTH, SEPTEMBER_FIRST, SEPTEMBER_LAST)));
    }

    @ParameterizedTest(name = "accepts {0}")
    @MethodSource("validBodies")
    void create_acceptsTheBoundaryCasesOfTheRules(String description, String body) throws Exception {
        when(budgetService.createBudget(any())).thenReturn(sampleBudget());

        mockMvc.perform(signedIn(withBody(HttpMethod.POST, URL, body)))
                .andExpect(status().isCreated());
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("missing amount", json(CATEGORY, MONTH, SEPTEMBER_FIRST, SEPTEMBER_LAST),
                        "Số tiền ngân sách không được để trống"),
                Arguments.of("zero amount", json(CATEGORY, "\"amount\":0", MONTH, SEPTEMBER_FIRST, SEPTEMBER_LAST),
                        "Số tiền ngân sách phải lớn hơn 0"),
                Arguments.of("negative amount", json(CATEGORY, "\"amount\":-5", MONTH, SEPTEMBER_FIRST, SEPTEMBER_LAST),
                        "Số tiền ngân sách phải lớn hơn 0"),
                Arguments.of("three decimals", json(CATEGORY, "\"amount\":100.123", MONTH, SEPTEMBER_FIRST, SEPTEMBER_LAST),
                        "chữ số thập phân"),
                Arguments.of("more digits than the column holds",
                        json(CATEGORY, "\"amount\":123456789012345678", MONTH, SEPTEMBER_FIRST, SEPTEMBER_LAST),
                        "chữ số nguyên"),
                Arguments.of("missing period type", json(CATEGORY, AMOUNT, SEPTEMBER_FIRST, SEPTEMBER_LAST),
                        "Loại kỳ hạn không được để trống"),
                Arguments.of("missing start date", json(CATEGORY, AMOUNT, MONTH, SEPTEMBER_LAST),
                        "Ngày bắt đầu không được để trống"),
                Arguments.of("missing end date", json(CATEGORY, AMOUNT, MONTH, SEPTEMBER_FIRST),
                        "Ngày kết thúc không được để trống"),
                Arguments.of("end date before start date",
                        json(CATEGORY, AMOUNT, MONTH, "\"startDate\":\"2026-09-30\"", "\"endDate\":\"2026-09-01\""),
                        "Ngày kết thúc không được trước ngày bắt đầu"),
                Arguments.of("a week of 8 days", json(CATEGORY, AMOUNT, WEEK, SEPTEMBER_FIRST, "\"endDate\":\"2026-09-08\""),
                        "vượt quá độ dài của kỳ hạn"),
                Arguments.of("a month of 32 days", json(CATEGORY, AMOUNT, MONTH, SEPTEMBER_FIRST, "\"endDate\":\"2026-10-02\""),
                        "vượt quá độ dài của kỳ hạn"),
                Arguments.of("category id 0", json("\"categoryId\":0", AMOUNT, MONTH, SEPTEMBER_FIRST, SEPTEMBER_LAST),
                        "Mã danh mục không hợp lệ"),
                Arguments.of("negative category id", json("\"categoryId\":-3", AMOUNT, MONTH, SEPTEMBER_FIRST, SEPTEMBER_LAST),
                        "Mã danh mục không hợp lệ"));
    }

    @ParameterizedTest(name = "create rejects {0}")
    @MethodSource("invalidBodies")
    void create_returns400WithTheReason_andSkipsTheService(String description, String body, String message) throws Exception {
        mockMvc.perform(signedIn(withBody(HttpMethod.POST, URL, body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(startsWith(VALIDATION_MESSAGE_PREFIX)))
                .andExpect(jsonPath("$.message").value(containsString(message)));

        verifyNoInteractions(budgetService);
    }

    @ParameterizedTest(name = "update rejects {0}")
    @MethodSource("invalidBodies")
    void update_returns400WithTheReason_andSkipsTheService(String description, String body, String message) throws Exception {
        mockMvc.perform(signedIn(withBody(HttpMethod.PUT, URL + "/7", body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(containsString(message)));

        verifyNoInteractions(budgetService);
    }

    @ParameterizedTest(name = "rejects {0}")
    @MethodSource("malformedBodies")
    void create_returns400_andSkipsTheService_whenTheBodyCannotBeRead(String description, String body) throws Exception {
        mockMvc.perform(signedIn(withBody(HttpMethod.POST, URL, body)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(budgetService);
    }

    static Stream<Arguments> malformedBodies() {
        return Stream.of(
                Arguments.of("an unknown period type", json(CATEGORY, AMOUNT, "\"periodType\":\"YEAR\"", SEPTEMBER_FIRST, SEPTEMBER_LAST)),
                Arguments.of("a date that is not a date", json(CATEGORY, AMOUNT, MONTH, "\"startDate\":\"not-a-date\"", SEPTEMBER_LAST)),
                Arguments.of("text that is not JSON", "this is not json"));
    }

    @Test
    void get_returns400_whenTheIdIsNotANumber() throws Exception {
        mockMvc.perform(signedIn(request(HttpMethod.GET, URL + "/abc")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(budgetService);
    }

    // ---- failures raised by the service ----

    @Test
    void get_returns404_whenTheBudgetIsMissingOrNotTheCallers() throws Exception {
        when(budgetService.getMyBudget(anyLong())).thenThrow(new BudgetNotFoundException("Không tìm thấy ngân sách"));

        mockMvc.perform(signedIn(request(HttpMethod.GET, URL + "/7")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("Không tìm thấy ngân sách"));
    }

    @Test
    void update_returns404_whenTheBudgetIsMissingOrNotTheCallers() throws Exception {
        when(budgetService.updateBudget(anyLong(), any())).thenThrow(new BudgetNotFoundException("Không tìm thấy ngân sách"));

        mockMvc.perform(signedIn(withBody(HttpMethod.PUT, URL + "/7", VALID_BODY)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void delete_returns404_whenTheBudgetIsMissingOrNotTheCallers() throws Exception {
        doThrow(new BudgetNotFoundException("Không tìm thấy ngân sách")).when(budgetService).deleteBudget(anyLong());

        mockMvc.perform(signedIn(request(HttpMethod.DELETE, URL + "/7")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void create_returns409_whenTheSameCategoryAndPeriodAlreadyExist() throws Exception {
        when(budgetService.createBudget(any()))
                .thenThrow(new DuplicateBudgetException("Ngân sách cho danh mục và kỳ hạn này đã tồn tại"));

        mockMvc.perform(signedIn(withBody(HttpMethod.POST, URL, VALID_BODY)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("Ngân sách cho danh mục và kỳ hạn này đã tồn tại"));
    }

    @Test
    void update_returns409_whenTheSameCategoryAndPeriodAlreadyExist() throws Exception {
        when(budgetService.updateBudget(anyLong(), any()))
                .thenThrow(new DuplicateBudgetException("Ngân sách cho danh mục và kỳ hạn này đã tồn tại"));

        mockMvc.perform(signedIn(withBody(HttpMethod.PUT, URL + "/7", VALID_BODY)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void create_returns400_whenTheCategoryDoesNotExist() throws Exception {
        when(budgetService.createBudget(any())).thenThrow(new InvalidCategoryException("Danh mục không tồn tại"));

        mockMvc.perform(signedIn(withBody(HttpMethod.POST, URL, VALID_BODY)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("Danh mục không tồn tại"));
    }
}
