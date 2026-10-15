package vn.edu.huit.finance.core.Service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.Category;
import vn.edu.huit.finance.core.Exception.InvalidCategoryException;
import vn.edu.huit.finance.core.Repository.CategoryRepository;
import vn.edu.huit.finance.core.Service.Audit.AuditActor;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;

/**
 * The shared categories as administrators manage them, for administrators only (the caller checks that). Each change
 * and its audit row, naming the administrator, commit together. (A category a user names in a transaction is created by
 * TransactionService, which records it too.)
 */
@Slf4j
@Service
public class CategoryService {

    private static final String MSG_CATEGORY_NOT_FOUND = "Không tìm thấy danh mục";
    private static final String MSG_CATEGORY_IN_USE = "Không thể xóa danh mục [%s] vì đang có giao dịch sử dụng nó!";
    private static final String AUDIT_CREATED = "Thêm danh mục \"%s\" (%s)";
    private static final String AUDIT_RENAMED = "Đổi tên danh mục \"%s\" → \"%s\"";
    private static final String AUDIT_DELETED = "Xóa danh mục \"%s\" (%s)";

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private AuditLogService auditLogService;

    @Transactional
    public Category createCategory(String name, String type) {
        Category category = new Category();
        category.setName(name);
        category.setType(type);
        Category created = categoryRepository.save(category);

        record(AuditAction.CATEGORY_CREATED, created.getId(), String.format(AUDIT_CREATED, created.getName(), created.getType()));
        log.info("Category created, categoryId={}", created.getId());
        return created;
    }

    @Transactional
    public Category renameCategory(Long categoryId, String name) {
        Category category = findCategory(categoryId);
        String previousName = category.getName();
        category.setName(name);
        Category renamed = categoryRepository.save(category);

        record(AuditAction.CATEGORY_UPDATED, categoryId, String.format(AUDIT_RENAMED, previousName, renamed.getName()));
        log.info("Category renamed, categoryId={}", categoryId);
        return renamed;
    }

    /** Refused while a transaction or a budget still uses the category: nothing is deleted then, and nothing recorded. */
    @Transactional
    public void deleteCategory(Long categoryId) {
        Category category = findCategory(categoryId);
        try {
            categoryRepository.delete(category);
            categoryRepository.flush();
        } catch (DataIntegrityViolationException e) {
            log.warn("Category deletion refused, it is still in use, categoryId={}", categoryId);
            throw new IllegalStateException(String.format(MSG_CATEGORY_IN_USE, category.getName()));
        }

        record(AuditAction.CATEGORY_DELETED, categoryId, String.format(AUDIT_DELETED, category.getName(), category.getType()));
        log.info("Category deleted, categoryId={}", categoryId);
    }

    private Category findCategory(Long categoryId) {
        return categoryRepository.findById(categoryId).orElseThrow(() -> {
            log.warn("Category not found, categoryId={}", categoryId);
            return new InvalidCategoryException(MSG_CATEGORY_NOT_FOUND);
        });
    }

    /** By the administrator who called, looked up when the row is written. */
    private void record(AuditAction action, Long categoryId, String description) {
        auditLogService.record(AuditEntry.of(action, AuditActor.currentCaller(), categoryId, description));
    }
}
