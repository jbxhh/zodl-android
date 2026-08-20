package co.electriccoin.zcash.ui.common.model

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CrossPayRequestTest {
    private val recipient = "0x92bF6Fbd794bA41093013Db027400B174aE4b5Cd"
    private val usdcContract = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913"

    @Test
    fun bitcoinLikeRequests() {
        val bitcoin = requireNotNull(CrossPayRequestParser.parse("bitcoin:bc1qexample?amount=0.015&label=Shop"))
        assertEquals("bc1qexample", bitcoin.address)
        assertEquals(BigDecimal("0.015"), bitcoin.resolvedAmount(SwapAssetTestFixture.asset()))

        val litecoin = requireNotNull(CrossPayRequestParser.parse("LITECOIN:ltc1example"))
        assertEquals("ltc1example", litecoin.address)
        assertNull(litecoin.amount)

        val cash = requireNotNull(CrossPayRequestParser.parse("bitcoincash:qexample?amount=1"))
        assertEquals("bitcoincash:qexample", cash.address)
    }

    @Test
    fun erc20RequestUsesRecipientContractChainAndAtomicAmount() {
        val baseUsdc =
            SwapAssetTestFixture.asset(
                tokenTicker = "USDC",
                chainTicker = "base",
                decimals = 6,
                contractAddress = usdcContract
            )
        val request =
            requireNotNull(
                CrossPayRequestParser.parse(
                    "ethereum:$usdcContract@8453/transfer?address=$recipient&uint256=2500000"
                )
            )

        assertEquals(recipient, request.address)
        assertEquals(baseUsdc, request.resolveAsset(listOf(baseUsdc), null))
        assertEquals(0, BigDecimal("2.5").compareTo(request.resolvedAmount(baseUsdc)))
    }

    @Test
    fun nativeEvmRequestUsesChainAndWeiAmount() {
        val ethereum = SwapAssetTestFixture.asset(tokenTicker = "ETH", chainTicker = "eth", decimals = 18)
        val request = requireNotNull(CrossPayRequestParser.parse("ethereum:$recipient@1?value=2.014e18"))

        assertEquals(ethereum, request.resolveAsset(listOf(ethereum), null))
        assertEquals(0, BigDecimal("2.014").compareTo(request.resolvedAmount(ethereum)))
    }

    @Test
    fun solanaAndNearRequests() {
        val mint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        val solanaUsdc =
            SwapAssetTestFixture.asset(
                tokenTicker = "USDC",
                chainTicker = "sol",
                decimals = 6,
                contractAddress = mint
            )
        val solana = requireNotNull(CrossPayRequestParser.parse("solana:recipient?amount=0.01&spl-token=$mint"))
        assertEquals("recipient", solana.address)
        assertEquals(solanaUsdc, solana.resolveAsset(listOf(solanaUsdc), null))
        assertEquals(BigDecimal("0.01"), solana.resolvedAmount(solanaUsdc))

        assertEquals("alice.near", CrossPayRequestParser.parse("near:alice.near")?.address)
    }

    @Test
    fun unsupportedAndPlainValuesAreNotReinterpreted() {
        assertNull(CrossPayRequestParser.parse("bc1qplain"))
        assertNull(CrossPayRequestParser.parse("solana:https://example.com/pay"))
        assertNull(CrossPayRequestParser.parse("ethereum:$usdcContract/approve?address=$recipient"))
    }
}
