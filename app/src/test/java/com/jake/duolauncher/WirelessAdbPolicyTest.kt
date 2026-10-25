package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

class WirelessAdbPolicyTest {
    @Test fun `ports reject hosts privileged ports and command injection`() {
        listOf("", "0", "80", "1023", "65536", "-1", "127.0.0.1:12345", "12345;id", "12345\n4321", "１２３４５").forEach {
            assertNull(it, localAdbPort(it))
        }
        assertEquals(1024, localAdbPort("1024")); assertEquals(65535, localAdbPort("65535"))
    }
    @Test fun `code keeps leading zeros and rejects non ASCII or non six digits`() {
        assertTrue(validAdbPairingCode("001234"))
        listOf("12345", "1234567", "１２３４５６", "12345 ", "12;456", " 123456").forEach { assertFalse(validAdbPairingCode(it)) }
    }
    @Test fun `default home and connected service are both required`() {
        for (home in listOf(true, false)) for (connected in listOf(true, false)) {
            assertEquals(home && connected, wirelessSetupAllowed(home, connected))
        }
    }
    @Test fun `only one fixed grant is allowed`() {
        assertEquals("pm grant com.jake.duolauncher.core android.permission.WRITE_SECURE_SETTINGS", DUO18_NAVIGATION_GRANT)
    }
}
