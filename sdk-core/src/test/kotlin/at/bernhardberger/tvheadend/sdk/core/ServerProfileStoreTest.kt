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
    fun `only password summaries may mark credentials unavailable`() {
        val id = ServerProfileId.generate()
        assertThrows(IllegalArgumentException::class.java) {
            ServerProfileSummary.create(id, null, "test.invalid", 9982, ServerProfileAuthenticationMode.ANONYMOUS, false)
        }
        assertFalse(ServerProfileSummary.create(id, null, "test.invalid", 9982,
            ServerProfileAuthenticationMode.PASSWORD, false).credentialsAvailable)
    }

    @Test
    fun `supported factories normalize and validate connectable profiles`() {
        val anonymous = ServerProfileReadResult.anonymous(ServerProfileId.generate(), "  test.invalid  ", 4_242)
        val password = ServerProfileReadResult.password(
            id = ServerProfileId.generate(),
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
            ServerProfileReadResult.anonymous(ServerProfileId.generate(), "  ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerProfileReadResult.password(
                ServerProfileId.generate(),
                "test.invalid",
                username = " ",
                password = "password",
            )
        }
    }

    @Test
    fun `available profile result has no generated credential-bearing value APIs`() {
        val first = ServerProfileReadResult.password(
            ServerProfileId.generate(),
            "private.invalid",
            username = "private-user",
            password = "private-password",
        )
        val second = ServerProfileReadResult.password(
            ServerProfileId.generate(),
            "private.invalid",
            username = "private-user",
            password = "private-password",
        )
        val methods = ServerProfileReadResult.Available::class.java.declaredMethods
            .filterNot { method -> method.isSynthetic }
            .mapTo(mutableSetOf()) { method -> method.name }

        assertEquals(setOf("getId", "getLabel", "getProfile", "getHost", "getPort", "getAuthenticationMode", "toString"), methods)
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

    @Test
    fun `ids require canonical UUID and labels normalize without leaking fields`() {
        val id = ServerProfileId.from("12345678-1234-1234-1234-123456789abc")
        assertEquals(id, ServerProfileId.from(id.value))
        assertEquals(id.hashCode(), ServerProfileId.from(id.value).hashCode())
        listOf("1-1-1-1-1", "invalid", id.value.uppercase()).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { ServerProfileId.from(invalid) }
        }
        val profile = ServerProfileReadResult.anonymous(id, "private.invalid", label = "  Home  ")
        assertEquals("Home", profile.label)
        assertEquals(null, ServerProfileReadResult.anonymous(id, "private.invalid", label = "   ").label)
        listOf("x".repeat(65), "a\nb", "\t").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                ServerProfileReadResult.anonymous(id, "private.invalid", label = invalid)
            }
        }
        val summary = ServerProfileSummary.create(id, "Home", profile.host, profile.port, profile.authenticationMode)
        assertEquals("ServerProfileSummary(<redacted>)", summary.toString())
        assertEquals("ServerProfileListResult.Available(<redacted>)", ServerProfileListResult.Available(listOf(summary), id).toString())
    }
}
