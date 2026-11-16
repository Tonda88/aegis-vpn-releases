package com.aegis.vpn.desktop

import com.aegis.vpn.config.ConfigParser
import com.aegis.vpn.config.Mojibake
import com.aegis.vpn.config.Subscription
import com.aegis.vpn.data.ServerProfile
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

class Store {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val dir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "AegisVPN-Desktop")
    private val file = File(dir, "profiles.json")

    data class State(
        val servers: MutableList<ServerProfile> = mutableListOf(),
        var selectedId: String? = null,
        var hwid: String = UUID.randomUUID().toString().replace("-", "").take(16),
        var autoProcesses: MutableSet<String> = linkedSetOf(
            "Telegram.exe", "AyuGram.exe",
            "Discord.exe", "DiscordCanary.exe", "DiscordPTB.exe",
            "WhatsApp.exe", "Signal.exe", "ChatGPT.exe"
        ),
        var autoDomains: MutableSet<String> = linkedSetOf(
            "youtube.com","youtu.be","googlevideo.com","ytimg.com","ggpht.com","youtubei.googleapis.com","youtube-nocookie.com","jnn-pa.googleapis.com",
            "instagram.com","cdninstagram.com","facebook.com","fbcdn.net","fb.com","fbsbx.com","messenger.com","meta.com","threads.net",
            "twitter.com","x.com","twimg.com","t.co",
            "discord.com","discord.gg","discordapp.com","discordapp.net","discord.media","discordcdn.com",
            "whatsapp.com","whatsapp.net",
            "t.me","telegram.org","telegram.me","telegra.ph","telesco.pe",
            "chatgpt.com","openai.com","oaistatic.com","oaiusercontent.com",
            "tiktok.com","tiktokv.com","tiktokcdn.com",
            "linkedin.com","licdn.com","medium.com",
            "rutracker.org","nnmclub.to","proton.me","protonvpn.com","canva.com","notion.so"
        ),
        var autoConnectEnabled: Boolean = true
    )

    var state: State = load()
        private set

    private fun load(): State {
        val loaded = try {
            if (file.exists()) {
                gson.fromJson(file.readText(), State::class.java) ?: State()
            } else State()
        } catch (_: Exception) { State() }

        var repaired = false
        loaded.servers.forEachIndexed { index, server ->
            val fixedName = Mojibake.repair(server.name)
            if (fixedName != server.name) {
                loaded.servers[index] = server.copy(name = fixedName)
                repaired = true
            }
        }
        if (repaired) {
            runCatching { file.writeText(gson.toJson(loaded)) }
        }
        return loaded
    }

    fun save() {
        dir.mkdirs()
        file.writeText(gson.toJson(state))
    }

    fun addRaw(rawInput: String): Result<ServerProfile> = runCatching {
        val parsed = ConfigParser.parse(rawInput).getOrThrow()
        val raw = rawInput.trim().removePrefix("\uFEFF")
        if (state.servers.any { it.raw.trim() == raw }) error("Этот ключ уже есть в списке")
        val profile = ServerProfile(
            name = parsed.name,
            protocol = parsed.protocol,
            host = parsed.host,
            port = parsed.port,
            raw = raw
        )
        state.servers.add(profile)
        if (state.selectedId == null) state.selectedId = profile.id
        save()
        profile
    }

    fun importKeys(raws: List<String>): Pair<Int, Int> {
        var added = 0; var skipped = 0
        raws.forEach { raw ->
            val r = addRaw(raw)
            if (r.isSuccess) added++ else skipped++
        }
        return added to skipped
    }

    fun fetchSubscription(url: String): List<String> {
        val body = Subscription.fetch(url, state.hwid)
        save()
        return Subscription.decode(body)
    }

    fun select(id: String) { state.selectedId = id; save() }
    fun delete(id: String) {
        state.servers.removeAll { it.id == id }
        if (state.selectedId == id) state.selectedId = state.servers.firstOrNull()?.id
        save()
    }
    fun rename(id: String, name: String) {
        val idx = state.servers.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val s = state.servers[idx]
            state.servers[idx] = s.copy(name = name.trim().ifBlank { s.name })
            save()
        }
    }
    fun selected(): ServerProfile? = state.servers.firstOrNull { it.id == state.selectedId } ?: state.servers.firstOrNull()
}
