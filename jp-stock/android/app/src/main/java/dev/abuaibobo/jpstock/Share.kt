package dev.abuaibobo.jpstock

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Builds a shareable text for one pick (defaults to Japanese blurb). */
fun buildShareText(p: Pick): String {
    if (p.pitch.isNotBlank()) {
        return p.pitch  // client-facing Chinese buy case
    }
    val ja = if (p.reasonJa.isNotBlank()) p.reasonJa else {
        "【日株ピック】${p.name}(${p.code}) ¥${fmt(p.price)} スコア${fmt1(p.score)}\n" +
            p.reason + "\n※投資助言ではありません。"
    }
    return ja + "\n#日株 #日本株"
}

/** Japan social platforms we target: display name -> Android package. */
val JAPAN_SOCIAL = listOf(
    "X(Twitter)" to "com.twitter.android",
    "LINE" to "jp.naver.line.android",
    "Facebook" to "com.facebook.katana",
    "note" to "mu.note",
    "はてな" to "com.hatenanews",
)

/**
 * Share text. Resolves each Japan platform; launches the first installed one
 * directly, else falls back to the system share sheet.
 */
fun shareToJapanPlatforms(context: Context, text: String) {
    val pm: PackageManager = context.packageManager
    val base = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }

    // find first installed Japan platform
    for ((name, pkg) in JAPAN_SOCIAL) {
        val direct = Intent(base).apply { `package` = pkg }
        if (direct.resolveActivity(pm) != null) {
            val chooser = Intent.createChooser(direct, "分享到 $name")
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(chooser)
            return
        }
    }
    // none installed -> generic system sheet
    val fallback = Intent.createChooser(base, "分享到…")
        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    context.startActivity(fallback)
}

@Composable
fun ShareRow(p: Pick, context: Context) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("分享 ", fontSize = 13.sp, color = FlatGray)
        TextButton(onClick = { shareToJapanPlatforms(context, buildShareText(p)) }) {
            Text("X · LINE · Facebook", fontSize = 13.sp)
        }
        if (p.pitch.isNotBlank()) {
            val clipboard = LocalClipboardManager.current
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(buildShareText(p)))
            }) {
                Text("复制购买理由", fontSize = 13.sp)
            }
        }
    }
}
