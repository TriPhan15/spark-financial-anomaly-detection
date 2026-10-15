package vn.edu.huit.finance.core.Controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import vn.edu.huit.finance.core.Dto.ApiResponse;

/** Builds the ApiResponse envelope, with the code mirroring the HTTP status. */
final class ApiResponses {

    private ApiResponses() {
    }

    static <T> ResponseEntity<ApiResponse<T>> respond(HttpStatus status, String message, T data) {
        return ResponseEntity.status(status).body(ApiResponse.<T>builder()
                .code(status.value())
                .message(message)
                .data(data)
                .build());
    }
}
