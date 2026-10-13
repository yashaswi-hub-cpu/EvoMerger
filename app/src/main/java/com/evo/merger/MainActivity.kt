package com.evo.merger

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private var inputs: List<Pair<String, Uri>> = emptyList()
    private lateinit var status: TextView
    private lateinit var pick: Button
    private lateinit var start: Button

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            inputs = uris.map { nameOf(it) to it }.sortedBy { natural(it.first) }
            status.text = "Merge order:\n" +
                inputs.mapIndexed { i, p -> "${i + 1}. ${p.first}" }.joinToString("\n")
            start.isEnabled = true
        }

    private val saveFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
            if (uri != null) convert(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()

        pick = Button(this).apply {
            text = "Select EVO files"
            setOnClickListener { pickFiles.launch(arrayOf("*/*")) }
        }
        start = Button(this).apply {
            text = "Merge & convert to MP4"
            isEnabled = false
            setOnClickListener {
                saveFile.launch(inputs.first().first.substringBeforeLast('.') + ".mp4")
            }
        }
        status = TextView(this).apply { text = "No files selected" }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(pick)
            addView(start)
            addView(ScrollView(this@MainActivity).apply { addView(status) })
        })
    }

    private fun natural(s: String) =
        s.lowercase().replace(Regex("\\d+")) { it.value.padStart(12, '0') }

    private fun nameOf(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: uri.lastPathSegment ?: "file"

    private fun say(t: String) = runOnUiThread { status.text = t }

    private fun busy(on: Boolean) {
        pick.isEnabled = !on
        start.isEnabled = !on && inputs.isNotEmpty()
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (on) window.addFlags(flag) else window.clearFlags(flag)
    }

    private fun convert(out: Uri) {
        busy(true)
        thread {
            val dir = externalCacheDir ?: cacheDir
            val merged = File(dir, "merged.evo")
            val mp4 = File(dir, "out.mp4")
            try {
                merged.delete(); mp4.delete()

                say("Merging ${inputs.size} files…")
                merged.outputStream().buffered(1 shl 20).use { o ->
                    for ((_, uri) in inputs) {
                        contentResolver.openInputStream(uri)!!.use { it.copyTo(o, 1 shl 20) }
                    }
                }

                val codec = FFprobeKit.getMediaInformation(merged.absolutePath)
                    .mediaInformation?.streams?.firstOrNull { it.type == "video" }?.codec
                val video = if (codec == "h264") "-c:v copy" else "-c:v mpeg4 -q:v 3"

                FFmpegKitConfig.enableStatisticsCallback { s ->
                    say("Converting (${codec ?: "?"})… ${s.time.toLong() / 1000}s of video done")
                }
                val cmd = "-y -analyzeduration 100M -probesize 100M -i \"${merged.absolutePath}\" " +
                    "-map 0:v:0 -map 0:a:0? $video -c:a aac -b:a 192k -ac 2 " +
                    "-movflags +faststart \"${mp4.absolutePath}\""
                val session = FFmpegKit.execute(cmd)
                if (!ReturnCode.isSuccess(session.returnCode)) {
                    say("Failed:\n" + session.allLogsAsString.takeLast(800))
                    return@thread
                }

                say("Saving…")
                contentResolver.openOutputStream(out)!!.use { o ->
                    mp4.inputStream().use { it.copyTo(o, 1 shl 20) }
                }
                say("Done ✔  ${mp4.length() / 1048576} MB")
            } catch (t: Throwable) {
                say("Error: ${t.message}")
            } finally {
                merged.delete(); mp4.delete()
                runOnUiThread { busy(false) }
            }
        }
    }
}
