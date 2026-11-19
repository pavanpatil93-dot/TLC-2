package com.example.loi

import org.junit.Assert.assertEquals
import org.junit.Test

class LoiPdfGeneratorTest {

    @Test
    fun indianCurrencyWords_formatsLakhsAndCrores() {
        assertEquals(
            "Rs. 1,70,000 (Rupees One Lakh Seventy Thousand Only)",
            IndianCurrencyWords.formatRsWithWords(170_000.0)
        )
        assertEquals(
            "Rs. 5,10,000 (Rupees Five Lakh Ten Thousand Only)",
            IndianCurrencyWords.formatRsWithWords(510_000.0)
        )
        assertEquals(
            "Rs. 1,25,40,050 (Rupees One Crore Twenty Five Lakh Forty Thousand Fifty Only)",
            IndianCurrencyWords.formatRsWithWords(12_540_050.0)
        )
        assertEquals("__________________", IndianCurrencyWords.formatRsWithWords(0.0))
    }

    @Test
    fun brokerageSlab_handlesThreeYearsThreshold() {
        val upTo3Years = LoiDraftData(agreementDurationMonths = "36", monthlyFeeInr = "170000")
        assertEquals(1, upTo3Years.brokerageMonthsSlab())
        assertEquals(170_000.0, upTo3Years.computedBrokerageInr(), 0.01)

        val above3Years = LoiDraftData(agreementDurationMonths = "60", monthlyFeeInr = "170000")
        assertEquals(2, above3Years.brokerageMonthsSlab())
        assertEquals(340_000.0, above3Years.computedBrokerageInr(), 0.01)
    }
}
