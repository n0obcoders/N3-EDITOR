package com.nitinshukla.notebook

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nitinshukla.notebook.security.NoteCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Needs a real Android Keystore, so it runs as an instrumented test. */
@RunWith(AndroidJUnit4::class)
class NoteCryptoTest {
    @Test
    fun roundTripAndTamperDetection() {
        val crypto = NoteCrypto()
        val cipher = crypto.encrypt("secret ✓")
        assertTrue(crypto.isEncrypted(cipher))
        assertNotEquals("secret ✓", cipher)
        assertEquals("secret ✓", crypto.decrypt(cipher))
        assertNull(crypto.decrypt(cipher.dropLast(4) + "AAAA"))
        assertEquals("plain", crypto.decrypt("plain"))
        // Two encryptions of the same text differ because of the random IV.
        assertNotEquals(cipher, crypto.encrypt("secret ✓"))
    }
}
