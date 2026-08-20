package co.electriccoin.zcash.ui.common.model

import java.math.BigDecimal
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class CrossPayRequest(
    val address: String,
    val amount: Amount?,
    val assetReference: AssetReference
) {
    data class Amount(
        val value: BigDecimal,
        val isAtomic: Boolean
    )

    sealed interface AssetReference {
        data class Native(
            val chain: String
        ) : AssetReference

        data class EvmNative(
            val chainId: String?
        ) : AssetReference

        data class Contract(
            val chain: String?,
            val chainId: String?,
            val address: String
        ) : AssetReference
    }

    fun resolveAsset(assets: Collection<SwapAsset>, current: SwapAsset?): SwapAsset? {
        val candidates =
            when (val reference = assetReference) {
                is AssetReference.Native -> nativeAssets(assets, reference.chain)
                is AssetReference.EvmNative -> evmNativeAssets(assets, reference, current)
                is AssetReference.Contract -> contractAssets(assets, reference)
            }

        return current?.takeIf(candidates::contains) ?: candidates.singleOrNull()
    }

    private fun nativeAssets(assets: Collection<SwapAsset>, chain: String) =
        assets.filter {
            it.chainTicker.equals(chain, true) && it.tokenTicker.lowercase() in nativeTokens(chain)
        }

    private fun evmNativeAssets(
        assets: Collection<SwapAsset>,
        reference: AssetReference.EvmNative,
        current: SwapAsset?
    ): List<SwapAsset> {
        val chain = reference.chainId?.let(::evmChain) ?: current?.chainTicker?.lowercase()
        return chain?.let { nativeAssets(assets, it) }.orEmpty()
    }

    private fun contractAssets(
        assets: Collection<SwapAsset>,
        reference: AssetReference.Contract
    ): List<SwapAsset> {
        val chain = reference.chainId?.let(::evmChain) ?: reference.chain
        if (reference.chainId != null && chain == null) return emptyList()
        return assets.filter {
            (chain == null || it.chainTicker.equals(chain, true)) &&
                it.contractAddress?.equals(reference.address, true) == true
        }
    }

    fun resolvedAmount(asset: SwapAsset?): BigDecimal? =
        amount?.let {
            if (it.isAtomic) {
                asset?.let { resolvedAsset -> it.value.movePointLeft(resolvedAsset.decimals) }
            } else {
                it.value
            }
        }

    private fun evmChain(chainId: String): String? = EVM_CHAINS[chainId]

    private fun nativeTokens(chain: String): Set<String> =
        NATIVE_TOKENS[chain.lowercase()] ?: setOf(chain.lowercase())

    private companion object {
        val EVM_CHAINS =
            mapOf(
                "1" to "eth",
                "10" to "op",
                "56" to "bsc",
                "137" to "pol",
                "196" to "xlayer",
                "8453" to "base",
                "42161" to "arb",
                "43114" to "avax"
            )
        val NATIVE_TOKENS =
            mapOf(
                "arb" to setOf("eth"),
                "base" to setOf("eth"),
                "eth" to setOf("eth"),
                "avax" to setOf("avax"),
                "bch" to setOf("bch"),
                "bsc" to setOf("bnb"),
                "btc" to setOf("btc"),
                "dash" to setOf("dash"),
                "doge" to setOf("doge"),
                "ltc" to setOf("ltc"),
                "near" to setOf("near", "wnear"),
                "op" to setOf("eth", "op"),
                "pol" to setOf("matic", "pol"),
                "sol" to setOf("sol"),
                "xlayer" to setOf("okb")
            )
    }
}

object CrossPayRequestParser {
    fun parse(value: String): CrossPayRequest? {
        val trimmed = value.trim()
        val separator = trimmed.indexOf(':')
        if (separator <= 0) return null

        val scheme = trimmed.substring(0, separator).lowercase()
        val payload = trimmed.substring(separator + 1)
        return when (scheme) {
            "bitcoin" -> parseBitcoinLike(payload, "btc")
            "bitcoincash" -> parseBitcoinLike(payload, "bch", preserveScheme = true)
            "dash" -> parseBitcoinLike(payload, "dash")
            "dogecoin" -> parseBitcoinLike(payload, "doge")
            "ethereum" -> parseEthereum(payload)
            "litecoin" -> parseBitcoinLike(payload, "ltc")
            "near" -> parseNear(payload)
            "solana" -> parseSolana(payload)
            else -> null
        }
    }

    private fun parseBitcoinLike(payload: String, chain: String, preserveScheme: Boolean = false): CrossPayRequest? {
        val (target, query) = splitQuery(payload)
        val decoded = decode(target.removePrefix("//"))
        if (decoded.isEmpty()) return null
        val params = queryParameters(query)
        return CrossPayRequest(
            address = if (preserveScheme) "bitcoincash:$decoded" else decoded,
            amount = decimal(params["amount"])?.let { CrossPayRequest.Amount(it, isAtomic = false) },
            assetReference = CrossPayRequest.AssetReference.Native(chain)
        )
    }

    private fun parseEthereum(payload: String): CrossPayRequest? {
        val normalized = if (payload.startsWith(PAY_PREFIX, true)) payload.drop(PAY_PREFIX.length) else payload
        val (targetAndFunction, query) = splitQuery(normalized)
        val segments = targetAndFunction.split('/', limit = 2)
        var target = segments[0]
        val function = segments.getOrNull(1)?.lowercase()
        val chainSeparator = target.lastIndexOf('@')
        val chainId =
            if (chainSeparator >= 0) {
                target.substring(chainSeparator + 1).also { target = target.substring(0, chainSeparator) }
            } else {
                null
            }
        if (target.isEmpty() || chainId?.all(Char::isDigit) == false) return null
        val params = queryParameters(query)

        return if (function.isNullOrEmpty()) {
            CrossPayRequest(
                address = target,
                amount = decimal(params["value"])?.let { CrossPayRequest.Amount(it, isAtomic = true) },
                assetReference = CrossPayRequest.AssetReference.EvmNative(chainId)
            )
        } else {
            parseErc20Transfer(function, target, chainId, params)
        }
    }

    private fun parseErc20Transfer(
        function: String,
        contract: String,
        chainId: String?,
        params: Map<String, String>
    ): CrossPayRequest? {
        val recipient = params["address"] ?: return null
        val isSupported = function == "transfer" && isHexAddress(contract) && isHexAddress(recipient)
        return if (isSupported) {
            CrossPayRequest(
                address = recipient,
                amount = decimal(params["uint256"])?.let { CrossPayRequest.Amount(it, isAtomic = true) },
                assetReference = CrossPayRequest.AssetReference.Contract(null, chainId, contract)
            )
        } else {
            null
        }
    }

    private fun parseSolana(payload: String): CrossPayRequest? {
        val (target, query) = splitQuery(payload)
        val address = decode(target.removePrefix("//"))
        if (address.isEmpty() || runCatching { URI(address).scheme }.getOrNull() != null) return null
        val params = queryParameters(query)
        val assetReference =
            params["spl-token"]?.let {
                CrossPayRequest.AssetReference.Contract(chain = "sol", chainId = null, address = it)
            } ?: CrossPayRequest.AssetReference.Native("sol")
        return CrossPayRequest(
            address = address,
            amount = decimal(params["amount"])?.let { CrossPayRequest.Amount(it, isAtomic = false) },
            assetReference = assetReference
        )
    }

    private fun parseNear(payload: String): CrossPayRequest? {
        val (target, query) = splitQuery(payload)
        val address = decode(target.removePrefix("//"))
        if (address.isEmpty()) return null
        val amount = decimal(queryParameters(query)["amount"])
        return CrossPayRequest(
            address = address,
            amount = amount?.let { CrossPayRequest.Amount(it, isAtomic = false) },
            assetReference = CrossPayRequest.AssetReference.Native("near")
        )
    }
}

private fun splitQuery(value: String): Pair<String, String?> {
    val separator = value.indexOf('?')
    return if (separator < 0) value to null else value.substring(0, separator) to value.substring(separator + 1)
}

private fun queryParameters(query: String?): Map<String, String> =
    query
        ?.split('&')
        ?.mapNotNull {
            val pair = it.split('=', limit = 2)
            pair.takeIf { parts -> parts.size == 2 }?.let { parts ->
                decode(parts[0]).lowercase() to decode(parts[1])
            }
        }?.toMap()
        .orEmpty()

private fun decimal(value: String?): BigDecimal? =
    value
        ?.let { runCatching { BigDecimal(it) }.getOrNull() }
        ?.takeIf { it.signum() >= 0 }

private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

private fun isHexAddress(value: String): Boolean =
    value.length == EVM_ADDRESS_LENGTH &&
        value.startsWith("0x", true) &&
        value.drop(2).all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }

private const val PAY_PREFIX = "pay-"
private const val EVM_ADDRESS_LENGTH = 42
