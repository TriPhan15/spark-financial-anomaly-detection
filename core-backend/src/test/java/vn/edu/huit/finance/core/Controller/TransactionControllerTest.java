package vn.edu.huit.finance.core.Controller;

import io.jsonwebtoken.MalformedJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.web.bind.annotation.RequestParam;
import vn.edu.huit.finance.core.Config.SecurityConfig;
import vn.edu.huit.finance.core.Dto.AppDashboardResponseDto;
import vn.edu.huit.finance.core.Dto.PopularVendorResponseDto;
import vn.edu.huit.finance.core.Dto.TransactionFilterRequestDto;
import vn.edu.huit.finance.core.Dto.TransactionRequestDto;
import vn.edu.huit.finance.core.Dto.TransactionResponseDto;
import vn.edu.huit.finance.core.Dto.TransactionUpdateRequestDto;
import vn.edu.huit.finance.core.Entity.Receipt;
import vn.edu.huit.finance.core.Entity.Transaction;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.GlobalExceptionHandler;
import vn.edu.huit.finance.core.Exception.InsufficientBalanceException;
import vn.edu.huit.finance.core.Exception.InvalidCategoryException;
import vn.edu.huit.finance.core.Exception.InvalidSortFieldException;
import vn.edu.huit.finance.core.Exception.ReceiptNotFoundException;
import vn.edu.huit.finance.core.Exception.TransactionAccessDeniedException;
import vn.edu.huit.finance.core.Exception.TransactionNotFoundException;
import vn.edu.huit.finance.core.Exception.UserNotFoundException;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Security.CustomUserDetailsService;
import vn.edu.huit.finance.core.Security.JwtAuthFilter;
import vn.edu.huit.finance.core.Security.JwtUtil;
import vn.edu.huit.finance.core.Security.TokenClaims;
import vn.edu.huit.finance.core.Security.TokenType;
import vn.edu.huit.finance.core.Service.LoginAttemptService;
import vn.edu.huit.finance.core.Service.TokenBlacklistService;
import vn.edu.huit.finance.core.Service.TransactionService;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The user-facing transaction endpoints through the real security filter chain, JwtAuthFilter and GlobalExceptionHandler.
 * The service is a mock, so these tests are about what the HTTP layer accepts, whom it acts for, and which status it
 * answers; the money itself is covered in TransactionServiceTest and TransactionServiceRollbackTest.
 */
@WebMvcTest(TransactionController.class)
@ContextConfiguration(classes = {
        TransactionController.class, GlobalExceptionHandler.class, SecurityConfig.class, JwtAuthFilter.class})
class TransactionControllerTest {

    private static final String URL = "/api/transactions";
    private static final String TOKEN = "valid.jwt.token";
    private static final String BAD_TOKEN = "invalid.jwt.token";
    private static final String USERNAME = "alice";
    private static final String VALIDATION_PREFIX = "Dữ liệu không hợp lệ";
    /** The name as it goes over the wire; the mobile app reads it from the {@code receiptStatus} key. */
    private static final String RECEIPT_STATUS = "SUCCESS";

    @Autowired private MockMvc mockMvc;

    @MockBean private TransactionService transactionService;
    @MockBean private JwtUtil jwtUtil;
    @MockBean private CustomUserDetailsService userDetailsService;
    @MockBean private UserRepository userRepository;
    @MockBean private LoginAttemptService loginAttemptService;
    @MockBean private TokenBlacklistService tokenBlacklistService;

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

    private static MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
    }

    private static MockHttpServletRequestBuilder withBody(HttpMethod method, String path, String body) {
        return request(method, path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static TransactionResponseDto sample() {
        return TransactionResponseDto.builder()
                .id(100L).title("Bún bò").amount(new BigDecimal("150000")).date(LocalDateTime.of(2026, 9, 1, 12, 0))
                .category("Ăn uống").isExpense(true).receiptStatus(RECEIPT_STATUS).build();
    }

    // ------------------------------------------------------------------------------------------------ authentication

    static Stream<Arguments> everyEndpoint() {
        return Stream.of(
                Arguments.of(HttpMethod.GET, URL + "/mine"),
                Arguments.of(HttpMethod.POST, URL + "/checkout"),
                Arguments.of(HttpMethod.PUT, URL + "/100"),
                Arguments.of(HttpMethod.DELETE, URL + "/100"),
                Arguments.of(HttpMethod.GET, URL + "/filter"),
                Arguments.of(HttpMethod.PUT, URL + "/cancel/7"),
                Arguments.of(HttpMethod.GET, URL + "/popular-vendors"),
                Arguments.of(HttpMethod.GET, URL + "/app-dashboard"));
    }

    @ParameterizedTest(name = "{0} {1} without a token")
    @MethodSource("everyEndpoint")
    void withoutATokenNothingReachesTheService(HttpMethod method, String path) throws Exception {
        mockMvc.perform(withBody(method, path, "{}")).andExpect(status().is4xxClientError());

        verifyNoInteractions(transactionService);
    }

    @ParameterizedTest(name = "{0} {1} with an invalid token")
    @MethodSource("everyEndpoint")
    void withAnInvalidTokenNothingReachesTheService(HttpMethod method, String path) throws Exception {
        mockMvc.perform(withBody(method, path, "{}").header(HttpHeaders.AUTHORIZATION, "Bearer " + BAD_TOKEN))
                .andExpect(status().is4xxClientError());

        verifyNoInteractions(transactionService);
    }

    // ------------------------------------------------------------------------------------------------ PUT /{id}

    private static final String VALID_UPDATE = "{\"amount\":150000,\"categoryName\":\"Cà phê\",\"note\":\"Cà phê sáng\","
            + "\"transactionDate\":\"2026-09-01T08:30:00\",\"paymentMethod\":\"Tiền mặt\",\"isExpense\":true}";

    @Test
    void updatePassesTheEditableFieldsToTheServiceAndAnswers200() throws Exception {
        when(transactionService.updateTransaction(anyLong(), any())).thenReturn(sample());

        mockMvc.perform(signedIn(withBody(HttpMethod.PUT, URL + "/100", VALID_UPDATE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Cập nhật giao dịch thành công"))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.amount").value(150000))
                .andExpect(jsonPath("$.data.category").value("Ăn uống"))
                .andExpect(jsonPath("$.data.isExpense").value(true))
                .andExpect(jsonPath("$.data.receiptStatus").value(RECEIPT_STATUS));

        ArgumentCaptor<TransactionUpdateRequestDto> sent = ArgumentCaptor.forClass(TransactionUpdateRequestDto.class);
        verify(transactionService).updateTransaction(eq(100L), sent.capture());
        assertEquals(new BigDecimal("150000"), sent.getValue().getAmount());
        assertEquals("Cà phê", sent.getValue().getCategoryName());
        assertEquals("Cà phê sáng", sent.getValue().getNote());
        assertEquals(LocalDateTime.of(2026, 9, 1, 8, 30), sent.getValue().getTransactionDate());
        assertEquals("Tiền mặt", sent.getValue().getPaymentMethod());
        assertEquals(Boolean.TRUE, sent.getValue().getExpense());
    }

    @Test
    void updateRunsAsTheJwtUserWhateverTheBodyOrTheQuerySaysAboutUsers() throws Exception {
        AtomicReference<String> callerSeenByService = new AtomicReference<>();
        when(transactionService.updateTransaction(anyLong(), any())).thenAnswer(invocation -> {
            callerSeenByService.set(SecurityContextHolder.getContext().getAuthentication().getName());
            return sample();
        });
        String bodyWithForeignUser = "{\"userId\":999,\"user\":{\"id\":999},\"amount\":150000}";

        mockMvc.perform(signedIn(withBody(HttpMethod.PUT, URL + "/100?userId=999", bodyWithForeignUser)))
                .andExpect(status().isOk());

        assertEquals(USERNAME, callerSeenByService.get());
    }

    @Test
    void anEmptyBodyIsAValidUpdateThatChangesNothing() throws Exception {
        when(transactionService.updateTransaction(anyLong(), any())).thenReturn(sample());

        mockMvc.perform(signedIn(withBody(HttpMethod.PUT, URL + "/100", "{}"))).andExpect(status().isOk());
    }

    static Stream<Arguments> invalidUpdates() {
        return Stream.of(
                Arguments.of("a zero amount", "{\"amount\":0}", "Số tiền giao dịch phải lớn hơn 0"),
                Arguments.of("a negative amount", "{\"amount\":-5}", "Số tiền giao dịch phải lớn hơn 0"),
                Arguments.of("too many decimals", "{\"amount\":1.234}", "chữ số thập phân"),
                Arguments.of("too many integer digits", "{\"amount\":123456789012345678}", "chữ số nguyên"),
                Arguments.of("a category id of zero", "{\"categoryId\":0}", "Mã danh mục không hợp lệ"),
                Arguments.of("a negative category id", "{\"categoryId\":-3}", "Mã danh mục không hợp lệ"),
                Arguments.of("a category name that is too long", "{\"categoryName\":\"" + "x".repeat(256) + "\"}", "Tên danh mục tối đa"),
                Arguments.of("a note that is too long", "{\"note\":\"" + "x".repeat(256) + "\"}", "Ghi chú tối đa"),
                Arguments.of("a blank payment method", "{\"paymentMethod\":\"   \"}", "Nguồn tiền không được để trống"),
                Arguments.of("an empty payment method", "{\"paymentMethod\":\"\"}", "Nguồn tiền không được để trống"),
                Arguments.of("a payment method that is too long", "{\"paymentMethod\":\"" + "x".repeat(256) + "\"}", "Nguồn tiền tối đa"));
    }

    @ParameterizedTest(name = "rejects {0}")
    @MethodSource("invalidUpdates")
    void updateRejectsInvalidBodiesWith400AndNeverReachesTheService(String description, String body, String message) throws Exception {
        mockMvc.perform(signedIn(withBody(HttpMethod.PUT, URL + "/100", body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(startsWith(VALIDATION_PREFIX)))
                .andExpect(jsonPath("$.message").value(containsString(message)));

        verifyNoInteractions(transactionService);
    }

    @ParameterizedTest(name = "accepts {0}")
    @MethodSource("boundaryUpdates")
    void updateAcceptsTheBoundaryCases(String description, String body) throws Exception {
        when(transactionService.updateTransaction(anyLong(), any())).thenReturn(sample());

        mockMvc.perform(signedIn(withBody(HttpMethod.PUT, URL + "/100", body))).andExpect(status().isOk());
    }

    static Stream<Arguments> boundaryUpdates() {
        return Stream.of(
                Arguments.of("the smallest amount", "{\"amount\":0.01}"),
                Arguments.of("the largest amount", "{\"amount\":99999999999999999.99}"),
                Arguments.of("a note of exactly 255 characters", "{\"note\":\"" + "x".repeat(255) + "\"}"),
                Arguments.of("a blank note, which clears it", "{\"note\":\"   \"}"),
                Arguments.of("an income", "{\"isExpense\":false}"));
    }

    @Test
    void updateWithMalformedJsonOrANonNumericIdIsA400() throws Exception {
        mockMvc.perform(signedIn(withBody(HttpMethod.PUT, URL + "/100", "{not json")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(signedIn(withBody(HttpMethod.PUT, URL + "/abc", "{}")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(transactionService);
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
                Arguments.of(new TransactionAccessDeniedException("Bạn không có quyền thao tác trên giao dịch này"), 403,
                        "Bạn không có quyền thao tác trên giao dịch này"),
                Arguments.of(new TransactionNotFoundException("Không tìm thấy giao dịch"), 404, "Không tìm thấy giao dịch"),
                Arguments.of(new InsufficientBalanceException("GIAO DỊCH THẤT BẠI: Số dư ví không đủ!"), 400,
                        "GIAO DỊCH THẤT BẠI: Số dư ví không đủ!"),
                Arguments.of(new InvalidCategoryException("Không tìm thấy danh mục"), 400, "Không tìm thấy danh mục"));
    }

    @ParameterizedTest(name = "update: {0}")
    @MethodSource("refusals")
    void updateAnswersEachRefusalOfTheServiceWithItsOwnStatus(RuntimeException refusal, int status, String message) throws Exception {
        when(transactionService.updateTransaction(anyLong(), any())).thenThrow(refusal);

        mockMvc.perform(signedIn(withBody(HttpMethod.PUT, URL + "/100", VALID_UPDATE)))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(status))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // ---------------------------------------------------------------------------------------------- DELETE /{id}

    @Test
    void deleteAsksTheServiceToDeleteTheGivenIdAndAnswers200() throws Exception {
        mockMvc.perform(signedIn(request(HttpMethod.DELETE, URL + "/100")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Đã xóa giao dịch và hoàn tiền thành công!"));

        verify(transactionService).deleteTransaction(100L);
        verifyNoMoreInteractions(transactionService);
    }

    @ParameterizedTest(name = "delete: {0}")
    @MethodSource("deleteRefusals")
    void deleteAnswersTheRefusalsOfTheServiceWithTheirStatus(RuntimeException refusal, int status, String message) throws Exception {
        doThrow(refusal).when(transactionService).deleteTransaction(100L);

        mockMvc.perform(signedIn(request(HttpMethod.DELETE, URL + "/100")))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.message").value(message));
    }

    static Stream<Arguments> deleteRefusals() {
        return refusals().limit(2);
    }

    // ------------------------------------------------------------------------------------------------ POST /checkout

    @Test
    void checkoutRunsForTheSignedInUserAndKeepsItsResponse() throws Exception {
        when(transactionService.createMyTransaction(any())).thenReturn(Map.of("transactionId", 5, "finalAmount", 10000));
        String body = "{\"amount\":10000,\"location\":\"Quán A\",\"paymentMethod\":\"Ví chính\","
                + "\"transactionDate\":\"2026-09-01T08:30:00\",\"categoryId\":10,\"isExpense\":true,\"userId\":999}";

        mockMvc.perform(signedIn(withBody(HttpMethod.POST, URL + "/checkout", body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Lưu giao dịch thành công!"))
                .andExpect(jsonPath("$.data.transactionId").value(5));

        ArgumentCaptor<TransactionRequestDto> sent = ArgumentCaptor.forClass(TransactionRequestDto.class);
        verify(transactionService).createMyTransaction(sent.capture());
        assertEquals(new BigDecimal("10000"), sent.getValue().getAmount());
        verify(transactionService, never()).createTransaction(any(), any());
    }

    @Test
    void checkoutRejectsAnInvalidBodyAndAnswersAFailureOfTheServiceAs400() throws Exception {
        mockMvc.perform(signedIn(withBody(HttpMethod.POST, URL + "/checkout", "{\"amount\":-1}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(startsWith(VALIDATION_PREFIX)));
        verifyNoInteractions(transactionService);

        when(transactionService.createMyTransaction(any())).thenThrow(new RuntimeException("GIAO DỊCH THẤT BẠI: Số dư ví không đủ!"));
        String body = "{\"amount\":10000,\"location\":\"Quán A\",\"paymentMethod\":\"Ví chính\","
                + "\"transactionDate\":\"2026-09-01T08:30:00\",\"categoryId\":10,\"isExpense\":true}";
        mockMvc.perform(signedIn(withBody(HttpMethod.POST, URL + "/checkout", body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("GIAO DỊCH THẤT BẠI: Số dư ví không đủ!"));
    }

    // ------------------------------------------------------------------- POST /checkout: the optional client id

    private static String checkoutBodyWith(String extraFields) {
        return "{\"amount\":10000,\"location\":\"Quán A\",\"paymentMethod\":\"Ví chính\","
                + "\"transactionDate\":\"2026-09-01T08:30:00\",\"categoryId\":10,\"isExpense\":true" + extraFields + "}";
    }

    private TransactionRequestDto checkoutSentToTheService() {
        ArgumentCaptor<TransactionRequestDto> sent = ArgumentCaptor.forClass(TransactionRequestDto.class);
        verify(transactionService).createMyTransaction(sent.capture());
        return sent.getValue();
    }

    @Test
    void checkoutPassesTheClientIdOnToTheService() throws Exception {
        when(transactionService.createMyTransaction(any())).thenReturn(Map.of("transactionId", 5));
        String clientId = "0b7e6f1c-5f0a-4a55-9d1f-2f6f6b1c9a11";

        mockMvc.perform(signedIn(withBody(HttpMethod.POST, URL + "/checkout", checkoutBodyWith(",\"clientId\":\"" + clientId + "\""))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transactionId").value(5));

        assertEquals(clientId, checkoutSentToTheService().getClientId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"clientId\":null", ",\"clientId\":\"\"", ",\"clientId\":\"   \""})
    void checkoutWithoutAUsableClientIdIsStillAcceptedAndTheServiceDecides(String extraFields) throws Exception {
        when(transactionService.createMyTransaction(any())).thenReturn(Map.of("transactionId", 5));

        mockMvc.perform(signedIn(withBody(HttpMethod.POST, URL + "/checkout", checkoutBodyWith(extraFields))))
                .andExpect(status().isOk());

        TransactionRequestDto sent = checkoutSentToTheService();
        assertTrue(sent.getClientId() == null || sent.getClientId().isBlank());
    }

    @Test
    void aClientIdOfExactlyTheColumnWidthIsAccepted() throws Exception {
        when(transactionService.createMyTransaction(any())).thenReturn(Map.of("transactionId", 5));
        String widest = "x".repeat(Transaction.CLIENT_ID_MAX_LENGTH);

        mockMvc.perform(signedIn(withBody(HttpMethod.POST, URL + "/checkout", checkoutBodyWith(",\"clientId\":\"" + widest + "\""))))
                .andExpect(status().isOk());

        assertEquals(widest, checkoutSentToTheService().getClientId());
    }

    @Test
    void aClientIdLongerThanTheColumnIsRejectedWith400AndNeverReachesTheService() throws Exception {
        String tooLong = "x".repeat(Transaction.CLIENT_ID_MAX_LENGTH + 1);

        mockMvc.perform(signedIn(withBody(HttpMethod.POST, URL + "/checkout", checkoutBodyWith(",\"clientId\":\"" + tooLong + "\""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(startsWith(VALIDATION_PREFIX)))
                .andExpect(jsonPath("$.message").value(containsString("clientId")));

        verifyNoInteractions(transactionService);
    }

    // ------------------------------------------------------------- POST /checkout: the answer of the AI for a scan

    @Test
    void anAnswerOfTheAiOfExactlyTheLongestLengthAReceiptKeepsIsAccepted() throws Exception {
        when(transactionService.createMyTransaction(any())).thenReturn(Map.of("transactionId", 5));
        String longest = "x".repeat(Receipt.AI_RAW_DATA_MAX_LENGTH);

        mockMvc.perform(signedIn(withBody(HttpMethod.POST, URL + "/checkout",
                        checkoutBodyWith(",\"rawReceiptData\":\"" + longest + "\""))))
                .andExpect(status().isOk());

        assertEquals(longest, checkoutSentToTheService().getRawReceiptData());
    }

    @Test
    void anAnswerOfTheAiLongerThanAReceiptKeepsIsRejectedWith400AndNeverReachesTheService() throws Exception {
        String tooLong = "x".repeat(Receipt.AI_RAW_DATA_MAX_LENGTH + 1);

        mockMvc.perform(signedIn(withBody(HttpMethod.POST, URL + "/checkout",
                        checkoutBodyWith(",\"rawReceiptData\":\"" + tooLong + "\""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(startsWith(VALIDATION_PREFIX)))
                .andExpect(jsonPath("$.message").value(containsString("rawReceiptData")));

        verifyNoInteractions(transactionService);
    }

    // ---------------------------------------------------------------------- the audited endpoints: no userId any more

    @Test
    void mineAnswersTheCallersTransactions() throws Exception {
        when(transactionService.getMyTransactions()).thenReturn(List.of(sample()));

        mockMvc.perform(signedIn(request(HttpMethod.GET, URL + "/mine?userId=999")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(100))
                .andExpect(jsonPath("$.data[0].receiptStatus").value(RECEIPT_STATUS));

        verify(transactionService).getMyTransactions();
        verifyNoMoreInteractions(transactionService);
    }

    @Test
    void filterSearchesTheCallersTransactionsAndIgnoresAUserIdInTheQuery() throws Exception {
        AtomicReference<String> callerSeenByService = new AtomicReference<>();
        when(transactionService.filterMyTransactions(any())).thenAnswer(invocation -> {
            callerSeenByService.set(SecurityContextHolder.getContext().getAuthentication().getName());
            return List.of(sample());
        });

        mockMvc.perform(signedIn(request(HttpMethod.GET,
                        URL + "/filter?userId=999&categoryId=10&minPrice=1000&maxPrice=9000&vendorName=Highlands&sortBy=amount&sortDir=ASC")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Lấy dữ liệu thành công"))
                .andExpect(jsonPath("$.data[0].id").value(100));

        assertEquals(USERNAME, callerSeenByService.get());
        ArgumentCaptor<TransactionFilterRequestDto> sent = ArgumentCaptor.forClass(TransactionFilterRequestDto.class);
        verify(transactionService).filterMyTransactions(sent.capture());
        assertEquals(10L, sent.getValue().getCategoryId());
        assertEquals(new BigDecimal("1000"), sent.getValue().getMinPrice());
        assertEquals(new BigDecimal("9000"), sent.getValue().getMaxPrice());
        assertEquals("Highlands", sent.getValue().getVendorName());
        assertEquals("amount", sent.getValue().getSortBy());
        assertEquals("ASC", sent.getValue().getSortDirection());
        verifyNoMoreInteractions(transactionService);
    }

    @Test
    void filterSortsByTheDateNewestFirstUnlessToldOtherwise() throws Exception {
        when(transactionService.filterMyTransactions(any())).thenReturn(List.of());

        mockMvc.perform(signedIn(request(HttpMethod.GET, URL + "/filter"))).andExpect(status().isOk());

        ArgumentCaptor<TransactionFilterRequestDto> sent = ArgumentCaptor.forClass(TransactionFilterRequestDto.class);
        verify(transactionService).filterMyTransactions(sent.capture());
        assertEquals("transactionDate", sent.getValue().getSortBy());
        assertEquals("DESC", sent.getValue().getSortDirection());
        assertNull(sent.getValue().getCategoryId());
    }

    @Test
    void filterAnswers400WhenTheServiceRefusesTheSortField() throws Exception {
        when(transactionService.filterMyTransactions(any())).thenThrow(new InvalidSortFieldException("Trường sắp xếp không hợp lệ"));

        mockMvc.perform(signedIn(request(HttpMethod.GET, URL + "/filter?sortBy=user.password")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Trường sắp xếp không hợp lệ"));
    }

    @Test
    void cancelActsOnTheCallersReceiptOnlyAndIgnoresAUserIdInTheQuery() throws Exception {
        mockMvc.perform(signedIn(request(HttpMethod.PUT, URL + "/cancel/7?userId=999")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Đã hủy hóa đơn thành công!"));

        verify(transactionService).cancelMyReceipt(7L);
        verifyNoMoreInteractions(transactionService);
    }

    @ParameterizedTest(name = "cancel: {0}")
    @MethodSource("cancelRefusals")
    void cancelAnswersTheRefusalsOfTheServiceWithTheirStatus(RuntimeException refusal, int status, String message) throws Exception {
        doThrow(refusal).when(transactionService).cancelMyReceipt(7L);

        mockMvc.perform(signedIn(request(HttpMethod.PUT, URL + "/cancel/7")))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.message").value(message));
    }

    static Stream<Arguments> cancelRefusals() {
        return Stream.of(
                Arguments.of(new TransactionAccessDeniedException("Bạn không có quyền hủy hóa đơn này"), 403, "Bạn không có quyền hủy hóa đơn này"),
                Arguments.of(new ReceiptNotFoundException("Không tìm thấy hóa đơn"), 404, "Không tìm thấy hóa đơn"),
                Arguments.of(new IllegalStateException("Chỉ có thể hủy hóa đơn đang ở trạng thái Chờ xử lý"), 400,
                        "Chỉ có thể hủy hóa đơn đang ở trạng thái Chờ xử lý"));
    }

    @Test
    void popularVendorsAreThoseOfTheCallerNeverOfTheUserIdInTheQuery() throws Exception {
        when(transactionService.getMyPopularVendors()).thenReturn(
                List.of(PopularVendorResponseDto.builder().vendorName("Highlands").transactionCount(5).build()));

        mockMvc.perform(signedIn(request(HttpMethod.GET, URL + "/popular-vendors?userId=2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Lấy danh sách độ phổ biến thành công"))
                .andExpect(jsonPath("$.data[0].vendorName").value("Highlands"))
                .andExpect(jsonPath("$.data[0].transactionCount").value(5));

        verify(transactionService).getMyPopularVendors();
        verify(transactionService, never()).getPopularVendorsOf(any());
    }

    @Test
    void theAppDashboardIsTheCallersNeverTheOneOfTheUserIdInTheQuery() throws Exception {
        when(transactionService.getMyDashboard()).thenReturn(AppDashboardResponseDto.builder()
                .recentTransactions(List.of(sample())).biggestTransaction(sample()).saleTransactions(List.of()).build());

        mockMvc.perform(signedIn(request(HttpMethod.GET, URL + "/app-dashboard?userId=2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Load trang chủ App thành công"))
                .andExpect(jsonPath("$.data.recentTransactions[0].id").value(100))
                .andExpect(jsonPath("$.data.recentTransactions[0].receiptStatus").value(RECEIPT_STATUS))
                .andExpect(jsonPath("$.data.biggestTransaction.id").value(100))
                .andExpect(jsonPath("$.data.saleTransactions").isEmpty());

        verify(transactionService).getMyDashboard();
        verify(transactionService, never()).getDashboardOf(any());
    }

    @Test
    void aUserWhoNoLongerExistsIsAnswered404() throws Exception {
        when(transactionService.getMyTransactions()).thenThrow(new UserNotFoundException("Không tìm thấy người dùng đang đăng nhập"));

        mockMvc.perform(signedIn(request(HttpMethod.GET, URL + "/mine"))).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------------------------------ structural guards

    @Test
    void noHandlerOfTheUserControllerReadsAUserIdFromTheRequest() {
        for (Method method : TransactionController.class.getDeclaredMethods()) {
            for (Parameter parameter : method.getParameters()) {
                assertFalse(parameter.getName().equalsIgnoreCase("userId"),
                        method.getName() + " must not take a userId: " + parameter);
                RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
                if (requestParam != null) {
                    assertFalse(Arrays.asList(requestParam.name(), requestParam.value()).contains("userId"),
                            method.getName() + " must not bind the request parameter userId");
                }
            }
        }
    }
}
