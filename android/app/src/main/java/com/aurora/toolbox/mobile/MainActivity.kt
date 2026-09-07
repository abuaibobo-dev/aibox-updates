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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

data class Tool(val id:String,val icon:String,val title:String,val desc:String)
private val tools=listOf(
    Tool("proxy","⇄","代理中心","SOCKS5 全局 VPN、扫码导入、账号认证与远程 DNS"),
    Tool("system","⌁","系统检查","查看设备、系统、内存与存储信息"),
    Tool("ocr","文","文字提取","从相册图片提取中文、日文和英文"),
    Tool("qr","▦","二维码识别","从相册图片读取二维码内容")
)
private val bg=Color(0xFF07111F); private val panel=Color(0xFF0D1B2D)
private val cyan=Color(0xFF4CE4D0); private val muted=Color(0xFF8296AA)

class MainActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{App(this)}}
}

@Composable fun App(activity:MainActivity){
    var selected by remember{mutableStateOf<Tool?>(null)}
    MaterialTheme(colorScheme=darkColorScheme(primary=cyan,background=bg,surface=panel)){
        Surface(Modifier.fillMaxSize()){if(selected==null)Home{selected=it}else ToolPage(activity,selected!!){selected=null}}
    }
}

@Composable fun Home(open:(Tool)->Unit){
    Column(Modifier.fillMaxSize().background(bg).padding(20.dp)){
        Text("AURORA MOBILE · CORE",color=cyan)
        Text("极光工作箱",style=MaterialTheme.typography.headlineLarge,color=Color.White)
        Text("轻量手机版 · 四项核心能力",color=muted); Spacer(Modifier.height(24.dp))
        tools.forEach{tool->Card(Modifier.fillMaxWidth().padding(vertical=7.dp).clickable{open(tool)},colors=CardDefaults.cardColors(containerColor=panel)){
            Row(Modifier.padding(20.dp)){Text(tool.icon,color=cyan,style=MaterialTheme.typography.headlineSmall);Spacer(Modifier.width(18.dp));Column{Text(tool.title,color=Color.White,style=MaterialTheme.typography.titleMedium);Text(tool.desc,color=muted)}}
        }}
    }
}

@Composable fun ToolPage(activity:MainActivity,tool:Tool,back:()->Unit){
    val prefs=activity.getSharedPreferences(AuroraVpnService.PREFS,Context.MODE_PRIVATE)
    var input by remember{mutableStateOf(prefs.getString(AuroraVpnService.KEY_NODE,"").orEmpty())}
    var output by remember{mutableStateOf("")}
    fun startVpn(){activity.startForegroundService(Intent(activity,AuroraVpnService::class.java).setAction(AuroraVpnService.ACTION_CONNECT));output="正在建立全局 VPN…"}
    val vpnPermission=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){if(VpnService.prepare(activity)==null)startVpn()else output="未授予 VPN 权限"}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri:Uri?->if(uri!=null){
        val image=InputImage.fromFilePath(activity,uri)
        if(tool.id=="ocr")TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()).process(image).addOnSuccessListener{output=it.text.ifBlank{"未识别到文字"}}.addOnFailureListener{output="识别失败：${it.message}"}
        else BarcodeScanning.getClient().process(image).addOnSuccessListener{val value=it.firstOrNull()?.rawValue.orEmpty();if(tool.id=="proxy"&&value.isNotBlank())input=value;output=value.ifBlank{"未识别到二维码"}}.addOnFailureListener{output="识别失败：${it.message}"}
    }}
    Column(Modifier.fillMaxSize().background(bg).padding(20.dp)){
        TextButton(onClick=back){Text("← 返回")};Text("${tool.icon}  ${tool.title}",style=MaterialTheme.typography.headlineMedium,color=Color.White);Text(tool.desc,color=muted);Spacer(Modifier.height(24.dp))
        when(tool.id){
            "proxy"->{
                OutlinedTextField(input,{input=it},Modifier.fillMaxWidth(),label={Text("SOCKS5 节点／分享链接")},minLines=4);Spacer(Modifier.height(12.dp))
                Button(onClick={output=runCatching{val n=ProxyConfig.parse(input);"节点有效\n服务器：${n.host}:${n.port}\n认证：${if(n.username.isBlank())"无" else n.username}"}.getOrElse{"格式无效：${it.message}"}},Modifier.fillMaxWidth()){Text("检查节点")}
                Button(onClick={runCatching{ProxyConfig.parse(input)}.onSuccess{prefs.edit().putString(AuroraVpnService.KEY_NODE,input.trim()).apply();val permission=VpnService.prepare(activity);if(permission==null)startVpn()else vpnPermission.launch(permission)}.onFailure{output="无法连接：${it.message}"}},Modifier.fillMaxWidth()){Text("连接 · 接管全局网络")}
                OutlinedButton(onClick={activity.startService(Intent(activity,AuroraVpnService::class.java).setAction(AuroraVpnService.ACTION_DISCONNECT));output="正在断开代理…"},Modifier.fillMaxWidth()){Text("断开 VPN")}
                OutlinedButton(onClick={picker.launch("image/*")},Modifier.fillMaxWidth()){Text("扫描二维码导入")}
                OutlinedButton(onClick={prefs.edit().putString(AuroraVpnService.KEY_NODE,input.trim()).apply();output="节点已保存在应用私有目录"},Modifier.fillMaxWidth()){Text("保存节点")}
                OutlinedButton(onClick={input=prefs.getString(AuroraVpnService.KEY_NODE,"").orEmpty();output=prefs.getString(AuroraVpnService.KEY_STATUS,"尚未连接").orEmpty()},Modifier.fillMaxWidth()){Text("刷新状态")}
                Text("当前真实全局隧道支持 SOCKS5（TCP、UDP、账号密码、远程 DNS）。其他协议不会伪装连接。",color=muted)
            }
            "system"->{Button(onClick={val rt=Runtime.getRuntime();output="设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nAndroid：${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\nCPU 架构：${android.os.Build.SUPPORTED_ABIS.joinToString()}\n应用可用内存：${(rt.maxMemory()-rt.totalMemory()+rt.freeMemory())/1048576} MB"},Modifier.fillMaxWidth()){Text("刷新检查")}}
            else->Button(onClick={picker.launch("image/*")},Modifier.fillMaxWidth()){Text(if(tool.id=="ocr")"选择图片提取文字" else "选择二维码图片")}
        }
        if(output.isNotBlank()){Spacer(Modifier.height(20.dp));Card(colors=CardDefaults.cardColors(containerColor=panel)){Text(output,Modifier.fillMaxWidth().padding(16.dp),color=Color.White)}}
    }
}
