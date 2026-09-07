package com.aurora.toolbox.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.random.Random

data class Tool(val icon:String,val title:String,val desc:String)
private val tools=listOf(
 Tool("⌁","IP 与网络","查看网络与公网地址"),Tool("◫","图片工具","压缩、转换与放大"),
 Tool("▦","二维码","识别与生成二维码"),Tool("GIF","GIF 制作","多图合成动画"),
 Tool("Aa","名字生成","英文名与日本名字"),Tool("☎","资料生成","测试电话与示例地址"),
 Tool("文","日语专区","常用语、文化与礼仪"),Tool("¥","日本股票","行情与基础知识"),
 Tool("✎","加密记事本","本机加密保存"),Tool("123","字数统计","字符、单词与行数"),
 Tool("6","2FA 验证码","本地 TOTP 计算"),Tool("◎","内置浏览器","常用网站与翻译"),
 Tool("{}","开发工具","JSON、Base64 与哈希"),Tool("⚙","手机设置","权限、缓存与更新")
)
class MainActivity:ComponentActivity(){override fun onCreate(b:Bundle?){super.onCreate(b);setContent{AuroraApp()}}}
@Composable fun AuroraApp(){var selected by remember{mutableStateOf<Tool?>(null)};MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFF4CE4D0),background=Color(0xFF07111F),surface=Color(0xFF0D1B2D))){Surface(Modifier.fillMaxSize()){if(selected==null)Home{selected=it}else ToolPage(selected!!){selected=null}}}}
@Composable fun Home(open:(Tool)->Unit){Column(Modifier.fillMaxSize().background(Color(0xFF07111F)).padding(18.dp)){Text("AURORA MOBILE",color=Color(0xFF4CE4D0));Text("极光工作箱",style=MaterialTheme.typography.headlineLarge,color=Color.White);Text("移动端工具矩阵 · 本地优先",color=Color(0xFF8296AA));Spacer(Modifier.height(18.dp));LazyVerticalGrid(GridCells.Fixed(2),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){items(tools){t->Card(Modifier.fillMaxWidth().height(142.dp).clickable{open(t)},colors=CardDefaults.cardColors(containerColor=Color(0xFF0D1B2D))){Column(Modifier.padding(16.dp)){Text(t.icon,color=Color(0xFF4CE4D0),style=MaterialTheme.typography.headlineSmall);Spacer(Modifier.height(12.dp));Text(t.title,color=Color.White);Text(t.desc,color=Color(0xFF8296AA),style=MaterialTheme.typography.bodySmall)}}}}}}
@Composable fun ToolPage(tool:Tool,back:()->Unit){var text by remember{mutableStateOf("")};Column(Modifier.fillMaxSize().background(Color(0xFF07111F)).padding(18.dp)){TextButton(onClick=back){Text("← 返回")};Text("${tool.icon}  ${tool.title}",style=MaterialTheme.typography.headlineMedium,color=Color.White);Text(tool.desc,color=Color(0xFF8296AA));Spacer(Modifier.height(20.dp));OutlinedTextField(text,{text=it},Modifier.fillMaxWidth(),label={Text("输入内容")});Spacer(Modifier.height(12.dp));Button(onClick={text=when(tool.title){"字数统计"->"字符 ${text.length} · 行数 ${if(text.isEmpty())0 else text.lines().size}";"名字生成"->listOf("Haruto Sato / 佐藤 陽翔","Emma Wilson","Aoi Tanaka / 田中 葵").random();"资料生成"->"+81 ${Random.nextInt(10,99)}-${Random.nextInt(1000,9999)}-${Random.nextInt(1000,9999)}";else->"${tool.title} 移动端模块已就绪"}}){Text("执行")};Spacer(Modifier.height(16.dp));Text(text,color=Color.White)}}
