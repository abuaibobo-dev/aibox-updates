package com.aurora.toolbox.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay

private val Void = Color(0xFF070B14)
private val Panel = Color(0xFF101827)
private val Panel2 = Color(0xFF162235)
private val Mint = Color(0xFF35E6C1)
private val Indigo = Color(0xFF6C7CFF)
private val Muted = Color(0xFF8492A6)
private val Danger = Color(0xFFFF5D73)
private val tabs = listOf("连接", "节点", "规则", "设置")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { OrvynApp(this) } }
}

@Composable private fun OrvynApp(activity: MainActivity) {
    var tab by remember { mutableIntStateOf(0) }
    MaterialTheme(colorScheme = darkColorScheme(primary = Mint, secondary = Indigo, background = Void, surface = Panel)) {
        Scaffold(containerColor = Void, bottomBar = { NavigationBar(containerColor = Panel) {
            tabs.forEachIndexed { index, label -> NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Text(listOf("◉", "◇", "⌁", "⚙")[index], fontSize = 21.sp) }, label = { Text(label) }, colors = NavigationBarItemDefaults.colors(indicatorColor = Mint.copy(.14f))) }
        } }) { padding -> Box(Modifier.padding(padding).fillMaxSize()) {
            AnimatedContent(tab, transitionSpec = { (fadeIn(tween(220)) + slideInHorizontally { it / 10 }) togetherWith fadeOut(tween(150)) }, label = "page") { page ->
                when (page) { 0 -> ConnectPage(activity) { tab = 1 }; 1 -> NodesPage(activity); 2 -> RulesPage(activity); else -> SettingsPage(activity) }
            }
        } }
    }
}

@Composable private fun Header(title: String, subtitle: String) = Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
    Text("ORVYN  /  SECURE NETWORK", color = Mint, fontSize = 11.sp, letterSpacing = 1.6.sp)
    Spacer(Modifier.height(5.dp)); Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 28.sp); Text(subtitle, color = Muted, fontSize = 13.sp)
}

@Composable private fun ConnectPage(activity: MainActivity, openNodes: () -> Unit) {
    val prefs = activity.getSharedPreferences(AuroraVpnService.PREFS, Context.MODE_PRIVATE)
    var connected by remember { mutableStateOf(prefs.getBoolean(AuroraVpnService.KEY_CONNECTED, false)) }
    var status by remember { mutableStateOf(prefs.getString(AuroraVpnService.KEY_STATUS, "未连接").orEmpty()) }
    val nodeText = prefs.getString(AuroraVpnService.KEY_NODE, "").orEmpty()
    val profile = remember(nodeText) { runCatching { SingBoxConfig.parse(nodeText) }.getOrNull() }
    LaunchedEffect(Unit) { while (true) { connected = prefs.getBoolean(AuroraVpnService.KEY_CONNECTED, false); status = prefs.getString(AuroraVpnService.KEY_STATUS, "未连接").orEmpty(); delay(700) } }
    fun start() = runCatching { activity.startForegroundService(Intent(activity, AuroraVpnService::class.java).setAction(AuroraVpnService.ACTION_CONNECT)) }
        .onFailure { prefs.edit().putString(AuroraVpnService.KEY_STATUS, "启动失败：${it.message}").apply() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && VpnService.prepare(activity) == null) start()
        else prefs.edit().putString(AuroraVpnService.KEY_STATUS, "VPN 授权被取消").apply()
    }
    val infinite = rememberInfiniteTransition(label = "connect")
    val spin by infinite.animateFloat(0f, 360f, infiniteRepeatable(tween(if (connected) 2600 else 6200, easing = LinearEasing)), label = "spin")
    val pulse by infinite.animateFloat(.35f, .85f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "pulse")
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Void, Color(0xFF091322), Void)))) {
        Header("智能代理", if (connected) "网络已由 ORVYN 安全接管" else "选择节点，一键连接")
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Box(Modifier.size(230.dp).rotate(spin).alpha(if (connected) pulse else .42f).border(2.dp, Brush.sweepGradient(listOf(Mint, Indigo, Color.Transparent, Mint)), CircleShape))
            Box(Modifier.size(190.dp).background(Brush.radialGradient(listOf((if (connected) Mint else Indigo).copy(.2f), Panel)), CircleShape).clickable {
                if (connected) activity.startService(Intent(activity, AuroraVpnService::class.java).setAction(AuroraVpnService.ACTION_DISCONNECT))
                else if (profile == null) openNodes() else VpnService.prepare(activity)?.let { permission.launch(it) } ?: start()
            }, contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(if (connected) "ON" else "OFF", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold); Text(if (connected) "点击断开" else "点击连接", color = if (connected) Mint else Muted) } }
        }
        Card(Modifier.padding(20.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) { Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(9.dp).background(if (connected) Mint else Danger, CircleShape)); Spacer(Modifier.width(9.dp)); Text(status, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Color.White.copy(.07f)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Metric("协议", profile?.protocol?.uppercase() ?: "--"); Metric("服务器", profile?.host?.take(18) ?: "未选择"); Metric("模式", "全局") }
        } }
    }
}

@Composable private fun Metric(label: String, value: String) = Column { Text(label, color = Muted, fontSize = 11.sp); Text(value, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }

@Composable private fun NodesPage(activity: MainActivity) {
    val prefs = activity.getSharedPreferences(AuroraVpnService.PREFS, Context.MODE_PRIVATE)
    var nodes by remember { mutableStateOf(NodeStore.load(prefs)) }; var showImport by remember { mutableStateOf(false) }; var message by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? -> uri?.let {
        BarcodeScanning.getClient().process(InputImage.fromFilePath(activity, it)).addOnSuccessListener { result ->
            val values = result.mapNotNull { code -> code.rawValue }
            val imported = values.flatMap { NodeImport.parseMany(it) }
            if (imported.isNotEmpty()) { nodes = NodeStore.merge(nodes, imported); NodeStore.save(prefs, nodes); prefs.edit().putString(AuroraVpnService.KEY_NODE, imported.first()).apply(); message = "已导入并选择 ${imported.size} 个节点" } else message = if (values.isEmpty()) "图片中没有识别到二维码" else "二维码已读取，但节点格式不兼容"
        }.addOnFailureListener { e -> message = "识别失败：${e.message}" }
    } }
    Column(Modifier.fillMaxSize().background(Void)) { Header("节点", "统一管理、识别与切换")
        Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button({ showImport = true }, Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("＋ 导入节点") }; OutlinedButton({ picker.launch("image/*") }, Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("▦ 扫码导入") } }
        if (message.isNotBlank()) Text(message, color = Mint, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
        if (nodes.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("还没有节点\n从链接、订阅、剪贴板或二维码导入", color = Muted) }
        else LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) { itemsIndexed(nodes) { index, raw ->
            val p = runCatching { SingBoxConfig.parse(raw) }.getOrNull(); Card(Modifier.fillMaxWidth().clickable { prefs.edit().putString(AuroraVpnService.KEY_NODE, raw).apply(); message = "已选择 ${p?.host ?: "节点"}" }, colors = CardDefaults.cardColors(containerColor = if (prefs.getString(AuroraVpnService.KEY_NODE, "") == raw) Panel2 else Panel), shape = RoundedCornerShape(17.dp)) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).background(Mint.copy(.12f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Text(p?.protocol?.take(2)?.uppercase() ?: "?", color = Mint) }; Spacer(Modifier.width(13.dp)); Column(Modifier.weight(1f)) { Text(p?.name ?: "节点 ${index + 1}", color = Color.White, fontWeight = FontWeight.SemiBold); Text("${p?.host ?: "无法解析"}:${p?.port ?: "--"}", color = Muted, fontSize = 12.sp) }; TextButton({ nodes = nodes.toMutableList().also { it.removeAt(index) }; NodeStore.save(prefs, nodes) }) { Text("删除", color = Danger) }
            } }
        } }
    }
    if (showImport) ImportDialog({ showImport = false }) { input -> val imported = NodeImport.parseMany(input); if (imported.isEmpty()) message = "未识别到兼容格式" else { nodes = NodeStore.merge(nodes, imported); NodeStore.save(prefs, nodes); message = "已导入 ${imported.size} 个节点" }; showImport = false }
}

@Composable private fun ImportDialog(onDismiss: () -> Unit, import: (String) -> Unit) { var text by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = onDismiss, title = { Text("导入中心") }, text = { Column { Text("支持单节点、多行链接和 Base64 订阅内容", color = Muted); Spacer(Modifier.height(10.dp)); OutlinedTextField(text, { text = it }, minLines = 6, maxLines = 12, label = { Text("粘贴节点或订阅内容") }) } }, confirmButton = { Button({ import(text) }) { Text("识别并导入") } }, dismissButton = { TextButton(onDismiss) { Text("取消") } }) }

@Composable private fun RulesPage(activity: MainActivity) { val prefs = activity.getSharedPreferences(AuroraVpnService.PREFS, Context.MODE_PRIVATE); var mode by remember { mutableStateOf(prefs.getString("route_mode", "全局代理") ?: "全局代理") }; var lan by remember { mutableStateOf(prefs.getBoolean("bypass_lan", true)) }; var ipv6 by remember { mutableStateOf(prefs.getBoolean("ipv6", false)) }
    Column(Modifier.fillMaxSize().background(Void)) { Header("路由规则", "决定哪些流量经过代理"); listOf("全局代理", "智能分流", "仅代理指定应用").forEach { item -> ListItem(headlineContent = { Text(item) }, leadingContent = { RadioButton(mode == item, { mode = item; prefs.edit().putString("route_mode", item).apply() }) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent)) }; SettingSwitch("绕过局域网", "访问路由器和局域网设备时直连", lan) { lan = it; prefs.edit().putBoolean("bypass_lan", it).apply() }; SettingSwitch("IPv6", "节点与网络均支持时启用", ipv6) { ipv6 = it; prefs.edit().putBoolean("ipv6", it).apply() } }
}

@Composable private fun SettingsPage(activity: MainActivity) { var hotspot by remember { mutableStateOf(false) }; Column(Modifier.fillMaxSize().background(Void)) { Header("设置", "核心、安全与共享"); Card(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(18.dp)) { Text("热点代理共享", color = Color.White, fontWeight = FontWeight.Bold); Text("无需 Root：其他设备手动设置局域网代理。透明共享需要 Root、Shizuku 或系统级权限。", color = Muted, fontSize = 13.sp); Spacer(Modifier.height(12.dp)); Button({ hotspot = !hotspot }) { Text(if (hotspot) "关闭说明" else "查看共享方式") }; if (hotspot) Text("当前版本先提供能力检测与说明，不会显示虚假的已共享状态。", color = Color(0xFFF5B942), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) } }; Spacer(Modifier.height(12.dp)); SettingsRow("核心版本", "sing-box 1.14.0"); SettingsRow("配置安全", "应用私有存储 · 日志脱敏"); SettingsRow("ORVYN", "版本 0.5.0 · 专业代理客户端") } }

@Composable private fun SettingSwitch(title: String, desc: String, checked: Boolean, change: (Boolean) -> Unit) = ListItem(headlineContent = { Text(title) }, supportingContent = { Text(desc) }, trailingContent = { Switch(checked, change) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
@Composable private fun SettingsRow(title: String, value: String) = ListItem(headlineContent = { Text(title) }, trailingContent = { Text(value, color = Muted) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))

private object NodeStore { private const val SEP = "\n---ORVYN---\n"; fun load(prefs: android.content.SharedPreferences): List<String> = prefs.getString("node_library", "").orEmpty().split(SEP).filter { it.isNotBlank() }.ifEmpty { listOfNotNull(prefs.getString(AuroraVpnService.KEY_NODE, null)?.takeIf { it.isNotBlank() }) }; fun save(prefs: android.content.SharedPreferences, nodes: List<String>) { prefs.edit().putString("node_library", nodes.joinToString(SEP)).apply() }; fun merge(old: List<String>, fresh: List<String>) = (old + fresh).distinctBy { it.trim() } }
private object NodeImport { private val schemes = Regex("(?i)(socks|socks5|socks5h|http|https|ss|vmess|vless|trojan|hysteria|hysteria2|hy2|tuic|anytls|shadowtls|ssh|naive|snell|wireguard|wg)://[^\\r\\n\\t ]+")
    fun parseMany(input: String): List<String> {
        val clean = input.trim().removePrefix("\uFEFF")
        val direct = schemes.findAll(clean).map { it.value.trim() }.toList()
        if (direct.isNotEmpty()) return direct.filter { runCatching { SingBoxConfig.parse(it) }.isSuccess }
        val compact = clean.replace(Regex("\\s"), "").replace('-', '+').replace('_', '/')
        val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
        val decoded = runCatching { android.util.Base64.decode(padded, android.util.Base64.DEFAULT).toString(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        return schemes.findAll(decoded).map { it.value.trim() }.filter { runCatching { SingBoxConfig.parse(it) }.isSuccess }.toList()
    }
}
