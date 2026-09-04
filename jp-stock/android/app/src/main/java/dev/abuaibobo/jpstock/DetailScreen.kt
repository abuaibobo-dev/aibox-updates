package dev.abuaibobo.jpstock

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
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
    LaunchedEffect(Unit) {
        load()
        while (true) { delay(60_000); load() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${p.code} ${p.name}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { load() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
            .padding(14.dp)) {
            Text("${p.industry} · 现价 ¥${fmt(p.price)} · 综合分 ${fmt1(p.score)}",
                fontSize = 13.sp, color = FlatGray)
            Spacer(Modifier.height(6.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                p.pbrPct?.let { PercentChip("PB历史分位", it, lowerBetter = true) }
                p.perPct?.let { PercentChip("PE历史分位", it, lowerBetter = true) }
            }
            Spacer(Modifier.height(8.dp))

            when {
                loading -> Box(Modifier.fillMaxWidth().height(200.dp), Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null -> ErrorBox(error!!) { load() }
                candles != null -> {
                    Text("近1年日K · 延迟行情(自动60s刷新) · 红涨蓝跌 · 蓝虚线=支撑 红虚线=压力",
                        fontSize = 12.sp, color = FlatGray)
                    val kls = candles!!
                    val (sup, res) = findKeyLevels(
                        kls.map { it.high }, kls.map { it.low }, kls.last().close)
                    CandlestickChart(kls, Modifier.fillMaxWidth().height(240.dp),
                        supports = sup, resistances = res)
                    Spacer(Modifier.height(10.dp))

                    val ind = computeIndicators(candles!!.map { it.close })
                    IndicatorPanel(ind, p)
                    Spacer(Modifier.height(10.dp))

                    if (p.aiReason.isNotBlank()) {
                        Card(Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = AccentBlue.copy(alpha = 0.10f))) {
                            Column(Modifier.padding(12.dp)) {
                                Text("AI 解读", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                                    color = AccentBlue)
                                Spacer(Modifier.height(4.dp))
                                Text(p.aiReason, fontSize = 13.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardDark)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("量化评分", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(p.reason, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    val ctx = LocalContext.current
                    ShareRow(p, ctx)
                    Text("免责声明: 量化信号，非投资建议", fontSize = 11.sp, color = FlatGray)
                }
            }
        }
    }
}

@Composable
fun PercentChip(label: String, value: Double, lowerBetter: Boolean) {
    val low = value <= 25
    val high = value >= 75
    val note = when {
        low && lowerBetter -> "历史低位"
        high && !lowerBetter -> "历史高位"
        else -> ""
    }
    val color = when {
        low && lowerBetter -> UpRed
        high && !lowerBetter -> DownBlue
        else -> FlatGray
    }
    Card(colors = CardDefaults.cardColors(containerColor = CardDark)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(label, fontSize = 11.sp, color = FlatGray)
            Text("${value.toInt()}% $note", fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, color = color)
        }
    }
}

@Composable
fun IndicatorPanel(ind: TechIndicators, p: Pick) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardDark)) {
        Column(Modifier.padding(12.dp)) {
            Text("技术指标", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            ind.rsi?.let {
                MetricRow("RSI(14)", fmt1(it), when {
                    it >= 70 -> "超买" to UpRed
                    it <= 30 -> "超卖" to DownBlue
                    else -> "中性" to FlatGray
                })
            }
            ind.macd?.let { m ->
                val sig = ind.macdSignal ?: 0.0
                MetricRow("MACD", fmt2(m), if (m >= sig) "多头" to UpRed else "空头" to DownBlue)
            }
            ind.macdHist?.let { MetricRow("MACD柱", fmt2(it), null) }
            ind.bbPct?.let {
                MetricRow("布林位置", "${it.toInt()}%", when {
                    it >= 90 -> "近上轨" to UpRed
                    it <= 10 -> "近下轨" to DownBlue
                    else -> "中轨" to FlatGray
                })
            }
            ind.ma20?.let { MetricRow("MA20", fmt(it), null) }
            ind.ma60?.let { MetricRow("MA60", fmt(it), null) }
            ind.ma200?.let { MetricRow("MA200", fmt(it), null) }
            p.m6?.let { MetricRow("6月动量", pct(it), null) }
            p.m12?.let { MetricRow("1年动量", pct(it), null) }
        }
    }
}

@Composable
fun MetricRow(label: String, value: String, state: Pair<String, Color>?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(1f), fontSize = 13.sp, color = FlatGray)
        if (state != null) {
            Text(state.first, fontSize = 12.sp, color = state.second,
                modifier = Modifier.padding(end = 8.dp))
        }
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
