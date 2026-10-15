package vn.edu.huit.finance.core.Controller;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestParam;
import vn.edu.huit.finance.core.Config.SecurityConfig;
import vn.edu.huit.finance.core.Dto.ReceiptResponseDto;
import vn.edu.huit.finance.core.Dto.ReceiptRetryRequestDto;
import vn.edu.huit.finance.core.Dto.ReceiptUpdateRequestDto;
import vn.edu.huit.finance.core.Entity.Receipt;
import vn.edu.huit.finance.core.Entity.Transaction;
import vn.edu.huit.finance.core.Entity.User;
import vn.edu.huit.finance.core.Exception.GlobalExceptionHandler;
import vn.edu.huit.finance.core.Exception.ReceiptConflictException;
import vn.edu.huit.finance.core.Exception.ReceiptNotFoundException;
import vn.edu.huit.finance.core.Exception.TransactionAccessDeniedException;
import vn.edu.huit.finance.core.Repository.UserRepository;
import vn.edu.huit.finance.core.Security.CustomUserDetailsService;
import vn.edu.huit.finance.core.Security.JwtAuthFilter;
import vn.edu.huit.finance.core.Security.JwtUtil;
import vn.edu.huit.finance.core.Security.TokenClaims;
import vn.edu.huit.finance.core.Security.TokenType;
import vn.edu.huit.finance.core.Service.LoginAttemptService;
import vn.edu.huit.finance.core.Service.TokenBlacklistService;
import vn.edu.huit.finance.core.Service.ReceiptProcessingService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The endpoints of /api/receipts (feedback, reading, editing, retrying) through the real security filter chain,
 * JwtAuthFilter and GlobalExceptionHandler. The service is a mock: these tests are about whom an endpoint acts for, what
 * of a request reaches the service, what of a receipt reaches the client and which status each refusal is answered with;
 * the ownership and lifecycle rules themselves are covered in ReceiptProcessingServiceTest and on a real database.
 */
@WebMvcTest(ReceiptController.class)
@ContextConfiguration(classes = {
        ReceiptController.class, GlobalExceptionHandler.class, SecurityConfig.class, JwtAuthFilter.class})
class ReceiptControllerTest {

    private static final String URL = "/api/receipts/feedback/7";
    private static final String TOKEN = "valid.jwt.token";
    private static final String BAD_TOKEN = "invalid.jwt.token";
    private static final String USERNAME = "alice";

    @Autowired private MockMvc mockMvc;

    @MockBean private ReceiptProcessingService receiptProcessingService;
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

    private static MockHttpServletRequestBuilder signedInPost(String urlWithQuery) {
        return request(HttpMethod.POST, urlWithQuery).header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
    }

    // ------------------------------------------------------------------------------------------------ authentication

    @Test
    void withoutATokenNothingReachesTheService() throws Exception {
        mockMvc.perform(request(HttpMethod.POST, URL + "?manualAmount=150000")).andExpect(status().is4xxClientError());

        verifyNoInteractions(receiptProcessingService);
    }

    @Test
    void withAnInvalidTokenNothingReachesTheService() throws Exception {
        mockMvc.perform(request(HttpMethod.POST, URL + "?manualAmount=150000")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + BAD_TOKEN))
                .andExpect(status().is4xxClientError());

        verifyNoInteractions(receiptProcessingService);
    }

    // ------------------------------------------------------------------------------------------------------ the happy path

    @Test
    void feedbackActsForTheCallerOnlyAndIgnoresAUserIdInTheQuery() throws Exception {
        mockMvc.perform(signedInPost(URL + "?manualAmount=150000&userId=999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Đã gửi báo cáo cho Admin duyệt lại hóa đơn!"));

        verify(receiptProcessingService).submitFeedback(7L, new BigDecimal("150000"));
        verifyNoMoreInteractions(receiptProcessingService);
    }

    // ------------------------------------------------------------------------------------------------------- refusals

    @Test
    void aReceiptOfSomebodyElseIsAnswered403() throws Exception {
        doThrow(new TransactionAccessDeniedException("Bạn không có quyền thao tác trên hóa đơn này"))
                .when(receiptProcessingService).submitFeedback(any(), any());

        mockMvc.perform(signedInPost(URL + "?manualAmount=150000"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("Bạn không có quyền thao tác trên hóa đơn này"));
    }

    static Stream<Arguments> otherRefusals() {
        return Stream.of(
                Arguments.of(new ReceiptNotFoundException("Không tìm thấy hóa đơn"), 404, "Không tìm thấy hóa đơn"),
                Arguments.of(new IllegalStateException("Chỉ có thể gửi yêu cầu xem xét cho các hóa đơn bị lỗi (FAILED)"), 400,
                        "Chỉ có thể gửi yêu cầu xem xét cho các hóa đơn bị lỗi (FAILED)"),
                Arguments.of(new IllegalArgumentException("Số tiền điều chỉnh phải lớn hơn 0"), 400,
                        "Số tiền điều chỉnh phải lớn hơn 0"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("otherRefusals")
    void theOtherRefusalsOfTheServiceKeepTheirStatus(RuntimeException refusal, int status, String message) throws Exception {
        doThrow(refusal).when(receiptProcessingService).submitFeedback(any(), any());

        mockMvc.perform(signedInPost(URL + "?manualAmount=150000"))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.message").value(message));
    }

    @Test
    void theAmountIsRequired() throws Exception {
        mockMvc.perform(signedInPost(URL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Thiếu tham số bắt buộc: manualAmount"));

        verifyNoInteractions(receiptProcessingService);
    }

    @Test
    void anAmountThatIsNotANumberNeverReachesTheService() throws Exception {
        mockMvc.perform(signedInPost(URL + "?manualAmount=abc")).andExpect(status().isBadRequest());

        verifyNoInteractions(receiptProcessingService);
    }

    // ------------------------------------------------------------------ reading, editing and retrying a receipt: who

    private static final String RECEIPT_URL = "/api/receipts/7";
    private static final String BY_TRANSACTION_URL = "/api/receipts/transaction/100";
    private static final String RETRY_URL = "/api/receipts/7/retry";
    private static final String VALID_EDIT = "{\"version\":3,\"vendorName\":\"Bách Hóa Xanh Q1\",\"extractedTotal\":250000,"
            + "\"extractedDate\":\"2026-05-28\"}";
    private static final String AI_ANSWER = "{\"seller\": {\"seller_name\": \"Highlands\", \"total_cost_number\": 95000}}";
    private static final String VALIDATION_PREFIX = "Dữ liệu không hợp lệ";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static MockHttpServletRequestBuilder signedIn(HttpMethod method, String url) {
        return request(method, url).header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
    }

    private static MockHttpServletRequestBuilder signedInWithBody(HttpMethod method, String url, String body) {
        return signedIn(method, url).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String retryBody(String rawReceiptData, String clientId) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("rawReceiptData", rawReceiptData);
        body.put("clientId", clientId);
        return JSON.writeValueAsString(body);
    }

    private static ReceiptResponseDto receipt(String status, long version) {
        return ReceiptResponseDto.builder()
                .id(7L).transactionId(100L).status(status).version(version)
                .vendorName("Bách Hóa Xanh").extractedTotal(new BigDecimal("245000.00")).extractedDate(LocalDate.of(2026, 5, 27))
                .imageUrl("https://img.example.com/1.jpg")
                .build();
    }

    static Stream<Arguments> theNewEndpoints() {
        return Stream.of(
                Arguments.of(HttpMethod.GET, RECEIPT_URL, null),
                Arguments.of(HttpMethod.GET, BY_TRANSACTION_URL, null),
                Arguments.of(HttpMethod.PUT, RECEIPT_URL, VALID_EDIT),
                Arguments.of(HttpMethod.POST, RETRY_URL, "{\"rawReceiptData\":\"{}\"}"));
    }

    private static MockHttpServletRequestBuilder call(HttpMethod method, String url, String body) {
        MockHttpServletRequestBuilder call = request(method, url);
        return body == null ? call : call.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("theNewEndpoints")
    void withoutATokenOrWithAnInvalidOneNoReceiptIsReadOrChanged(HttpMethod method, String url, String body) throws Exception {
        mockMvc.perform(call(method, url, body)).andExpect(status().isUnauthorized());
        mockMvc.perform(call(method, url, body).header(HttpHeaders.AUTHORIZATION, "Bearer " + BAD_TOKEN))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(receiptProcessingService);
    }

    // ------------------------------------------------------------------------------------------------------- reading

    @Test
    void theOwnerReadsAReceiptWithItsVersionAndNothingOfWhatTheAiAnswered() throws Exception {
        when(receiptProcessingService.getMyReceipt(7L)).thenReturn(receipt("COMPLETED_RAW", 3));

        mockMvc.perform(signedIn(HttpMethod.GET, RECEIPT_URL + "?userId=999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Lấy thông tin hóa đơn thành công"))
                .andExpect(jsonPath("$.data.id").value(7))
                .andExpect(jsonPath("$.data.transactionId").value(100))
                .andExpect(jsonPath("$.data.status").value("COMPLETED_RAW"))
                .andExpect(jsonPath("$.data.version").value(3))
                .andExpect(jsonPath("$.data.vendorName").value("Bách Hóa Xanh"))
                .andExpect(jsonPath("$.data.extractedTotal").value(245000.00))
                .andExpect(jsonPath("$.data.extractedDate").value("2026-05-27"))
                .andExpect(jsonPath("$.data.aiRawData").doesNotExist())
                .andExpect(jsonPath("$.data.rawReceiptData").doesNotExist())
                .andExpect(jsonPath("$.data.retryClientId").doesNotExist());

        verify(receiptProcessingService).getMyReceipt(7L);
        verifyNoMoreInteractions(receiptProcessingService);
    }

    @Test
    void aFailedReceiptIsReadWithItsReason() throws Exception {
        when(receiptProcessingService.getMyReceipt(7L)).thenReturn(ReceiptResponseDto.builder()
                .id(7L).status("FAILED").version(0L)
                .errorCode("AI_NO_TOTAL").errorMessage("AI không đọc được tổng tiền trên hóa đơn")
                .build());

        mockMvc.perform(signedIn(HttpMethod.GET, RECEIPT_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.errorCode").value("AI_NO_TOTAL"))
                .andExpect(jsonPath("$.data.errorMessage").value("AI không đọc được tổng tiền trên hóa đơn"));
    }

    @Test
    void theReceiptOfATransactionIsReadThroughTheTransaction() throws Exception {
        when(receiptProcessingService.getMyReceiptOfTransaction(100L)).thenReturn(receipt("EDITED", 4));

        mockMvc.perform(signedIn(HttpMethod.GET, BY_TRANSACTION_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(7))
                .andExpect(jsonPath("$.data.status").value("EDITED"))
                .andExpect(jsonPath("$.data.version").value(4));

        verify(receiptProcessingService).getMyReceiptOfTransaction(100L);
        verifyNoMoreInteractions(receiptProcessingService);
    }

    // ------------------------------------------------------------------------------------------------------- editing

    @Test
    void anEditHandsTheVersionAndTheFieldsToTheServiceAndAnswersWithTheReceipt() throws Exception {
        when(receiptProcessingService.editMyReceipt(eq(7L), any())).thenReturn(receipt("EDITED", 4));

        mockMvc.perform(signedInWithBody(HttpMethod.PUT, RECEIPT_URL, VALID_EDIT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Cập nhật hóa đơn thành công"))
                .andExpect(jsonPath("$.data.status").value("EDITED"))
                .andExpect(jsonPath("$.data.version").value(4));

        ArgumentCaptor<ReceiptUpdateRequestDto> sent = ArgumentCaptor.forClass(ReceiptUpdateRequestDto.class);
        verify(receiptProcessingService).editMyReceipt(eq(7L), sent.capture());
        assertEquals(3L, sent.getValue().getVersion());
        assertEquals("Bách Hóa Xanh Q1", sent.getValue().getVendorName());
        assertEquals(0, new BigDecimal("250000").compareTo(sent.getValue().getExtractedTotal()));
        assertEquals(LocalDate.of(2026, 5, 28), sent.getValue().getExtractedDate());
    }

    @Test
    void anEditTakesNoUserNoStatusAndNoAiDataFromTheRequest() throws Exception {
        when(receiptProcessingService.editMyReceipt(eq(7L), any())).thenReturn(receipt("EDITED", 4));
        String body = "{\"version\":3,\"vendorName\":\"Highlands\",\"userId\":999,\"status\":\"APPROVED\","
                + "\"aiRawData\":\"{}\",\"rawReceiptData\":\"{}\",\"errorCode\":null,\"transactionId\":1}";

        mockMvc.perform(signedInWithBody(HttpMethod.PUT, RECEIPT_URL + "?userId=999", body)).andExpect(status().isOk());

        // The request type simply has no such fields, so whatever a client adds is dropped before the service is called.
        List<String> accepted = Arrays.stream(ReceiptUpdateRequestDto.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)
                .sorted()
                .toList();
        assertEquals(List.of("extractedDate", "extractedTotal", "vendorName", "version"), accepted);
    }

    static Stream<Arguments> invalidEdits() {
        return Stream.of(
                Arguments.of("no version", "{\"vendorName\":\"Highlands\"}", "Thiếu phiên bản (version) của hóa đơn"),
                Arguments.of("a null version", "{\"version\":null,\"vendorName\":\"Highlands\"}", "Thiếu phiên bản (version) của hóa đơn"),
                Arguments.of("nothing to change", "{\"version\":3}", "Cần ít nhất một trường để sửa"),
                Arguments.of("a total of zero", "{\"version\":3,\"extractedTotal\":0}", "Tổng tiền hóa đơn phải lớn hơn 0"),
                Arguments.of("a negative total", "{\"version\":3,\"extractedTotal\":-5}", "Tổng tiền hóa đơn phải lớn hơn 0"),
                Arguments.of("a vendor name wider than its column",
                        "{\"version\":3,\"vendorName\":\"" + "x".repeat(256) + "\"}", "Tên cửa hàng tối đa 255 ký tự"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidEdits")
    void anInvalidEditIsRejectedWith400AndNeverReachesTheService(String description, String body, String message) throws Exception {
        mockMvc.perform(signedInWithBody(HttpMethod.PUT, RECEIPT_URL, body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(startsWith(VALIDATION_PREFIX)))
                .andExpect(jsonPath("$.message").value(containsString(message)));

        verifyNoInteractions(receiptProcessingService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"version\":3,\"extractedDate\":\"28/05/2026\"}", "{\"version\":\"three\",\"vendorName\":\"A\"}", "not json"})
    void anEditThatCannotBeReadIsA400(String body) throws Exception {
        mockMvc.perform(signedInWithBody(HttpMethod.PUT, RECEIPT_URL, body)).andExpect(status().isBadRequest());

        verifyNoInteractions(receiptProcessingService);
    }

    // ------------------------------------------------------------------------------------------------------ retrying

    @Test
    void aRetryHandsTheAnswerOfTheAiAndTheKeyToTheServiceAndAnswersWithWhatBecameOfTheReceipt() throws Exception {
        when(receiptProcessingService.retryMyReceipt(eq(7L), any())).thenReturn(receipt("COMPLETED_RAW", 2));

        mockMvc.perform(signedInWithBody(HttpMethod.POST, RETRY_URL + "?userId=999", retryBody(AI_ANSWER, "attempt-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Đã ghi nhận kết quả đọc lại hóa đơn"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED_RAW"))
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.aiRawData").doesNotExist());

        ArgumentCaptor<ReceiptRetryRequestDto> sent = ArgumentCaptor.forClass(ReceiptRetryRequestDto.class);
        verify(receiptProcessingService).retryMyReceipt(eq(7L), sent.capture());
        assertEquals(AI_ANSWER, sent.getValue().getRawReceiptData());
        assertEquals("attempt-1", sent.getValue().getClientId());
    }

    @Test
    void aRetryWhoseNewReadingFailedTooIsStillA200ThatSaysSo() throws Exception {
        when(receiptProcessingService.retryMyReceipt(eq(7L), any())).thenReturn(ReceiptResponseDto.builder()
                .id(7L).status("FAILED").version(2L)
                .errorCode("AI_UNREADABLE").errorMessage("AI không đọc được nội dung hóa đơn")
                .build());

        mockMvc.perform(signedInWithBody(HttpMethod.POST, RETRY_URL, retryBody("{\"raw_text\": \"noise\"}", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.errorCode").value("AI_UNREADABLE"));
    }

    @Test
    void anAnswerOfTheAiOfExactlyTheLongestLengthAReceiptKeepsIsAccepted() throws Exception {
        when(receiptProcessingService.retryMyReceipt(eq(7L), any())).thenReturn(receipt("COMPLETED_RAW", 2));
        String longest = "x".repeat(Receipt.AI_RAW_DATA_MAX_LENGTH);

        mockMvc.perform(signedInWithBody(HttpMethod.POST, RETRY_URL, retryBody(longest, "x".repeat(Transaction.CLIENT_ID_MAX_LENGTH))))
                .andExpect(status().isOk());
    }

    static Stream<Arguments> invalidRetries() throws Exception {
        return Stream.of(
                Arguments.of("no answer of the AI", "{}", "Thiếu dữ liệu AI"),
                Arguments.of("a null answer", "{\"rawReceiptData\":null}", "Thiếu dữ liệu AI"),
                Arguments.of("a blank answer", retryBody("   ", null), "Thiếu dữ liệu AI"),
                Arguments.of("an answer longer than a receipt keeps",
                        retryBody("x".repeat(Receipt.AI_RAW_DATA_MAX_LENGTH + 1), null), "tối đa " + Receipt.AI_RAW_DATA_MAX_LENGTH + " ký tự"),
                Arguments.of("a key wider than its column",
                        retryBody(AI_ANSWER, "x".repeat(Transaction.CLIENT_ID_MAX_LENGTH + 1)), "clientId"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRetries")
    void anInvalidRetryIsRejectedWith400AndNeverReachesTheService(String description, String body, String message) throws Exception {
        mockMvc.perform(signedInWithBody(HttpMethod.POST, RETRY_URL, body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(startsWith(VALIDATION_PREFIX)))
                .andExpect(jsonPath("$.message").value(containsString(message)));

        verifyNoInteractions(receiptProcessingService);
    }

    // -------------------------------------------------------------------------- refusals of the reads and the writes

    static Stream<Arguments> refusalsOfTheLifecycle() {
        return Stream.of(
                Arguments.of(new TransactionAccessDeniedException("Bạn không có quyền thao tác trên hóa đơn này"), 403,
                        "Bạn không có quyền thao tác trên hóa đơn này"),
                Arguments.of(new ReceiptNotFoundException("Không tìm thấy hóa đơn"), 404, "Không tìm thấy hóa đơn"),
                Arguments.of(new ReceiptConflictException("Chỉ có thể thử lại hóa đơn đang ở trạng thái lỗi (FAILED)"), 409,
                        "Chỉ có thể thử lại hóa đơn đang ở trạng thái lỗi (FAILED)"),
                Arguments.of(new IllegalArgumentException("Số tiền điều chỉnh phải lớn hơn 0"), 400, "Số tiền điều chỉnh phải lớn hơn 0"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusalsOfTheLifecycle")
    void everyRefusalOfTheServiceKeepsItsStatusOnEachOfTheNewEndpoints(RuntimeException refusal, int status, String message)
            throws Exception {
        when(receiptProcessingService.getMyReceipt(any())).thenThrow(refusal);
        when(receiptProcessingService.getMyReceiptOfTransaction(any())).thenThrow(refusal);
        when(receiptProcessingService.editMyReceipt(any(), any())).thenThrow(refusal);
        when(receiptProcessingService.retryMyReceipt(any(), any())).thenThrow(refusal);

        for (MockHttpServletRequestBuilder call : List.of(
                signedIn(HttpMethod.GET, RECEIPT_URL),
                signedIn(HttpMethod.GET, BY_TRANSACTION_URL),
                signedInWithBody(HttpMethod.PUT, RECEIPT_URL, VALID_EDIT),
                signedInWithBody(HttpMethod.POST, RETRY_URL, retryBody(AI_ANSWER, null)))) {
            mockMvc.perform(call)
                    .andExpect(status().is(status))
                    .andExpect(jsonPath("$.code").value(status))
                    .andExpect(jsonPath("$.message").value(message));
        }
    }

    @Test
    void anEditThatLostToAnotherWriteOfTheSameVersionIsA409WithAFixedTextAndNothingOfTheEntity() throws Exception {
        when(receiptProcessingService.editMyReceipt(any(), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Receipt.class, 7L));

        mockMvc.perform(signedInWithBody(HttpMethod.PUT, RECEIPT_URL, VALID_EDIT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("Dữ liệu vừa được thay đổi bởi một yêu cầu khác, vui lòng tải lại rồi thử lại"))
                .andExpect(jsonPath("$.message").value(not(containsString("Receipt"))))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/receipts/abc", "/api/receipts/transaction/abc"})
    void anIdThatIsNotANumberIsA400(String url) throws Exception {
        mockMvc.perform(signedIn(HttpMethod.GET, url)).andExpect(status().isBadRequest());

        verifyNoInteractions(receiptProcessingService);
    }

    // ------------------------------------------------------------------------------------------------ structural guard

    @Test
    void noHandlerOfTheReceiptControllerReadsAUserIdFromTheRequest() {
        for (Method method : ReceiptController.class.getDeclaredMethods()) {
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
