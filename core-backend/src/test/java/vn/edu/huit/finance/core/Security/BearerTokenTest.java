package vn.edu.huit.finance.core.Security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BearerTokenTest {

    @Test
    void extract_returnsTheTokenAfterThePrefix() {
        assertEquals(Optional.of("abc.def.ghi"), BearerToken.extract("Bearer abc.def.ghi"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Bearer ", "Bearer", "bearer abc.def.ghi", "Basic abc.def.ghi", "abc.def.ghi", " Bearer abc.def.ghi"})
    void extract_isEmptyForAnythingThatIsNotABearerHeaderWithAToken(String header) {
        assertTrue(BearerToken.extract(header).isEmpty());
    }
}
