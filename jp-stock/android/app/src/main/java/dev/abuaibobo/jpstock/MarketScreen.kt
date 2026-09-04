package dev.abuaibobo.jpstock

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
fun MarketScreen(onOpenCode: (String) -> Unit = {}) {
    var view by remember { mutableStateOf(0) }  // 0 sectors, 1 stocks
    var selInd by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = view == 0, onClick = { view = 0 },
                label = { Text("板块涨跌") })
            FilterChip(selected = view == 1, onClick = { view = 1 },
                label = { Text("全部股票") })
        }
        when (view) {
            0 -> SectorView(onOpenIndustry = { ind ->
                selInd = ind; view = 1
            })
            1 -> StockView(initialInd = selInd, onOpenCode = onOpenCode)
        }
    }
}

@Composable
fun SectorView(onOpenIndustry: (String) -> Unit = {}) {
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
                Text("行业涨跌 · 点击板块查看成分股", fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
            }
            items(feed!!.sectors) { s -> SectorRow(s, onClick = { onOpenIndustry(s.name) }) }
        }
    }
}

@Composable
fun IndexCard(ix: IndexQuote, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = CardDark)) {
        Column(Modifier.padding(12.dp)) {
            Text(ix.name, fontSize = 13.sp, color = FlatGray)
            Text(fmt(ix.last), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            ix.chgDay?.let {
                Text(pct(it / 100), fontSize = 14.sp,
                    color = if (it >= 0) UpRed else DownBlue,
                    fontWeight = FontWeight.SemiBold)
            }
            ix.chg5d?.let { Text("5日 ${pct(it / 100)}", fontSize = 11.sp, color = FlatGray) }
        }
    }
}

@Composable
@Composable
fun SectorRow(s: Sector, onClick: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
            Text(s.name, Modifier.weight(1f), fontSize = 14.sp)
            Text("${s.count}只", fontSize = 12.sp, color = FlatGray,
                modifier = Modifier.padding(end = 12.dp))
            Text(pct(s.chgDay / 100), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = if (s.chgDay >= 0) UpRed else DownBlue)
            Text(" ›", fontSize = 16.sp, color = FlatGray,
                modifier = Modifier.padding(start = 6.dp))
        }
        HorizontalDivider(color = BorderDark)
    }
}

/** Browse all Prime stocks: industry filter chips + searchable, tappable list. */
@Composable
fun StockView(onOpenCode: (String) -> Unit, initialInd: String = "") {
    var feed by remember { mutableStateOf<StockFeed?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var selInd by remember { mutableStateOf(initialInd) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(initialInd) { selInd = initialInd }
    val scope = rememberCoroutineScope()
    fun load() {
        scope.launch {
            loading = true; error = null
            try { feed = Api.fetchStocks() }
            catch (e: Exception) { error = e.message ?: "加载失败(需启动后端)" }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text("全部股票 (${feed?.total ?: 0}只)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索代码/名称…", fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            error != null -> ErrorBox(error!!) { load() }
            feed != null -> {
                LazyRow(Modifier.fillMaxWidth()) {
                    item { IndustryChip("全部", selInd == "", { selInd = "" }) }
                    items(feed!!.industries) { c ->
                        IndustryChip(c.name, selInd == c.name, { selInd = c.name })
                    }
                }
                val shown = feed!!.stocks.filter {
                    (selInd.isEmpty() || it.industry == selInd) &&
                        (query.isBlank() || it.code.contains(query) ||
                            it.name.contains(query, ignoreCase = true))
                }
                Spacer(Modifier.height(4.dp))
                LazyColumn(Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(shown) { q -> StockRow(q) { onOpenCode(q.code) } }
                }
            }
        }
    }
}

@Composable
fun IndustryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, fontSize = 13.sp) })
}

@Composable
fun StockRow(q: StockQuote, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${q.code} ${q.name}", fontSize = 14.sp)
            Text(q.industry, fontSize = 11.sp, color = FlatGray)
        }
        Text("¥${fmt(q.price)}", fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(end = 12.dp))
        q.chgPct?.let {
            Text(pct(it / 100), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = if (it >= 0) UpRed else DownBlue)
        }
    }
    HorizontalDivider(color = BorderDark)
}
