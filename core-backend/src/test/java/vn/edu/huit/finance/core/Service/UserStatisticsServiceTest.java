package vn.edu.huit.finance.core.Service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import vn.edu.huit.finance.core.Service.Cache.CacheNames;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The rules that keep the per-user cache safe. What the statistics hold is tested through TransactionServiceTest (with
 * this class for real behind it), and the cache itself on a real Redis.
 */
class UserStatisticsServiceTest {

    private static final String CONTROLLER_PACKAGE = "vn.edu.huit.finance.core.Controller";

    @Test
    void eachStatisticIsCachedPerUserIdByAPublicMethod() throws NoSuchMethodException {
        Method vendors = UserStatisticsService.class.getMethod("popularVendorsOf", Long.class);
        Method dashboard = UserStatisticsService.class.getMethod("dashboardOf", Long.class);

        assertCachedPerUser(vendors, CacheNames.USER_POPULAR_VENDORS);
        assertCachedPerUser(dashboard, CacheNames.USER_DASHBOARD);
    }

    private static void assertCachedPerUser(Method method, String cacheName) {
        Cacheable cacheable = AnnotatedElementUtils.findMergedAnnotation(method, Cacheable.class);
        assertNotNull(cacheable, method.getName());
        assertTrue(Modifier.isPublic(method.getModifiers()), "@Cacheable only works on a public method called through the proxy");
        assertArrayEquals(new String[]{cacheName}, cacheable.cacheNames());
        assertEquals("#userId", cacheable.key(), "the key is the user id and nothing else");
        assertEquals("#result == null", cacheable.unless());
        assertFalse(cacheable.sync(), "sync would lock the whole cache inside one JVM, not across instances");
    }

    /**
     * Nothing in UserStatisticsService checks who asks: the user id must come from TransactionService, which takes it from
     * the SecurityContext (or checks an administrator's request). A controller that used this class directly could hand
     * it an id from the request and read somebody else's cached statistics.
     */
    @Test
    void noControllerUsesTheStatisticsThatTakeAUserIdDirectly() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        Set<BeanDefinition> controllers = scanner.findCandidateComponents(CONTROLLER_PACKAGE);
        assertFalse(controllers.isEmpty(), "the scan found no controller at all");

        for (BeanDefinition definition : controllers) {
            Class<?> controller = Class.forName(definition.getBeanClassName());
            List<Class<?>> dependencies = new java.util.ArrayList<>(
                    Arrays.stream(controller.getDeclaredFields()).map(Field::getType).toList());
            for (Constructor<?> constructor : controller.getDeclaredConstructors()) {
                dependencies.addAll(List.of(constructor.getParameterTypes()));
            }
            assertFalse(dependencies.contains(UserStatisticsService.class),
                    controller.getSimpleName() + " must go through TransactionService");
        }
    }
}
