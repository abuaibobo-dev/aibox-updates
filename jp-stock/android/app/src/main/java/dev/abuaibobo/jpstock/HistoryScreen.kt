package dev.abuaibobo.jpstock

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

@Composable
fun HistoryScreen() {
    var feed by remember { mutableStateOf<HistoryFeed?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    fun load() {
        scope.launch {
            loading = true; error = null
            try { feed = Api.fetchHistory() }
            catch (e: Exception) { error = e.message ?: "加载失败" }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    when {
        loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        error != null -> ErrorBox(error!!, load)
        feed != null -> LazyColumn(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("推荐跟踪", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("回看每日推荐后的市场表现 (截至 ${feed!!.updated.take(10)})",
                    fontSize = 12.sp, color = Color.Gray)
            }
            items(feed!!.days) { day ->
                HistoryDayCard(day)
            }
        }
    }
}

@Composable
fun HistoryDayCard(day: HistoryDay) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(day.date, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            day.picks.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text("${p.code} ${p.name}", Modifier.weight(1f), fontSize = 13.sp)
                    Text("¥${fmt(p.price)}", fontSize = 13.sp, color = Color.Gray,
                        modifier = Modifier.padding(end = 10.dp))
                    p.retPct?.let { r ->
                        Text(if (r >= 0) "▲ +${fmt1(r)}%" else "▼ ${fmt1(r)}%",
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = if (r >= 0) Color(0xFFE53935) else Color(0xFF1E88E5))
                    } ?: Text("—", fontSize = 13.sp, color = Color.Gray)
                }
            }
        }
    }
}
