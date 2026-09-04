package dev.abuaibobo.jpstock

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickDetailScreen(p: Pick, onBack: () -> Unit) {
    var candles by remember { mutableStateOf<List<KLine>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    fun load() {
        scope.launch {
            loading = true; error = null
            try { candles = Api.fetchCandles(p.code) }
            catch (e: Exception) { error = e.message ?: "K线加载失败" }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${p.code} ${p.name}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(14.dp)) {
            Text("${p.industry} · 现价 ¥${fmt(p.price)} · 综合分 ${fmt1(p.score)}",
                fontSize = 13.sp, color = Color.Gray)
            Spacer(Modifier.height(4.dp))

            val chips = buildList {
                p.per?.let { add("PE ${fmt1(it)}") }
                p.pbr?.let { add("PB ${fmt2(it)}") }
                p.roe?.let { add("ROE ${fmt1(it)}%") }
                p.divYield?.let { add("股息 ${fmt1(it)}%") }
                p.m6?.let { add("6个月 ${pct(it)}") }
                p.m12?.let { add("1年 ${pct(it)}") }
            }
            Text(chips.joinToString("  ·  "), fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))

            when {
                loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                error != null -> ErrorBox(error!!, load)
                candles != null -> {
                    Text("近1年日K (红涨蓝跌, 黄MA20 紫MA60)", fontSize = 12.sp, color = Color.Gray)
                    CandlestickChart(candles!!, Modifier.fillMaxWidth().height(280.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(p.reason, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    val ctx = LocalContext.current
                    ShareRow(p, ctx)
                    Text("免责声明: 量化信号，非投资建议", fontSize = 11.sp, color = Color.Gray)
                }
            }
        }
    }
}
