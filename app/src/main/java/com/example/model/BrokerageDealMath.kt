package com.example.model

/**
 * Pure calculations for the Gemini brokerage module.
 * No persistence, network access or changes to legacy property identifiers.
 * Values are indicative and must be reviewed against the signed deal terms.
 */
object BrokerageDealMath {
    fun leaseBrokerage(monthlyRent: Double, months: Double): Double {
        require(monthlyRent >= 0.0 && monthlyRent.isFinite())
        require(months >= 0.0 && months.isFinite())
        return monthlyRent * months
    }

    fun saleBrokerage(salePrice: Double, percent: Double): Double {
        require(salePrice >= 0.0 && salePrice.isFinite())
        require(percent in 0.0..100.0)
        return salePrice * percent / 100.0
    }

    fun gstOnBrokerage(brokerage: Double, applyGst: Boolean, rate: Double = 0.18): Double {
        require(brokerage >= 0.0 && brokerage.isFinite())
        require(rate in 0.0..1.0)
        return if (applyGst) brokerage * rate else 0.0
    }

    fun totalWithGst(brokerage: Double, applyGst: Boolean): Double =
        brokerage + gstOnBrokerage(brokerage, applyGst)

    fun securityDeposit(monthlyRent: Double, depositMonths: Double): Double =
        leaseBrokerage(monthlyRent, depositMonths)

    fun milestoneAmount(salePrice: Double, percentage: Double): Double =
        saleBrokerage(salePrice, percentage)
}

