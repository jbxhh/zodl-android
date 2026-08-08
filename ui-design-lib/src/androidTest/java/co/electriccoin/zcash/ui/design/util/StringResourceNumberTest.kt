package co.electriccoin.zcash.ui.design.util

import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import cash.z.ecc.android.sdk.model.Zatoshi
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale
import kotlin.test.assertEquals

/**
 * Number/amount [StringResource]s must always render with the forced number locale (period decimal,
 * comma grouping) even when the UI/device locale is non-US. We deliberately pass [Locale.GERMANY] as the
 * [StringContext] locale to prove the number output ignores it. See MOB-1356 / MOB-1394.
 */
class StringResourceNumberTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    @SmallTest
    fun byNumber_forcesUsGroupingAndDecimal_underGermanLocale() {
        val result = stringResByNumber(BigDecimal("1234567.89")).getString(context, Locale.GERMANY)

        assertEquals("1,234,567.89", result)
    }

    @Test
    @SmallTest
    fun byNumber_withoutGrouping_underGermanLocale() {
        val result =
            stringResByNumber(BigDecimal("1234567.89"), includeGroupingSeparator = false)
                .getString(context, Locale.GERMANY)

        assertEquals("1234567.89", result)
    }

    @Test
    @SmallTest
    fun byNumber_padsToMinDecimals() {
        val result = stringResByNumber(BigDecimal("1"), minDecimals = 2).getString(context, Locale.GERMANY)

        assertEquals("1.00", result)
    }

    @Test
    @SmallTest
    fun byDynamicNumber_usesPeriodDecimal_underGermanLocale() {
        val result = stringResByDynamicNumber(BigDecimal("1234.5")).getString(context, Locale.GERMANY)

        assertEquals("1,234.50", result)
    }

    @Test
    @SmallTest
    fun byCurrencyNumber_forcesUsFormat_underGermanLocale() {
        val result =
            stringResByCurrencyNumber(
                amount = BigDecimal("1234.5"),
                ticker = "$",
                tickerLocation = TickerLocation.BEFORE
            ).getString(context, Locale.GERMANY)

        assertEquals("$1,234.50", result)
    }

    @Test
    @SmallTest
    fun byZatoshi_forcesUsDecimal_underGermanLocale() {
        // 123_456_789 zatoshi == 1.23456789 ZEC
        val result = stringRes(Zatoshi(123_456_789L), TickerLocation.HIDDEN).getString(context, Locale.GERMANY)

        assertEquals("1.23456789", result)
    }
}
