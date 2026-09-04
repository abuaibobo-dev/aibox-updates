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
fun MarketScreen() {
    var feed by remember { mutableStateOf<MarketFeed?>(null) }
    var liveIndices by remember { mutableStateOf<List<IndexQuote>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    fun load() {
        scope.launch {
            loading = true; error = null
            try { feed = Api.fetchMarket() }
            catch (e: Exception) { error = e.message ?: "加载失败" }
            // best-effort live indices from local backend (fast fail if absent)
            liveIndices = try { Api.fetchLiveIndices() } catch (_: Exception) { null }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    when {
        loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        error != null -> ErrorBox(error!!) { load() }
        feed != null -> LazyColumn(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("日本股市 大盘概览", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                val live = liveIndices != null
                Text(if (live) "指数实时 · 板块日级 (${feed!!.date})"
                    else "数据日期: ${feed!!.date} (指数为日级)",
                    fontSize = 12.sp, color = FlatGray)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val shown = liveIndices ?: feed!!.indices
                    shown.forEach { IndexCard(it, Modifier.weight(1f)) }
                }
            }
            item {
                Text("行业涨跌 (33个)", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
            }
            items(feed!!.sectors) { s ->
                SectorRow(s)
            }
        }
    }
}

@Composable
fun IndexCard(ix: IndexQuote, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(ix.name, fontSize = 13.sp, color = FlatGray)
            Text(fmt(ix.last), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            ix.chgDay?.let {
                Text(pct(it / 100), fontSize = 14.sp,
                    color = if (it >= 0) UpRed else DownBlue,
                    fontWeight = FontWeight.SemiBold)
            }
            ix.chg5d?.let {
                Text("5日 ${pct(it / 100)}", fontSize = 11.sp, color = FlatGray)
            }
        }
    }
}

@Composable
fun SectorRow(s: Sector) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(s.name, Modifier.weight(1f), fontSize = 14.sp)
        Text("${s.count}只", fontSize = 12.sp, color = FlatGray,
            modifier = Modifier.padding(end = 12.dp))
        Text(pct(s.chgDay / 100), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = if (s.chgDay >= 0) UpRed else DownBlue)
    }
}
