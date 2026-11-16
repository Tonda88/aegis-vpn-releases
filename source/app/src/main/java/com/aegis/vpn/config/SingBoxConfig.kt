package com.aegis.vpn.config

import com.aegis.vpn.data.ParsedProfile
import org.json.JSONArray
import org.json.JSONObject

object SingBoxConfig {

    fun build(profile: ParsedProfile): String {
        val root = JSONObject()

        root.put(
            "log",
            JSONObject()
                .put("level", "warn")
                .put("timestamp", true)
        )

        // Application DNS goes through the proxy. A separate direct bootstrap
        // resolver is used only to resolve the proxy server hostname.
        //
        // NOTE: sing-box >= 1.12 rejects a `detour` that points to an "empty"
        // direct outbound ("detour to an empty direct outbound makes no
        // sense"). A DNS server *without* a detour uses the raw default
        // dialer, which sends the query straight to the physical interface
        // (auto_detect_interface protects it from the TUN). That is exactly
        // the bootstrap behaviour we need.
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

        root.put(
            "inbounds",
            JSONArray().put(
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
        )

        val outbounds = JSONArray()
        if (profile.isEndpoint) {
            // Since sing-box 1.11 WireGuard is configured as a top-level endpoint
            // and can be referenced by tag exactly like an outbound.
            root.put("endpoints", JSONArray().put(profile.outbound))
        } else {
            outbounds.put(profile.outbound)
        }
        outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))
        root.put("outbounds", outbounds)

        root.put(
            "route",
            JSONObject()
                .put(
                    "rules",
                    JSONArray().put(
                        JSONObject()
                            .put("ip_is_private", true)
                            .put("outbound", "direct")
                    )
                )
                .put("final", "proxy")
                .put("auto_detect_interface", true)
                .put("default_domain_resolver", "dns-bootstrap")
        )

        return root.toString(2)
    }
}
