package com.evo.merger

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
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
import java.io.File
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private var inputs: List<Pair<String, Uri>> = emptyList()
    private lateinit var status: TextView
    private lateinit var pick: Button
    private lateinit var start: Button
    private lateinit var saveRaw: Button
    private lateinit var merged: File
    private var mergedReady = false

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            inputs = uris.map { nameOf(it) to it }.sortedBy { natural(it.first) }
            status.text = "Merge order:\n" +
                inputs.mapIndexed { i, p -> "${i + 1}. ${p.first}" }.joinToString("\n")
            start.isEnabled = true
        }

    private val saveMp4 =
        registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
            if (uri != null) convert(uri)
        }

    private val saveEvo =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) {
                busy(true)
                thread {
                    try {
                        contentResolver.openOutputStream(uri)!!.use { o ->
                            merged.inputStream().use { it.copyTo(o, 1 shl 20) }
                        }
                        say("Merged EVO saved ✔")
                    } catch (t: Throwable) {
                        say("Error: ${t.message}")
                    } finally {
                        runOnUiThread { busy(false) }
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        merged = File(externalCacheDir ?: cacheDir, "merged.evo")
        val pad = (24 * resources.displayMetrics.density).toInt()

        pick = Button(this).apply {
            text = "Select EVO files"
            setOnClickListener { pickFiles.launch(arrayOf("*/*")) }
        }
        start = Button(this).apply {
            text = "Merge & convert to MP4"
            isEnabled = false
            setOnClickListener {
                saveMp4.launch(inputs.first().first.substringBeforeLast('.') + ".mp4")
            }
        }
        saveRaw = Button(this).apply {
            text = "Save merged EVO"
            isEnabled = false
            setOnClickListener {
                saveEvo.launch(inputs.first().first.substringBeforeLast('.') + "_merged.evo")
            }
        }
        status = TextView(this).apply { text = "No files selected" }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(pick)
            addView(start)
            addView(saveRaw)
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
        saveRaw.isEnabled = !on && mergedReady
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (on) window.addFlags(flag) else window.clearFlags(flag)
    }

    private fun convert(out: Uri) {
        busy(true)
        thread {
            val mp4 = File(merged.parentFile, "out.mp4")
            try {
                mp4.delete(); merged.delete(); mergedReady = false

                say("Merging ${inputs.size} files…")
                merged.outputStream().buffered(1 shl 20).use { o ->
                    for ((_, uri) in inputs) {
                        contentResolver.openInputStream(uri)!!.use { it.copyTo(o, 1 shl 20) }
                    }
                }
                mergedReady = true

                say("Converting to MP4…")
                val note = remux(merged.absolutePath, mp4.absolutePath)

                say("Saving…")
                contentResolver.openOutputStream(out)!!.use { o ->
                    mp4.inputStream().use { it.copyTo(o, 1 shl 20) }
                }
                say("Done ✔  ${mp4.length() / 1048576} MB$note")
                merged.delete(); mergedReady = false
            } catch (t: Throwable) {
                say(
                    if (mergedReady)
                        "Merge OK, but MP4 conversion failed:\n${t.message}\n\nTap “Save merged EVO” to keep the merged file."
                    else "Error: ${t.message}"
                )
            } finally {
                mp4.delete()
                runOnUiThread { busy(false) }
            }
        }
    }

    // Copies supported tracks into an MP4 without re-encoding (Android built-in codecs only).
    private fun remux(src: String, dst: String): String {
        val ex = MediaExtractor()
        var mux: MediaMuxer? = null
        try {
            ex.setDataSource(src)
            mux = MediaMuxer(dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val map = HashMap<Int, Int>()
            val skipped = ArrayList<String>()
            var hasVideo = false

            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                val ok = mime == "video/avc" || mime == "video/hevc" ||
                    mime == "video/mp4v-es" || mime == "audio/mp4a-latm"
                if (ok) {
                    ex.selectTrack(i)
                    map[i] = mux.addTrack(f)
                    if (mime.startsWith("video")) hasVideo = true
                } else skipped.add(mime)
            }
            if (!hasVideo) {
                throw IllegalStateException(
                    "No supported video track (found: " +
                        (if (skipped.isEmpty()) "none" else skipped.joinToString()) + ")"
                )
            }

            mux.start()
            val buf = ByteBuffer.allocate(4 shl 20)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val key = (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                info.set(0, size, ex.sampleTime, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                mux.writeSampleData(map[ex.sampleTrackIndex]!!, buf, info)
                ex.advance()
            }
            mux.stop()
            return if (skipped.isEmpty()) "" else "\n\nSkipped tracks: ${skipped.joinToString()}"
        } finally {
            ex.release()
            try { mux?.release() } catch (_: Exception) {}
        }
    }
}
