package dev.abuaibobo.jpstock

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

const val PREF_NAME = "jpstock_prefs"
const val PREF_BASE = "analysis_base"

fun loadSavedBase(context: Context): String =
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .getString(PREF_BASE, DEFAULT_BASE) ?: DEFAULT_BASE

private fun saveBase(context: Context, url: String) {
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .edit().putString(PREF_BASE, url).apply()
}

@Composable
fun SettingsScreen(onSaved: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var base by remember { mutableStateOf(loadSavedBase(context)) }
    var status by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState())) {
        Text("设置", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("实时行情/解析/AI解读依赖本地后端 (server.py)", fontSize = 12.sp, color = FlatGray)
        Spacer(Modifier.height(14.dp))

        Card(colors = CardDefaults.cardColors(containerColor = CardDark)) {
            Column(Modifier.padding(12.dp)) {
                Text("后端地址", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = base,
                    onValueChange = { base = it },
                    label = { Text("http://ip:port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        Api.setAnalysisBase(base)
                        saveBase(context, base)
                        saved = true
                        onSaved()
                    }) { Text("保存") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            status = "测试中…"
                            val ok = Api.testConnection()
                            status = if (ok) "✅ 后端连接正常" else "❌ 无法连接后端"
                        }
                    }) { Text("测试连接") }
                }
                if (status != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(status!!, fontSize = 13.sp, color = if (status!!.startsWith("✅")) UpRed else FlatGray)
                }
                if (saved) {
                    Spacer(Modifier.height(4.dp))
                    Text("已保存", fontSize = 12.sp, color = AccentGold)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        Card(colors = CardDefaults.cardColors(containerColor = CardDark)) {
            Column(Modifier.padding(12.dp)) {
                Text("后端一键启动 (在本设备/服务器上)", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text("App 无法直接启动 Python 后端，需在终端运行:", fontSize = 12.sp, color = FlatGray)
                Spacer(Modifier.height(8.dp))
                val cmd = "cd /workspace/jp-stock-app/engine && ./start_backend.sh"
                Surface(color = BgDark, shape = MaterialTheme.shapes.small) {
                    Text(cmd, Modifier.padding(10.dp), fontSize = 12.sp,
                        color = TextPrimary)
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(cmd))
                }) { Text("复制启动命令") }
            }
        }
        Spacer(Modifier.height(12.dp))

        Card(colors = CardDefaults.cardColors(containerColor = CardDark)) {
            Column(Modifier.padding(12.dp)) {
                Text("关于", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text("日股分析 · 每日推荐 (价值+质量多因子)\n" +
                    "股票池: 东证 Prime\n" +
                    "行情: Yahoo(延迟) · 估值: irbank · AI: DeepSeek\n" +
                    "数据与策略仅供研究参考，非投资建议。",
                    fontSize = 12.sp, color = TextSecondary)
            }
        }
    }
}
