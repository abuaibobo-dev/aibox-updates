package dev.abuaibobo.jpstock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = DarkColorScheme) {
                AppRoot()
            }
        }
    }
}

@Composable
fun AppRoot() {
    var tab by remember { mutableStateOf(0) }
    var detail by remember { mutableStateOf<Pick?>(null) }

    if (detail != null) {
        PickDetailScreen(detail!!, onBack = { detail = null })
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = {},
                    label = { Text("推荐") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = {},
                    label = { Text("行情") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = {},
                    label = { Text("跟踪") },
                )
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> PicksScreen(onPick = { detail = it })
                1 -> MarketScreen()
                2 -> HistoryScreen()
            }
        }
    }
}

@Composable
fun PicksScreen(onPick: (Pick) -> Unit) {
    var picks by remember { mutableStateOf<List<Pick>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    fun load() {
        scope.launch {
            loading = true; error = null
            try { picks = Api.fetchDaily() }
            catch (e: Exception) { error = e.message ?: "加载失败" }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("日本股市 每日推荐", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("量化多因子 · 行业分散 · 非投资建议", fontSize = 12.sp, color = FlatGray)
        Spacer(Modifier.height(10.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            error != null -> ErrorBox(error!!) { load() }
            picks != null -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(picks!!, key = { it.code }) { p ->
                    PickCard(p, onClick = { onPick(p) })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PickCard(p: Pick, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text("${p.code}  ${p.name}", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text("¥${fmt(p.price)}", fontWeight = FontWeight.Bold)
            }
            Text("${p.industry} · 综合分 ${fmt1(p.score)}", fontSize = 12.sp, color = FlatGray)
            Spacer(Modifier.height(6.dp))
            val chips = buildList {
                p.per?.let { add("PE ${fmt1(it)}") }
                p.pbr?.let { add("PB ${fmt2(it)}") }
                p.roe?.let { add("ROE ${fmt1(it)}%") }
                p.divYield?.let { add("股息 ${fmt1(it)}%") }
                p.m6?.let { add("6M ${pct(it)}") }
                p.m12?.let { add("12M ${pct(it)}") }
            }
            Text(chips.joinToString("  ·  "), fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(p.reason, fontSize = 13.sp, color = TextSecondary)
            Spacer(Modifier.height(4.dp))
            Text("点按查看走势 →", fontSize = 12.sp, color = AccentBlue)
        }
    }
}

@Composable
fun ErrorBox(msg: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("⚠ 加载失败: $msg", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRetry) { Text("重试") }
    }
}

fun fmt(v: Double): String = java.text.DecimalFormat("#,##0").format(v)
fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)
fun pct(v: Double): String = String.format(Locale.US, "%+.0f%%", v * 100)
