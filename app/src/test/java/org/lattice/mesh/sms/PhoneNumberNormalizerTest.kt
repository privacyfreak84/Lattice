package org.lattice.mesh.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneNumberNormalizerTest {
    @Test
    fun `already-international number normalizes regardless of region`() {
        // 2015550123 is libphonenumber's own documented <exampleNumber> for a US fixed line (see
        // resources/PhoneNumberMetadata.xml) — using it directly, rather than a hand-constructed number,
        // avoids repeating this test's first-version bug: it used 555 as the *area code*, which isn't a
        // real NANP area code at all (555 only exists as the reserved fictional/test *exchange*, e.g.
        // 201-555-01xx), so libphonenumber correctly rejected it as invalid and both assertions failed in CI.
        assertEquals("+12015550123", PhoneNumberNormalizer.normalize("+1 (201) 555-0123", defaultRegion = "GB"))
    }

    @Test
    fun `national-format number resolves country code from the default region`() {
        assertEquals("+12015550123", PhoneNumberNormalizer.normalize("(201) 555-0123", defaultRegion = "US"))
    }

    @Test
    fun `differently-formatted numbers for the same line normalize identically`() {
        val a = PhoneNumberNormalizer.normalize("+1-201-555-0123", defaultRegion = null)
        val b = PhoneNumberNormalizer.normalize("12015550123", defaultRegion = "US")
        assertEquals("+12015550123", a)
        assertEquals(a, b)
    }

    @Test
    fun `national-format number with no default region fails to normalize`() {
        assertNull(PhoneNumberNormalizer.normalize("201-555-0123", defaultRegion = null))
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
