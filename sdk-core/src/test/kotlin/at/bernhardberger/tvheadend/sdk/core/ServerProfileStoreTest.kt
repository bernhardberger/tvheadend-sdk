package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.core.gateway.ServerAuthentication as GatewayAuthentication
import java.io.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ServerProfileStoreTest {
    @Test
    fun `supported factories normalize and validate connectable profiles`() {
        val anonymous = ServerProfileReadResult.anonymous("  test.invalid  ", 4_242)
        val password = ServerProfileReadResult.password(
            host = "  secure.invalid  ",
            port = 5_353,
            username = " user ",
            password = " exact password ",
        )

        assertEquals("test.invalid", anonymous.host)
        assertEquals(4_242, anonymous.port)
        assertSame(ServerProfileAuthenticationMode.ANONYMOUS, anonymous.authenticationMode)
        assertEquals("secure.invalid", password.host)
        assertEquals(5_353, password.port)
        assertSame(ServerProfileAuthenticationMode.PASSWORD, password.authenticationMode)
        val authentication = password.profile.toGatewayConfiguration().authentication
            as GatewayAuthentication.Password
        assertEquals("user", authentication.username)
        assertEquals(" exact password ", authentication.password)
        assertThrows(IllegalArgumentException::class.java) {
            ServerProfileReadResult.anonymous("  ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerProfileReadResult.password(
                "test.invalid",
                username = " ",
                password = "password",
            )
        }
    }

    @Test
    fun `available profile result has no generated credential-bearing value APIs`() {
        val first = ServerProfileReadResult.password(
            "private.invalid",
            username = "private-user",
            password = "private-password",
        )
        val second = ServerProfileReadResult.password(
            "private.invalid",
            username = "private-user",
            password = "private-password",
        )
        val methods = ServerProfileReadResult.Available::class.java.declaredMethods
            .filterNot { method -> method.isSynthetic }
            .mapTo(mutableSetOf()) { method -> method.name }

        assertEquals(setOf("getProfile", "getHost", "getPort", "getAuthenticationMode", "toString"), methods)
        assertEquals(
            0,
            ServerProfileReadResult.Available::class.java.constructors
                .count { constructor -> !constructor.isSynthetic },
        )
        assertFalse(methods.any { method -> method.startsWith("component") || method == "copy" })
        assertFalse(Serializable::class.java.isAssignableFrom(ServerProfileReadResult.Available::class.java))
        assertNotEquals(first, second)
        assertEquals("ServerProfileReadResult.Available(<redacted>)", first.toString())
        assertEquals("ServerProfile(<redacted>)", first.profile.toString())
        assertFalse(first.toString().contains("private", ignoreCase = true))
        assertTrue(ServerProfileReadResult.Unavailable.toString().isNotBlank())
    }
}
