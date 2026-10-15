package vn.edu.huit.finance.core.Security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.*;

/** The one reading of the client's address: first entry of X-Forwarded-For (the proxy's convention), else the connection. */
class ClientIpTest {

    private static final String PROXY = "10.0.0.2";

    @AfterEach
    void forgetTheRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static MockHttpServletRequest requestFrom(String remoteAddress, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        if (forwardedFor != null) {
            request.addHeader(ClientIp.FORWARDED_FOR_HEADER, forwardedFor);
        }
        return request;
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "203.0.113.7                       | 203.0.113.7",
            "203.0.113.7, 10.0.0.1             | 203.0.113.7",
            "  198.51.100.4  ,10.0.0.1, 10.0.0.2 | 198.51.100.4",
            "2001:db8::1                       | 2001:db8::1",
            "::ffff:192.0.2.1                  | ::ffff:192.0.2.1"})
    void behindTheProxyTheFirstForwardedAddressIsTheClient(String forwardedFor, String client) {
        assertEquals(client, ClientIp.of(requestFrom(PROXY, forwardedFor)));
    }

    @Test
    void withoutTheHeaderTheConnectionsAddressIsTheClient() {
        assertEquals("192.0.2.10", ClientIp.of(requestFrom("192.0.2.10", null)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "evil.example.com", "<script>alert(1)</script>", "1.2.3.4'; DROP TABLE action_logs;--",
            "", "   ", "cafe", "1:2:3:4:5:6:7:8:9"})
    void anythingButAnIpLiteralInTheHeaderFallsBackToTheConnection(String forwardedFor) {
        assertEquals(PROXY, ClientIp.of(requestFrom(PROXY, forwardedFor)));
    }

    @Test
    void aHeaderLongerThanAnyAddressFallsBackToTheConnection_soItCanNeverOverflowTheAuditColumn() {
        String long45Plus = "1".repeat(20) + "." + "2".repeat(30);

        assertEquals(PROXY, ClientIp.of(requestFrom(PROXY, long45Plus)));
    }

    @Test
    void aConnectionWithoutAUsableAddressHasNone() {
        assertNull(ClientIp.of(requestFrom("not-an-address", null)));
    }

    @Test
    void theCurrentRequestIsReadThroughTheRequestContext_andOutsideARequestThereIsNoAddress() {
        assertNull(ClientIp.ofCurrentRequest());

        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(requestFrom(PROXY, "203.0.113.9")));

        assertEquals("203.0.113.9", ClientIp.ofCurrentRequest());
    }
}
