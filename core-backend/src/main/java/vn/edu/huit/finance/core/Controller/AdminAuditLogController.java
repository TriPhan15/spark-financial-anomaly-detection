package vn.edu.huit.finance.core.Controller;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.huit.finance.core.Dto.ApiResponse;
import vn.edu.huit.finance.core.Dto.AuditLogFilterRequestDto;
import vn.edu.huit.finance.core.Dto.AuditLogPageResponseDto;
import vn.edu.huit.finance.core.Security.Roles;
import vn.edu.huit.finance.core.Service.AuditLogQueryService;

/**
 * The application activity log, for administrators: filtered and paged, newest first. The class is closed to everybody
 * without ROLE_ADMIN twice, by the /api/admin/** rule of SecurityConfig and by @PreAuthorize, so neither of them can be
 * dropped by mistake without the other still holding. Reading the log is not itself logged.
 */
@RestController
@RequestMapping("/api/admin/audit-logs")
@PreAuthorize(Roles.HAS_ADMIN)
public class AdminAuditLogController {

    private static final String MSG_SEARCH_OK = "Lấy nhật ký hệ thống thành công";

    @Autowired
    private AuditLogQueryService auditLogQueryService;

    @GetMapping
    public ResponseEntity<ApiResponse<AuditLogPageResponseDto>> search(@Valid @ModelAttribute AuditLogFilterRequestDto filter) {
        return ApiResponses.respond(HttpStatus.OK, MSG_SEARCH_OK, auditLogQueryService.search(filter));
    }
}
