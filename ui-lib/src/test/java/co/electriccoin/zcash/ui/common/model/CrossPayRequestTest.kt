package co.electriccoin.zcash.ui.common.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Locks CrossPayRequest.resolveAsset's handling of an EVM chain id that is present but not in
 * EVM_CHAINS (e.g. a chain the app doesn't support yet), as distinct from a chain id that is
 * absent altogether. Before this test's regression fix, both cases fell back to whatever asset
 * the user already had selected, so a request for an unsupported chain would silently resolve to
 * the wrong chain's asset instead of being rejected (MOB-1751 review finding).
 */
class CrossPayRequestTest {
    private val ethOnArbitrum = SwapAssetTestFixture.asset(tokenTicker = "eth", chainTicker = "arb")
    private val ethOnMainnet = SwapAssetTestFixture.asset(tokenTicker = "eth", chainTicker = "eth")
    private val assets = listOf(ethOnArbitrum, ethOnMainnet)

    private fun evmNativeRequest(chainId: String?) =
        CrossPayRequest(
            address = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
            amount = null,
            assetReference = CrossPayRequest.AssetReference.EvmNative(chainId)
        )

    @Test
    fun unsupportedChainIdIsRejectedRatherThanFallingBackToCurrentSelection() {
        val resolved = evmNativeRequest(chainId = "250").resolveAsset(assets, current = ethOnArbitrum)

        assertNull(resolved)
    }

    @Test
    fun missingChainIdFallsBackToCurrentSelection() {
        val resolved = evmNativeRequest(chainId = null).resolveAsset(assets, current = ethOnArbitrum)

        assertEquals(ethOnArbitrum, resolved)
    }

    @Test
    fun supportedChainIdResolvesToThatChainRegardlessOfCurrentSelection() {
        val resolved = evmNativeRequest(chainId = "1").resolveAsset(assets, current = ethOnArbitrum)

        assertEquals(ethOnMainnet, resolved)
    }
}
