package vn.edu.huit.finance.core.Service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.huit.finance.core.Entity.AuditAction;
import vn.edu.huit.finance.core.Entity.Category;
import vn.edu.huit.finance.core.Exception.InvalidCategoryException;
import vn.edu.huit.finance.core.Repository.CategoryRepository;
import vn.edu.huit.finance.core.Service.Audit.AuditActor;
import vn.edu.huit.finance.core.Service.Audit.AuditEntry;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** The shared categories as administrators change them: each change with its row, by the administrator who called. */
@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock private CategoryRepository categoryRepository;
    @Mock private AuditLogService auditLogService;
    @InjectMocks private CategoryService service;

    private static Category category(Long id, String name) {
        Category category = new Category();
        category.setId(id);
        category.setName(name);
        category.setType("EXPENSE");
        return category;
    }

    private AuditEntry recorded() {
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(entry.capture());
        return entry.getValue();
    }

    @Test
    void aNewCategoryIsRecordedWithItsNameAndType() {
        when(categoryRepository.save(any(Category.class))).thenAnswer(invocation -> {
            Category saved = invocation.getArgument(0);
            saved.setId(12L);
            return saved;
        });

        service.createCategory("Ăn uống", "EXPENSE");

        AuditEntry entry = recorded();
        assertEquals(AuditAction.CATEGORY_CREATED, entry.action());
        assertEquals(AuditActor.currentCaller(), entry.actor());
        assertEquals(12L, entry.targetId());
        assertEquals("Thêm danh mục \"Ăn uống\" (EXPENSE)", entry.description());
    }

    @Test
    void aRenameIsRecordedWithTheOldAndTheNewName() {
        when(categoryRepository.findById(12L)).thenReturn(Optional.of(category(12L, "Ăn uống")));
        when(categoryRepository.save(any(Category.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.renameCategory(12L, "Ăn ngoài");

        AuditEntry entry = recorded();
        assertEquals(AuditAction.CATEGORY_UPDATED, entry.action());
        assertEquals(12L, entry.targetId());
        assertEquals("Đổi tên danh mục \"Ăn uống\" → \"Ăn ngoài\"", entry.description());
    }

    @Test
    void aDeletionIsRecorded_onceTheDatabaseHasAcceptedIt() {
        Category food = category(12L, "Ăn uống");
        when(categoryRepository.findById(12L)).thenReturn(Optional.of(food));

        service.deleteCategory(12L);

        verify(categoryRepository).delete(food);
        verify(categoryRepository).flush();
        AuditEntry entry = recorded();
        assertEquals(AuditAction.CATEGORY_DELETED, entry.action());
        assertEquals("Xóa danh mục \"Ăn uống\" (EXPENSE)", entry.description());
    }

    @Test
    void aCategoryStillInUseIsNotDeleted_withTheUsualMessage_andNothingIsRecorded() {
        when(categoryRepository.findById(12L)).thenReturn(Optional.of(category(12L, "Ăn uống")));
        doThrow(new DataIntegrityViolationException("fk_transactions_category")).when(categoryRepository).flush();

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> service.deleteCategory(12L));

        assertEquals("Không thể xóa danh mục [Ăn uống] vì đang có giao dịch sử dụng nó!", refused.getMessage());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void aCategoryThatDoesNotExistIsRefused_andNothingIsRecorded() {
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(InvalidCategoryException.class, () -> service.renameCategory(99L, "x"));
        assertThrows(InvalidCategoryException.class, () -> service.deleteCategory(99L));

        verifyNoInteractions(auditLogService);
    }

    @Test
    void everyChangeRunsInATransaction_soTheChangeAndItsRowCommitTogether() throws NoSuchMethodException {
        for (Method method : new Method[] {
                CategoryService.class.getMethod("createCategory", String.class, String.class),
                CategoryService.class.getMethod("renameCategory", Long.class, String.class),
                CategoryService.class.getMethod("deleteCategory", Long.class)}) {
            assertTrue(AnnotatedElementUtils.hasAnnotation(method, Transactional.class), method.getName());
        }
    }
}
