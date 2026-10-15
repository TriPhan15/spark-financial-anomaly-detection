package vn.edu.huit.finance.core.Service.Cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static vn.edu.huit.finance.core.Service.Cache.CacheNames.*;

/** The one table that decides what each kind of write makes stale. */
class StatisticsChangeTest {

    static Stream<Arguments> whatEachChangeEvicts() {
        // change, caches in which only the entry of the user concerned goes, caches that go as a whole
        return Stream.of(
                Arguments.of(StatisticsChange.TRANSACTION_TOTALS, USER_CACHES, ADMIN_CACHES),
                Arguments.of(StatisticsChange.TRANSACTION_DETAILS, Set.of(USER_DASHBOARD), Set.of()),
                Arguments.of(StatisticsChange.RECEIPT_STATUS, Set.of(USER_DASHBOARD), Set.of(ADMIN_DASHBOARD_SUMMARY)),
                Arguments.of(StatisticsChange.RECEIPT_CONTENT, USER_CACHES, Set.of(ADMIN_DASHBOARD_SUMMARY)),
                Arguments.of(StatisticsChange.ACCOUNT_CREATED, Set.of(), Set.of(ADMIN_DASHBOARD_SUMMARY)),
                Arguments.of(StatisticsChange.ACCOUNT_PROFILE, Set.of(), Set.of(ADMIN_DASHBOARD_SUMMARY, ADMIN_TOP_SPENDERS)),
                Arguments.of(StatisticsChange.ACCOUNT_DELETED, USER_CACHES, ADMIN_CACHES),
                Arguments.of(StatisticsChange.DATABASE_RESTORED, Set.of(), ALL_CACHES));
    }

    @ParameterizedTest
    @MethodSource("whatEachChangeEvicts")
    void eachChangeEvictsExactlyWhatItMakesStale(StatisticsChange change, Set<String> ownerCaches, Set<String> clearedCaches) {
        assertEquals(ownerCaches, change.ownerCaches());
        assertEquals(clearedCaches, change.clearedCaches());
    }

    @Test
    void everyChangeIsInTheTable() {
        assertEquals(StatisticsChange.values().length, whatEachChangeEvicts().count());
    }

    @ParameterizedTest
    @EnumSource(StatisticsChange.class)
    void onlyThePerUserCachesAreEvictedForOneUser(StatisticsChange change) {
        assertTrue(USER_CACHES.containsAll(change.ownerCaches()), change + " evicts a per-user entry of a shared cache");
        assertTrue(ALL_CACHES.containsAll(change.clearedCaches()), change + " names a cache that does not exist");
    }

    @ParameterizedTest
    @EnumSource(value = StatisticsChange.class, names = "DATABASE_RESTORED", mode = EnumSource.Mode.EXCLUDE)
    void noChangeOfOneUsersDataClearsTheEntriesOfEveryOtherUser(StatisticsChange change) {
        assertTrue(change.clearedCaches().stream().noneMatch(USER_CACHES::contains), change + " clears a per-user cache");
    }

    @Test
    void whatAReceiptSaysEvictsEverythingItsStatusDoesAndTheOwnersPopularVendorsOnTop() {
        // The popular vendors of a user are counted by the vendor name of their receipts, which an edit or a retry changes.
        assertTrue(StatisticsChange.RECEIPT_CONTENT.ownerCaches().containsAll(StatisticsChange.RECEIPT_STATUS.ownerCaches()));
        assertTrue(StatisticsChange.RECEIPT_CONTENT.ownerCaches().contains(USER_POPULAR_VENDORS));
        assertEquals(StatisticsChange.RECEIPT_STATUS.clearedCaches(), StatisticsChange.RECEIPT_CONTENT.clearedCaches());
    }

    @Test
    void anEditOfWhatOnlyTheOwnerSeesLeavesEveryAdminFigureAlone() {
        assertTrue(StatisticsChange.TRANSACTION_DETAILS.clearedCaches().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = StatisticsChange.class, names = {"ACCOUNT_CREATED", "ACCOUNT_PROFILE"})
    void aChangeOfAnAccountLeavesEveryUsersOwnStatisticsAlone(StatisticsChange change) {
        assertTrue(change.ownerCaches().isEmpty());
        assertTrue(ADMIN_CACHES.containsAll(change.clearedCaches()));
    }

    @Test
    void theCacheGroupsSplitTheCachesWithoutOverlap() {
        assertTrue(USER_CACHES.stream().noneMatch(ADMIN_CACHES::contains));
        assertEquals(ALL_CACHES.size(), USER_CACHES.size() + ADMIN_CACHES.size());
        assertTrue(ALL_CACHES.containsAll(USER_CACHES) && ALL_CACHES.containsAll(ADMIN_CACHES));
    }

    @Test
    void anEventAlwaysSaysWhatChanged() {
        assertThrows(NullPointerException.class, () -> StatisticsChangedEvent.of(null, 1L));
        assertNull(StatisticsChangedEvent.of(StatisticsChange.ACCOUNT_CREATED).userId());
    }
}
