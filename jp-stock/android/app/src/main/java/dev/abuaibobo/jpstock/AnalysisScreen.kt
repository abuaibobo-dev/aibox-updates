package dev.abuaibobo.jpstock

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Tab 3: type a 4-digit JP code -> real-time parse + AI note via local backend. */
@Composable
fun AnalysisScreen(initialCode: String? = null) {
    var code by remember { mutableStateOf(initialCode ?: "") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<AnalysisData?>(null) }
    val scope = rememberCoroutineScope()

    fun run(codeStr: String) {
        scope.launch {
            loading = true; error = null; result = null
            try { result = Api.fetchAnalysis(codeStr) }
            catch (e: Exception) { error = e.message ?: "解析失败" }
            loading = false
        }
    }

    LaunchedEffect(initialCode) {
        val c = initialCode
        if (!c.isNullOrBlank()) run(c)
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("个股解析", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("输入4位代码，实时行情 + 技术指标 + AI 建议", fontSize = 12.sp, color = FlatGray)
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = code,
                onValueChange = { v -> if (v.length <= 4 && v.all { it.isDigit() }) code = v },
                label = { Text("代码 (如 7203)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { if (code.length == 4) run(code) },
                enabled = code.length == 4 && !loading,
            ) { Text("解析") }
        }
        Spacer(Modifier.height(4.dp))
        Text("需要本地后端已启动 (见 server.py)", fontSize = 10.sp, color = FlatGray)
        Spacer(Modifier.height(10.dp))

        when {
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            error != null -> ErrorBox(error!!) { if (code.length == 4) run(code) }
            result != null -> AnalysisResultView(result!!)
        }
    }
}

@Composable
fun AnalysisResultView(a: AnalysisData) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("${a.code}  ${a.name}", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text("${a.industry} · 现价 ¥${fmt(a.price)} · 综合分 ${fmt1(a.score)}",
            fontSize = 12.sp, color = FlatGray)
        Spacer(Modifier.height(6.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            a.pbrPct?.let { PercentChip("PB历史分位", it, lowerBetter = true) }
            a.perPct?.let { PercentChip("PE历史分位", it, lowerBetter = true) }
        }
        Spacer(Modifier.height(8.dp))

        if (a.candles.isNotEmpty()) {
            Text("近3个月日K · 红涨蓝跌 · 蓝/红虚线=支撑/压力 · 长按可保存图片",
                fontSize = 12.sp, color = FlatGray)
            val kl = a.candles.map { KLine(it.ts, it.open, it.high, it.low, it.close, 0) }
            val (sup, res) = findKeyLevels(
                a.candles.map { it.high }, a.candles.map { it.low }, a.price)
            CapturableChart(bars = kl, fileName = a.code,
                modifier = Modifier.fillMaxWidth().height(240.dp),
                supports = sup, resistances = res)
            if (sup.isNotEmpty() || res.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("支撑: ${sup.joinToString { "¥" + fmt(it) }}"
                    + if (res.isNotEmpty()) "    压力: ${res.joinToString { "¥" + fmt(it) }}" else "",
                    fontSize = 13.sp, color = FlatGray)
            }
            Spacer(Modifier.height(8.dp))
        }

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardDark)) {
            Column(Modifier.padding(12.dp)) {
                Text("技术指标", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                a.rsi?.let { MetricRow("RSI(14)", fmt1(it),
                    when { it >= 70 -> "超买" to UpRed; it <= 30 -> "超卖" to DownBlue; else -> "中性" to FlatGray }) }
                a.macd?.let { MetricRow("MACD", fmt2(it),
                    if (it >= (a.macdSignal ?: 0.0)) "多头" to UpRed else "空头" to DownBlue) }
                a.macdHist?.let { MetricRow("MACD柱", fmt2(it), null) }
                a.bbPct?.let { MetricRow("布林位置", "${it.toInt()}%",
                    when { it >= 90 -> "近上轨" to UpRed; it <= 10 -> "近下轨" to DownBlue; else -> "中轨" to FlatGray }) }
                a.ma20?.let { MetricRow("MA20", fmt(it), null) }
                a.ma60?.let { MetricRow("MA60", fmt(it), null) }
                a.ma200?.let { MetricRow("MA200", fmt(it), null) }
                a.m6?.let { MetricRow("6月动量", pct(it), null) }
                a.m12?.let { MetricRow("1年动量", pct(it), null) }
            }
        }
        Spacer(Modifier.height(8.dp))

        if (a.aiReason.isNotBlank()) {
            Card(Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AccentBlue.copy(alpha = 0.10f))) {
                Column(Modifier.padding(12.dp)) {
                    Text("AI 建议", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = AccentBlue)
                    Spacer(Modifier.height(4.dp))
                    Text(a.aiReason, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Text("免责声明: 量化信号与AI分析仅供参考，非投资建议", fontSize = 11.sp, color = FlatGray)
    }
}
