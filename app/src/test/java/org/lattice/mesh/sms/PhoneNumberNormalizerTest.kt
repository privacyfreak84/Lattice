package org.lattice.mesh.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneNumberNormalizerTest {
    @Test
    fun `already-international number normalizes regardless of region`() {
        assertEquals("+15551234567", PhoneNumberNormalizer.normalize("+1 (555) 123-4567", defaultRegion = "GB"))
    }

    @Test
    fun `national-format number resolves country code from the default region`() {
        assertEquals("+15551234567", PhoneNumberNormalizer.normalize("(555) 123-4567", defaultRegion = "US"))
    }

    @Test
    fun `differently-formatted numbers for the same line normalize identically`() {
        val a = PhoneNumberNormalizer.normalize("+1-555-123-4567", defaultRegion = null)
        val b = PhoneNumberNormalizer.normalize("15551234567", defaultRegion = "US")
        assertEquals(a, b)
    }

    @Test
    fun `national-format number with no default region fails to normalize`() {
        assertNull(PhoneNumberNormalizer.normalize("555-123-4567", defaultRegion = null))
    }

    @Test
    fun `alphanumeric sender ID fails to normalize`() {
        assertNull(PhoneNumberNormalizer.normalize("AIRTIME", defaultRegion = "US"))
    }

    @Test
    fun `blank input fails to normalize`() {
        assertNull(PhoneNumberNormalizer.normalize("", defaultRegion = "US"))
    }

    @Test
    fun `implausibly short digit string fails to normalize`() {
        assertNull(PhoneNumberNormalizer.normalize("12345", defaultRegion = "US"))
    }
}
