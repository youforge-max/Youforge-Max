package eu.youforgemax.asr

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/** Runs Vosk over 16 kHz mono PCM and returns the recognised text. Fully offline. */
object AudioTranscriber {

    /** Transcribe [pcm16kMono]; empty string on failure / silence. */
    fun transcribe(modelDir: String, pcm16kMono: ShortArray): String {
        if (pcm16kMono.isEmpty()) return ""
        var model: Model? = null
        var rec: Recognizer? = null
        try {
            model = Model(modelDir)
            rec = Recognizer(model, 16000.0f)

            // Pack shorts → 16-bit LE bytes.
            val bytes = ByteArray(pcm16kMono.size * 2)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcm16kMono) bb.putShort(s)

            val chunk = ByteArray(8000)   // ~0.25 s per feed
            var off = 0
            while (off < bytes.size) {
                val len = min(chunk.size, bytes.size - off)
                System.arraycopy(bytes, off, chunk, 0, len)
                rec.acceptWaveForm(chunk, len)
                off += len
            }
            val json = rec.finalResult
            return JSONObject(json).optString("text").trim()
        } catch (_: Throwable) {
            return ""
        } finally {
            try { rec?.close() } catch (_: Throwable) {}
            try { model?.close() } catch (_: Throwable) {}
        }
    }
}
