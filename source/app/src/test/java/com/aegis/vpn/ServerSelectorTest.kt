package com.aegis.vpn

import com.aegis.vpn.core.ServerSelector
import com.aegis.vpn.data.ServerProfile
import com.aegis.vpn.data.VpnProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerSelectorTest {

    private fun server(name: String) = ServerProfile(
        name = name,
        protocol = VpnProtocol.VLESS,
        host = "$name.example.com",
        port = 443,
        raw = "vless://$name"
    )

    @Test
    fun picksServerWithLowestPing() = runBlocking {
        val servers = listOf(server("slow"), server("fast"), server("down"))
        val latencies = mapOf("slow" to 120, "fast" to 35, "down" to null)
        val best = ServerSelector.pickBest(servers) { latencies[it.name] }
        assertEquals("fast", best?.name)
    }

    @Test
    fun returnsNullWhenAllServersFail() = runBlocking {
        val best = ServerSelector.pickBest(listOf(server("a"), server("b"))) { null }
        assertNull(best)
    }

    @Test
    fun singleServerIsReturnedWithoutProbing() = runBlocking {
        val only = server("only")
        val best = ServerSelector.pickBest(listOf(only)) {
            throw AssertionError("single server must not be probed")
        }
        assertEquals(only.id, best?.id)
    }
}
