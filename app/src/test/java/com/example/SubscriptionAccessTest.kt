package com.example

import com.example.data.subscription.ActivationCodeRecord
import com.example.data.subscription.ActivationCodeStatus
import com.example.data.subscription.SubscriptionPlan
import com.example.data.subscription.SubscriptionStatus
import com.example.data.subscription.SubscriptionValidation
import com.example.data.subscription.SupportConfig
import com.example.data.subscription.UserSubscriptionRecord
import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class SubscriptionAccessTest {

    @Test
    fun testOnlyTwoPlansWithExactPricingAndDuration() {
        val plans = SubscriptionPlan.entries
        assertEquals(2, plans.size)

        val monthly = SubscriptionPlan.MONTHLY
        assertEquals("MONTHLY", monthly.displayName)
        assertEquals(500, monthly.pricePkr)
        assertEquals(1, monthly.durationMonths)

        val annual = SubscriptionPlan.GOLDEN_ANNUAL
        assertEquals("GOLDEN ANNUAL", annual.displayName)
        assertEquals(4000, annual.pricePkr)
        assertEquals(12, annual.durationMonths)
    }

    @Test
    fun testExpiryCalculationFitsRulesWindows() {
        val now = 1_760_000_000_000L
        val dayMs = 24L * 60L * 60L * 1000L

        val monthlyExp = SubscriptionValidation.calculateExpiryMillis(now, SubscriptionPlan.MONTHLY)
        val monthlyDays = (monthlyExp - now).toDouble() / dayMs
        assertTrue("Monthly duration ($monthlyDays d) must be within 27..32 days", monthlyDays in 27.0..32.0)

        val annualExp = SubscriptionValidation.calculateExpiryMillis(now, SubscriptionPlan.GOLDEN_ANNUAL)
        val annualDays = (annualExp - now).toDouble() / dayMs
        assertTrue("Golden Annual duration ($annualDays d) must be within 364..367 days", annualDays in 364.0..367.0)
    }

    @Test
    fun testAutoExpiryAndRevocationGating() {
        val now = 1_760_000_000_000L
        val futureExpiry = Timestamp(Date(now + 10_000_000L))
        val pastExpiry = Timestamp(Date(now - 5_000L))

        val activeUser = UserSubscriptionRecord(
            userId = "u1",
            email = "trader@gmail.com",
            subscriptionStatus = SubscriptionStatus.ACTIVE.name,
            accountDisabled = false,
            planId = SubscriptionPlan.MONTHLY.id,
            planName = SubscriptionPlan.MONTHLY.displayName,
            activeCode = "NXM-ABCD-1234",
            expiresAt = futureExpiry
        )
        assertTrue(activeUser.hasActiveAccess(now))
        assertEquals(SubscriptionStatus.ACTIVE, activeUser.effectiveStatus(now))

        val expiredUser = activeUser.copy(expiresAt = pastExpiry)
        assertFalse(expiredUser.hasActiveAccess(now))
        assertEquals(SubscriptionStatus.EXPIRED, expiredUser.effectiveStatus(now))

        val disabledUser = activeUser.copy(accountDisabled = true)
        assertFalse(disabledUser.hasActiveAccess(now))
        assertEquals(SubscriptionStatus.REVOKED, disabledUser.effectiveStatus(now))

        val revokedUser = activeUser.copy(subscriptionStatus = SubscriptionStatus.REVOKED.name)
        assertFalse(revokedUser.hasActiveAccess(now))
        assertEquals(SubscriptionStatus.REVOKED, revokedUser.effectiveStatus(now))
    }

    @Test
    fun testCodeValidationAndEffectiveExpiryStatus() {
        assertEquals("NXG-AB12-CD34", SubscriptionValidation.normalizeCode(" nxg-ab12-cd34 "))
        assertTrue(SubscriptionValidation.isValidCodeFormat("NXG-AB12-CD34"))
        assertFalse(SubscriptionValidation.isValidCodeFormat("SHORT"))
        assertFalse(SubscriptionValidation.isValidCodeFormat("INVALID/CODE"))

        val now = 1_760_000_000_000L
        val codePast = ActivationCodeRecord(
            code = "NXM-1122-3344",
            planId = SubscriptionPlan.MONTHLY.id,
            status = ActivationCodeStatus.ACTIVATED.name,
            expiresAt = Timestamp(Date(now - 1000L))
        )
        assertEquals(ActivationCodeStatus.EXPIRED, codePast.effectiveStatus(now))
    }

    @Test
    fun testSupportConfigDoesNotInventDefaultPhone() {
        val defaultConfig = SupportConfig()
        assertEquals("", defaultConfig.whatsappNumber)
        assertFalse(defaultConfig.hasConfiguredWhatsApp)

        val validConfig = SupportConfig(whatsappNumber = "+923001234567")
        assertTrue(validConfig.hasConfiguredWhatsApp)

        val normalized = SubscriptionValidation.normalizePhoneNumber("+92 300-1234567")
        assertEquals("+923001234567", normalized)
        assertTrue(SubscriptionValidation.isValidOptionalPhone(normalized))
        assertTrue(SubscriptionValidation.isValidOptionalPhone(""))
        assertFalse(SubscriptionValidation.isValidOptionalPhone("abc"))
    }
}
