package com.aurora.toolbox.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
import java.net.URI

data class Tool(val id:String,val icon:String,val title:String,val desc:String)
private val tools=listOf(
 Tool("proxy","⇄","代理中心","导入节点、检查格式与打开 VPN 设置"),
 Tool("system","⌁","系统检查","查看设备、系统、内存与存储信息"),
 Tool("ocr","文","文字提取","从相册图片提取中文、日文和英文"),
 Tool("qr","▦","二维码识别","从相册图片读取二维码内容")
)
private val bg=Color(0xFF07111F);private val panel=Color(0xFF0D1B2D);private val cyan=Color(0xFF4CE4D0);private val muted=Color(0xFF8296AA)

class MainActivity:ComponentActivity(){override fun onCreate(s:Bundle?){super.onCreate(s);setContent{App(this)}}}
@Composable fun App(a:MainActivity){var selected by remember{mutableStateOf<Tool?>(null)};MaterialTheme(colorScheme=darkColorScheme(primary=cyan,background=bg,surface=panel)){Surface(Modifier.fillMaxSize()){if(selected==null)Home{selected=it}else Page(a,selected!!){selected=null}}}}
@Composable fun Home(open:(Tool)->Unit){Column(Modifier.fillMaxSize().background(bg).padding(20.dp)){Text("AURORA MOBILE · CORE",color=cyan);Text("极光工作箱",style=MaterialTheme.typography.headlineLarge,color=Color.White);Text("轻量手机版 · 仅保留四项核心能力",color=muted);Spacer(Modifier.height(24.dp));tools.forEach{t->Card(Modifier.fillMaxWidth().padding(vertical=7.dp).clickable{open(t)},colors=CardDefaults.cardColors(containerColor=panel)){Row(Modifier.padding(20.dp)){Text(t.icon,color=cyan,style=MaterialTheme.typography.headlineSmall);Spacer(Modifier.width(18.dp));Column{Text(t.title,color=Color.White,style=MaterialTheme.typography.titleMedium);Text(t.desc,color=muted)}}}}}}
@Composable fun Page(a:MainActivity,t:Tool,back:()->Unit){var input by remember{mutableStateOf("")};var output by remember{mutableStateOf("")};val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri:Uri?->if(uri!=null){val image=InputImage.fromFilePath(a,uri);if(t.id=="ocr")TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()).process(image).addOnSuccessListener{output=it.text.ifBlank{"未识别到文字"}}.addOnFailureListener{output="识别失败：${it.message}"}else BarcodeScanning.getClient().process(image).addOnSuccessListener{output=it.mapNotNull{x->x.rawValue}.joinToString("\n").ifBlank{"未识别到二维码"}}.addOnFailureListener{output="识别失败：${it.message}"}}};Column(Modifier.fillMaxSize().background(bg).padding(20.dp)){TextButton(onClick=back){Text("← 返回")};Text("${t.icon}  ${t.title}",style=MaterialTheme.typography.headlineMedium,color=Color.White);Text(t.desc,color=muted);Spacer(Modifier.height(24.dp));when(t.id){"proxy"->{OutlinedTextField(input,{input=it},Modifier.fillMaxWidth(),label={Text("代理节点链接")},minLines=3);Spacer(Modifier.height(12.dp));Button(onClick={output=runCatching{val u=URI(input.trim());if(u.scheme !in listOf("http","https","socks5","vmess","vless","trojan"))error("不支持的协议");"节点格式有效：${u.scheme}"}.getOrElse{"格式无效：${it.message}"}},Modifier.fillMaxWidth()){Text("检查节点")};OutlinedButton(onClick={a.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))},Modifier.fillMaxWidth()){Text("打开 Android VPN 设置")}};"system"->{val rt=Runtime.getRuntime();output="设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nAndroid：${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\nCPU 架构：${android.os.Build.SUPPORTED_ABIS.joinToString()}\n应用可用内存：${(rt.maxMemory()-rt.totalMemory()+rt.freeMemory())/1048576} MB";Button(onClick={output=output},Modifier.fillMaxWidth()){Text("刷新检查")}};else->Button(onClick={picker.launch("image/*")},Modifier.fillMaxWidth()){Text(if(t.id=="ocr")"选择图片提取文字" else "选择二维码图片")}};if(output.isNotBlank()){Spacer(Modifier.height(20.dp));Card(colors=CardDefaults.cardColors(containerColor=panel)){Text(output,Modifier.fillMaxWidth().padding(16.dp),color=Color.White)}}}}
