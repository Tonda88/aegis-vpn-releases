package com.aegis.vpn.ui.screens

import com.aegis.vpn.ui.theme.AppColors

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aegis.vpn.R
import com.aegis.vpn.data.ServerProfile
import com.aegis.vpn.data.VpnProtocol
import com.aegis.vpn.ui.components.GlassCard
import com.aegis.vpn.ui.components.IconCircleButton
import com.aegis.vpn.ui.components.Pill
import com.aegis.vpn.ui.components.ScreenTitle
import com.aegis.vpn.ui.components.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

@Composable
fun ServersScreen(
    servers: List<ServerProfile>,
    selectedId: String?,
    onSelect: (ServerProfile) -> Unit,
    onDelete: (ServerProfile) -> Unit,
    onDeleteAll: () -> Unit,
    onRename: (ServerProfile, String) -> Unit,
    onAdd: () -> Unit,
    onScan: () -> Unit
) {
    var pendingDelete by remember { mutableStateOf<ServerProfile?>(null) }
    var pendingRename by remember { mutableStateOf<ServerProfile?>(null) }
    var pendingDeleteAll by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Ping state: null = not measured, -1 = timeout, >= 0 = ms
    val pings = remember { mutableStateMapOf<String, Long>() }
    var pinging by remember { mutableStateOf(false) }
    var sortByPing by remember { mutableStateOf(false) }

    fun startPing() {
        if (pinging) return
        pinging = true
        sortByPing = false
        pings.clear()
        scope.launch {
            servers.map { server ->
                async(Dispatchers.IO) {
                    val t = measureTcpPingMs(server.host, server.port)
                    pings[server.id] = t
                }
            }.awaitAll()
            pinging = false
            sortByPing = true
        }
    }

    val displayedServers = remember(servers, sortByPing, pings.size) {
        if (sortByPing && pings.isNotEmpty()) {
            servers.sortedWith(compareBy {
                val p = pings[it.id] ?: Long.MAX_VALUE
                if (p < 0) Long.MAX_VALUE else p
            })
        } else servers
    }

    val groups = remember(displayedServers, sortByPing, pings.size) {
        if (sortByPing && pings.isNotEmpty()) {
            // Single flat group when sorted by ping
            listOf("По пингу" to displayedServers)
        } else {
            val map = linkedMapOf<String, MutableList<ServerProfile>>()
            displayedServers.forEach { server ->
                val key = serverGroupName(server)
                map.getOrPut(key) { mutableListOf() }.add(server)
            }
            map.entries.map { it.key to it.value.toList() }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 32.dp)
    ) {
        item {
            ScreenTitle(
                title = "Серверы",
                subtitle = "Ключи VLESS, Shadowsocks и WireGuard"
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (servers.isNotEmpty()) {
                        // Ping sort button
                        IconCircleButton(
                            icon = R.drawable.ic_bolt,
                            contentDescription = "Сортировать по пингу",
                            onClick = { startPing() },
                            tint = if (sortByPing) AppColors.accent else AppColors.textSecondary
                        )
                        IconCircleButton(
                            icon = R.drawable.ic_delete,
                            contentDescription = "Удалить все серверы",
                            onClick = { pendingDeleteAll = true },
                            tint = AppColors.danger
                        )
                    }
                    IconCircleButton(
                        icon = R.drawable.ic_scan,
                        contentDescription = "Сканировать QR",
                        onClick = onScan
                    )
                    IconCircleButton(
                        icon = R.drawable.ic_add,
                        contentDescription = "Добавить сервер",
                        onClick = onAdd,
                        tint = Color(0xFF06100E),
                        background = AppColors.accent
                    )
                }
            }
        }

        item { Spacer(Modifier.height(22.dp)) }

        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                borderColor = AppColors.accent.copy(alpha = 0.18f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(AppColors.accent.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_add),
                            contentDescription = null,
                            tint = AppColors.accent,
                            modifier = Modifier.size(23.dp)
                        )
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Новый сервер",
                            color = AppColors.textPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Вставьте ссылку или отсканируйте QR-код",
                            color = AppColors.textSecondary,
                            fontSize = 11.5.sp
                        )
                    }
                }
                Spacer(Modifier.height(15.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onScan,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.textPrimary)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_scan),
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(Modifier.width(7.dp))
                        Text("QR-код", fontSize = 12.sp)
                    }
                    Button(
                        onClick = onAdd,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.accent,
                            contentColor = Color(0xFF06100E)
                        )
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_paste),
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(Modifier.width(7.dp))
                        Text("Добавить ключ", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionLabel(if (sortByPing) "По пингу" else "Мои серверы")
                Spacer(Modifier.weight(1f))
                if (pinging) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.5.dp,
                        color = AppColors.accent
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Измеряю…",
                        color = AppColors.textSecondary,
                        fontSize = 11.sp
                    )
                } else if (sortByPing) {
                    Text(
                        text = "отсортировано · ${servers.size}",
                        color = AppColors.accent,
                        fontSize = 11.sp
                    )
                } else {
                    Text(
                        text = "${servers.size}",
                        color = AppColors.textSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }

        item { Spacer(Modifier.height(10.dp)) }

        if (servers.isEmpty()) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_server),
                            contentDescription = null,
                            tint = AppColors.textSecondary.copy(alpha = 0.6f),
                            modifier = Modifier.size(38.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Пока нет ни одного сервера",
                            color = AppColors.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Нажмите «Добавить ключ» или отсканируйте QR",
                            color = AppColors.textSecondary,
                            fontSize = 11.5.sp
                        )
                    }
                }
            }
        } else {
            groups.forEach { (groupName, groupServers) ->
                item(key = "group_header_$groupName") {
                    GroupHeader(
                        title = groupName,
                        count = groupServers.size
                    )
                    Spacer(Modifier.height(8.dp))
                }
                items(groupServers, key = { it.id }) { server ->
                    ServerCard(
                        server = server,
                        selected = server.id == selectedId,
                        ping = pings[server.id],
                        pinging = pinging && pings[server.id] == null,
                        onClick = { onSelect(server) },
                        onLongClick = { pendingRename = server },
                        onDelete = { pendingDelete = server }
                    )
                    Spacer(Modifier.height(8.dp))
                }
                item(key = "group_spacer_$groupName") {
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }

        item {
            Text(
                text = "Ключи хранятся только на этом устройстве. Приложение не отправляет их на сторонние серверы.",
                color = AppColors.textSecondary.copy(alpha = 0.72f),
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
        }
    }

    pendingDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = AppColors.surfaceHigh,
            titleContentColor = AppColors.textPrimary,
            textContentColor = AppColors.textSecondary,
            title = { Text("Удалить сервер?") },
            text = { Text("«${server.name}» будет удалён с этого устройства.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(server)
                        pendingDelete = null
                    }
                ) {
                    Text("Удалить", color = AppColors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Отмена", color = AppColors.textSecondary)
                }
            }
        )
    }

    if (pendingDeleteAll) {
        AlertDialog(
            onDismissRequest = { pendingDeleteAll = false },
            containerColor = AppColors.surfaceHigh,
            titleContentColor = AppColors.textPrimary,
            textContentColor = AppColors.textSecondary,
            title = { Text("Удалить все серверы?") },
            text = { Text("Будут удалены все ключи и конфиги (${servers.size}). Это действие нельзя отменить.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteAll()
                        pendingDeleteAll = false
                    }
                ) {
                    Text("Удалить все", color = AppColors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteAll = false }) {
                    Text("Отмена", color = AppColors.textSecondary)
                }
            }
        )
    }

    pendingRename?.let { server ->
        var name by remember(server.id) { mutableStateOf(server.name) }
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            containerColor = AppColors.surfaceHigh,
            titleContentColor = AppColors.textPrimary,
            textContentColor = AppColors.textSecondary,
            title = { Text("Имя сервера") },
            text = {
                Column {
                    Text(
                        "Укажите имя провайдера или удобное название.",
                        color = AppColors.textSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = AppColors.surface,
                            unfocusedContainerColor = AppColors.surface,
                            focusedBorderColor = AppColors.accent.copy(alpha = 0.55f),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.08f),
                            focusedTextColor = AppColors.textPrimary,
                            unfocusedTextColor = AppColors.textPrimary,
                            cursorColor = AppColors.accent
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRename(server, name)
                        pendingRename = null
                    }
                ) {
                    Text("Сохранить", color = AppColors.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) {
                    Text("Отмена", color = AppColors.textSecondary)
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerCard(
    server: ServerProfile,
    selected: Boolean,
    ping: Long?,
    pinging: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit
) {
    val accent = protocolColor(server.protocol)
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (selected) accent.copy(alpha = 0.075f) else AppColors.surface.copy(alpha = 0.7f)
            )
            .border(
                width = 1.dp,
                color = if (selected) accent.copy(alpha = 0.48f) else Color.White.copy(alpha = 0.06f),
                shape = shape
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(accent.copy(alpha = 0.12f))
                .border(1.dp, accent.copy(alpha = 0.16f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = server.protocol.shortLabel,
                color = accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp
            )
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = server.name,
                    color = AppColors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (selected) {
                    Spacer(Modifier.width(7.dp))
                    Pill("АКТИВЕН", accent, dot = true)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${server.protocol.label} • ${server.endpointLabel}",
                    color = AppColors.textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                // Ping badge
                when {
                    pinging -> {
                        Spacer(Modifier.width(6.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(10.dp),
                            strokeWidth = 1.2.dp,
                            color = AppColors.textSecondary
                        )
                    }
                    ping != null -> {
                        Spacer(Modifier.width(6.dp))
                        val (pingText, pingColor) = when {
                            ping < 0 -> "—" to AppColors.danger
                            ping < 150 -> "${ping}мс" to AppColors.accent
                            ping < 400 -> "${ping}мс" to AppColors.blue
                            else -> "${ping}мс" to AppColors.danger
                        }
                        Text(
                            text = pingText,
                            color = pingColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onLongClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_edit),
                contentDescription = "Переименовать",
                tint = AppColors.textSecondary.copy(alpha = 0.75f),
                modifier = Modifier.size(17.dp)
            )
        }
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_delete),
                contentDescription = "Удалить",
                tint = AppColors.danger.copy(alpha = 0.85f),
                modifier = Modifier.size(17.dp)
            )
        }
    }
}

@Composable
private fun GroupHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = AppColors.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(AppColors.surfaceHigh.copy(alpha = 0.7f))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(999.dp))
                .padding(horizontal = 9.dp, vertical = 3.dp)
        ) {
            Text(
                text = count.toString(),
                color = AppColors.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private fun serverGroupName(server: ServerProfile): String {
    val name = server.name.trim()
    if (name.isBlank()) return "Серверы"
    val separators = listOf(" · ", " | ", " - ")
    for (sep in separators) {
        val idx = name.indexOf(sep)
        if (idx > 0) return name.substring(0, idx).trim().ifBlank { name }
    }
    return name
}

@Composable
fun protocolColor(protocol: VpnProtocol): Color = when (protocol) {
    VpnProtocol.VLESS -> AppColors.accent
    VpnProtocol.SHADOWSOCKS -> AppColors.blue
    VpnProtocol.WIREGUARD -> AppColors.violet
}

/** TCP connect ping in ms, or -1 on timeout/error. */
private fun measureTcpPingMs(host: String, port: Int, timeoutMs: Int = 3000): Long {
    return runCatching {
        val start = System.currentTimeMillis()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
        }
        System.currentTimeMillis() - start
    }.getOrDefault(-1L)
}
