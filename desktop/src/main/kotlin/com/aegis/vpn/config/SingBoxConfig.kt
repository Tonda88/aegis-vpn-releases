package com.aegis.vpn.config

import com.aegis.vpn.data.ParsedProfile
import org.json.JSONArray
import org.json.JSONObject

object SingBoxConfig {

    fun build(profile: ParsedProfile, tun: Boolean = true, mixedPort: Int = 2080, proxyDomains: Set<String> = emptySet()): String {
        val root = JSONObject()

        root.put(
            "log",
            JSONObject()
                .put("level", "warn")
                .put("timestamp", true)
        )

        root.put(
            "dns",
            JSONObject()
                .put(
                    "servers",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("type", "udp")
                                .put("tag", "dns-bootstrap")
                                .put("server", "8.8.8.8")
                        )
                        .put(
                            JSONObject()
                                .put("type", "https")
                                .put("tag", "dns-remote")
                                .put("server", "1.1.1.1")
                                .put("detour", "proxy")
                        )
                )
                .put("final", "dns-remote")
                .put("strategy", "prefer_ipv4")
        )

        val inbounds = JSONArray()
        if (tun) {
            inbounds.put(
                JSONObject()
                    .put("type", "tun")
                    .put("tag", "tun-in")
                    .put(
                        "address",
                        JSONArray()
                            .put("172.19.0.1/30")
                            .put("fdfe:dcba:9876::1/126")
                    )
                    .put("mtu", 9000)
                    .put("auto_route", true)
                    .put("strict_route", false)
                    .put("stack", "mixed")
            )
        }
        inbounds.put(
            JSONObject()
                .put("type", "mixed")
                .put("tag", "mixed-in")
                .put("listen", "127.0.0.1")
                .put("listen_port", mixedPort)
        )
        root.put("inbounds", inbounds)

        val outbounds = JSONArray()
        if (profile.isEndpoint) {
            root.put("endpoints", JSONArray().put(profile.outbound))
        } else {
            outbounds.put(profile.outbound)
        }
        outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))
        root.put("outbounds", outbounds)

        val rules = JSONArray().put(JSONObject().put("action", "sniff"))
        if (proxyDomains.isNotEmpty()) {
            val domainArr = JSONArray()
            val suffixArr = JSONArray()
            proxyDomains.map { it.trim().lowercase() }.filter { it.isNotBlank() }.forEach { d ->
                if (d.startsWith("*.")) suffixArr.put(d.removePrefix("*.")) else { suffixArr.put(d); domainArr.put(d) }
            }
            if (suffixArr.length() > 0) {
                val rule = JSONObject().put("outbound", "proxy")
                // domain_suffix covers both exact-ish suffix and subdomains; include keyword fallback not necessary
                val uniq = linkedSetOf<String>()
                for (i in 0 until suffixArr.length()) uniq.add(suffixArr.getString(i))
                val arr = JSONArray(); uniq.forEach { arr.put(it) }
                rule.put("domain_suffix", arr)
                rules.put(rule)
            }
        }
        rules.put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
        root.put(
            "route",
            JSONObject()
                .put("rules", rules)
                .put("final", "proxy")
                .put("auto_detect_interface", true)
                .put("default_domain_resolver", "dns-bootstrap")
        )

        return root.toString(2)
    }
}
