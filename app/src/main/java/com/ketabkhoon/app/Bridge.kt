package com.ketabkhoon.app

import android.content.ContentValues
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.FileProvider
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * Connects the web page (index.html) to the offline Persian voice.
 * Everything runs on the phone, no internet needed.
 */
class Bridge(
    private val ctx: MainActivity,
    private val web: WebView,
    private val exports: File
) {
    private val voices = setOf("amir", "ganji_adabi")
    private val exec = Executors.newSingleThreadExecutor()

    @Volatile
    private var cancelFlag = false
    private var tts: OfflineTts? = null
    private var loadedVoice: String? = null

    private fun js(code: String) {
        ctx.runOnUiThread { web.evaluateJavascript(code, null) }
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun copyAsset(assetPath: String, dest: File) {
        val children = ctx.assets.list(assetPath)
        if (children == null || children.isEmpty()) {
            dest.parentFile?.mkdirs()
            ctx.assets.open(assetPath).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
        } else {
            dest.mkdirs()
            for (child in children) {
                copyAsset("$assetPath/$child", File(dest, child))
            }
        }
    }

    /** Copies the voice files out of the app package once, then loads the engine. */
    private fun engine(voice: String): OfflineTts {
        require(voice in voices) { "unknown voice" }
        val current = tts
        if (current != null && loadedVoice == voice) return current

        current?.release()
        tts = null
        loadedVoice = null

        val root = File(ctx.filesDir, "tts")
        root.mkdirs()

        val espeakDir = File(root, "espeak-ng-data")
        val espeakMarker = File(root, "espeak.ok")
        if (!espeakMarker.exists()) {
            espeakDir.deleteRecursively()
            copyAsset("tts/espeak-ng-data", espeakDir)
            espeakMarker.writeText("1")
        }

        val voiceDir = File(root, voice)
        val voiceMarker = File(root, "$voice.ok")
        if (!voiceMarker.exists()) {
            voiceDir.deleteRecursively()
            copyAsset("tts/$voice", voiceDir)
            voiceMarker.writeText("1")
        }

        val vits = OfflineTtsVitsModelConfig(
            model = File(voiceDir, "model.onnx").absolutePath,
            tokens = File(voiceDir, "tokens.txt").absolutePath,
            dataDir = espeakDir.absolutePath
        )
        val modelConfig = OfflineTtsModelConfig(
            vits = vits,
            numThreads = 2,
            debug = false,
            provider = "cpu"
        )
        val created = OfflineTts(config = OfflineTtsConfig(model = modelConfig))
        tts = created
        loadedVoice = voice
        return created
    }

    private fun toPcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun wavHeader(sampleRate: Int, dataLen: Long): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray())
        b.putInt((36 + dataLen).toInt())
        b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray())
        b.putInt(16)
        b.putShort(1)
        b.putShort(1)
        b.putInt(sampleRate)
        b.putInt(sampleRate * 2)
        b.putShort(2)
        b.putShort(16)
        b.put("data".toByteArray())
        b.putInt(dataLen.toInt())
        return b.array()
    }

    /** Reads one sentence aloud: returns raw audio to the page. */
    @JavascriptInterface
    fun synth(id: Int, text: String, voice: String, speed: Double) {
        exec.execute {
            try {
                val e = engine(voice)
                val audio = e.generate(text, 0, speed.toFloat())
                val b64 = Base64.encodeToString(toPcm16(audio.samples), Base64.NO_WRAP)
                js("window.__ttsDone($id,'$b64',${audio.sampleRate})")
            } catch (t: Throwable) {
                js("window.__ttsFail($id,${JSONObject.quote(t.message ?: "error")})")
            }
        }
    }

    /** Builds one audio file (WAV) from many sentences. */
    @JavascriptInterface
    fun buildAudio(name: String, textsJson: String, voice: String, speed: Double) {
        cancelFlag = false
        exec.execute {
            val safe = safeName(name)
            val file = File(exports, safe)
            var stopped = false
            try {
                val texts = JSONArray(textsJson)
                val e = engine(voice)
                val sr = e.sampleRate()
                val gap = ByteArray((sr * 0.35).toInt() * 2)
                RandomAccessFile(file, "rw").use { raf ->
                    raf.setLength(0)
                    raf.write(ByteArray(44))
                    var bytes = 0L
                    for (i in 0 until texts.length()) {
                        if (cancelFlag) {
                            stopped = true
                            break
                        }
                        val audio = e.generate(texts.getString(i), 0, speed.toFloat())
                        val pcm = toPcm16(audio.samples)
                        raf.write(pcm)
                        raf.write(gap)
                        bytes += pcm.size + gap.size
                        js("window.__buildProgress(${i + 1},${texts.length()})")
                    }
                    if (!stopped) {
                        raf.seek(0)
                        raf.write(wavHeader(sr, bytes))
                    }
                }
                if (stopped) {
                    file.delete()
                    js("window.__buildFail('stopped')")
                } else {
                    js("window.__buildDone(${JSONObject.quote(safe)})")
                }
            } catch (t: Throwable) {
                file.delete()
                js("window.__buildFail(${JSONObject.quote(t.message ?: "error")})")
            }
        }
    }

    @JavascriptInterface
    fun cancelBuild() {
        cancelFlag = true
    }

    /** Copies the finished file into the phone's Download folder. */
    @JavascriptInterface
    fun saveToDownloads(name: String) {
        Thread {
            try {
                val src = File(exports, safeName(name))
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, src.name)
                    put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("no uri")
                ctx.contentResolver.openOutputStream(uri)!!.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                }
                js("window.__fileMsg('فایل در پوشهٔ Download گوشی ذخیره شد.',false)")
            } catch (t: Throwable) {
                js("window.__fileMsg('ذخیره‌سازی ممکن نشد.',true)")
            }
        }.start()
    }

    /** Opens the Android share sheet for the finished file. */
    @JavascriptInterface
    fun shareFile(name: String) {
        try {
            val file = File(exports, safeName(name))
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.runOnUiThread {
                ctx.startActivity(Intent.createChooser(send, "اشتراک‌گذاری فایل صوتی"))
            }
        } catch (t: Throwable) {
            js("window.__fileMsg('اشتراک‌گذاری ممکن نشد.',true)")
        }
    }
}
