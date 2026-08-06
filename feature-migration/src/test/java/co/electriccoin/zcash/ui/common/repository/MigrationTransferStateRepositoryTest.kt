package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.MigrationTransferStates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MigrationTransferStateRepositoryTest {
    private fun states(tipHeight: Long) = MigrationTransferStates(transfers = emptyList(), tipHeight = tipHeight)

    @Test
    fun observe_before_any_publish_is_null() {
        val repo = MigrationTransferStateRepositoryImpl()
        assertNull(repo.observe("account-1").value)
    }

    @Test
    fun publish_is_immediately_visible_to_observe() {
        val repo = MigrationTransferStateRepositoryImpl()
        repo.publish("account-1", states(42L))
        assertEquals(42L, repo.observe("account-1").value?.tipHeight)
    }

    @Test
    fun accounts_are_isolated_from_each_other() {
        val repo = MigrationTransferStateRepositoryImpl()
        repo.publish("account-1", states(1L))
        repo.publish("account-2", states(2L))
        assertEquals(1L, repo.observe("account-1").value?.tipHeight)
        assertEquals(2L, repo.observe("account-2").value?.tipHeight)
    }

    @Test
    fun republishing_null_clears_the_cached_value() {
        // A driver read that comes back null (e.g. no migration in progress anymore) must be able
        // to clear a stale cached value, not get silently ignored.
        val repo = MigrationTransferStateRepositoryImpl()
        repo.publish("account-1", states(1L))
        repo.publish("account-1", null)
        assertNull(repo.observe("account-1").value)
    }

    @Test
    fun observe_called_twice_for_the_same_account_returns_the_same_flow_instance() {
        // Repeated observe() calls (e.g. one per screen recomposition) must not fragment into
        // independent flows that could each cache a different value.
        val repo = MigrationTransferStateRepositoryImpl()
        val first = repo.observe("account-1")
        val second = repo.observe("account-1")
        repo.publish("account-1", states(7L))
        assertEquals(7L, first.value?.tipHeight)
        assertEquals(7L, second.value?.tipHeight)
    }
}
