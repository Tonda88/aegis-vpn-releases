package com.aegis.vpn

import com.aegis.vpn.config.ConfigParser
import com.aegis.vpn.config.SingBoxConfig
import com.aegis.vpn.config.Subscription
import com.aegis.vpn.data.VpnProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConfigGenerationTest {

    private val outputDir = File("build/generated-test-configs").apply { mkdirs() }

    private fun generate(name: String, raw: String): JSONObject {
        val parsed = ConfigParser.parse(raw).getOrThrow()
        val json = SingBoxConfig.build(parsed)
        File(outputDir, "$name.json").writeText(json)
        return JSONObject(json)
    }

    @Test
    fun vlessWebSocketTls() {
        val raw = "vless://11111111-2222-3333-4444-555555555555@example.com:443" +
            "?type=ws&security=tls&sni=example.com&path=%2Fws&host=example.com&fp=chrome" +
            "#Test%20VLESS"
        val parsed = ConfigParser.parse(raw).getOrThrow()
        assertEquals(VpnProtocol.VLESS, parsed.protocol)
        assertEquals("example.com", parsed.host)
        assertEquals(443, parsed.port)

        val root = generate("vless-ws", raw)
        val outbound = root.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("vless", outbound.getString("type"))
        assertEquals("/ws", outbound.getJSONObject("transport").getString("path"))
        assertTrue(outbound.getJSONObject("tls").getBoolean("enabled"))
        assertFalse(root.has("endpoints"))
    }

    @Test
    fun vlessReality() {
        val raw = "vless://11111111-2222-3333-4444-555555555555@1.2.3.4:443" +
            "?security=reality&sni=www.example.com&fp=chrome" +
            "&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-TXstqlzISY&sid=01234567" +
            "&type=tcp&flow=xtls-rprx-vision#Reality"
        val root = generate("vless-reality", raw)
        val outbound = root.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("xtls-rprx-vision", outbound.getString("flow"))
        val tls = outbound.getJSONObject("tls")
        assertTrue(tls.getJSONObject("reality").getBoolean("enabled"))
        assertEquals(
            "jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-TXstqlzISY",
            tls.getJSONObject("reality").getString("public_key")
        )
    }

    @Test
    fun shadowsocksSip002() {
        val raw = "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ=@1.2.3.4:8388#SS%20test"
        val root = generate("ss", raw)
        val outbound = root.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("shadowsocks", outbound.getString("type"))
        assertEquals("aes-256-gcm", outbound.getString("method"))
        assertEquals("password", outbound.getString("password"))
        assertEquals(8388, outbound.getInt("server_port"))
    }

    @Test
    fun wireGuardConfigUsesEndpoint() {
        val raw = """
            [Interface]
            PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
            Address = 10.66.66.2/32, fd42:42:42::2/128
            MTU = 1420

            [Peer]
            PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = wg.example.com:51820
            PersistentKeepalive = 25
        """.trimIndent()

        val parsed = ConfigParser.parse(raw).getOrThrow()
        assertEquals(VpnProtocol.WIREGUARD, parsed.protocol)
        assertTrue(parsed.isEndpoint)
        assertEquals("wg.example.com", parsed.host)
        assertEquals(51820, parsed.port)

        val root = generate("wg", raw)
        assertTrue(root.has("endpoints"))
        val endpoint = root.getJSONArray("endpoints").getJSONObject(0)
        assertEquals("wireguard", endpoint.getString("type"))
        assertEquals("proxy", endpoint.getString("tag"))
        assertEquals(1420, endpoint.getInt("mtu"))

        val outbounds = root.getJSONArray("outbounds")
        assertFalse((0 until outbounds.length()).any {
            outbounds.getJSONObject(it).optString("type") == "wireguard"
        })
        assertEquals("proxy", root.getJSONObject("route").getString("final"))
    }

    @Test
    fun wireGuardUrlIsParsed() {
        val raw = "wg://?privatekey=yAnz5TF%2BlXXJte14tji3zlMNq%2Bhd2rYUIgJBgB3fBmk%3D" +
            "&publickey=xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg%3D" +
            "&address=10.66.66.2%2F32&host=wg.example.com&port=51820#WG%20URL"
        val root = generate("wg-url", raw)
        assertTrue(root.has("endpoints"))
        val outbounds = root.getJSONArray("outbounds")
        assertEquals(1, outbounds.length())
        assertEquals("direct", outbounds.getJSONObject(0).getString("type"))
    }

    @Test
    fun subscriptionDecodesBase64List() {
        val plain = listOf(
            "vless://11111111-2222-3333-4444-555555555555@example.com:443?security=tls#One",
            "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ=@1.2.3.4:8388#Two",
            "hysteria2://password@example.com:443#Unsupported",
            "trojan://password@example.com:443#Unsupported"
        ).joinToString("\n")
        val encoded = java.util.Base64.getEncoder()
            .encodeToString(plain.toByteArray(Charsets.UTF_8))

        val keys = Subscription.decode(encoded)
        assertEquals(2, keys.size)
        assertTrue(keys[0].startsWith("vless://"))
        assertTrue(keys[1].startsWith("ss://"))
    }

    @Test
    fun subscriptionAcceptsPlainTextList() {
        val plain = "# comment\n\n" +
            "vless://11111111-2222-3333-4444-555555555555@example.com:443?security=tls#One\n" +
            "wireguard://?privatekey=abc&publickey=def&address=10.0.0.2%2F32&host=wg.example.com#WG"

        val keys = Subscription.decode(plain)
        assertEquals(2, keys.size)
        assertTrue(keys[1].startsWith("wireguard://"))
    }

    /**
     * Generates a sing-box config for every key of the real subscription snapshot
     * so that they can be validated against the real sing-box binary.
     */
    @Test
    fun generatesConfigsForSubscriptionSnapshot() {
        val source = File("../tools/subscription-keys.txt")
        assertTrue("subscription-keys.txt not found at ${source.absolutePath}", source.exists())

        val keys = source.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals(19, keys.size)

        val generated = File("build/generated-test-configs/subscription")
        generated.mkdirs()
        generated.listFiles()?.forEach { it.delete() }

        keys.forEachIndexed { index, key ->
            val parsed = ConfigParser.parse(key).getOrThrow()
            val json = SingBoxConfig.build(parsed)
            File(generated, String.format("sub-%02d.json", index)).writeText(json)
        }
        assertEquals(19, generated.listFiles()?.size ?: 0)
    }

    /**
     * sing-box >= 1.12 refuses a DNS server that detours to an "empty" direct
     * outbound. The bootstrap resolver must therefore stay detour-free.
     */
    @Test
    fun bootstrapDnsHasNoDetourToEmptyDirectOutbound() {
        val raw = "vless://11111111-2222-3333-4444-555555555555@example.com:443" +
            "?security=reality&sni=www.example.com&fp=chrome" +
            "&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-TXstqlzISY&sid=01234567" +
            "&type=tcp&flow=xtls-rprx-vision#Reality"

        val root = generate("dns-detour-check", raw)
        val servers = root.getJSONObject("dns").getJSONArray("servers")
        for (index in 0 until servers.length()) {
            val server = servers.getJSONObject(index)
            assertFalse(
                "DNS server '${server.optString("tag")}' must not detour to 'direct'",
                server.optString("detour") == "direct"
            )
        }
    }
}
