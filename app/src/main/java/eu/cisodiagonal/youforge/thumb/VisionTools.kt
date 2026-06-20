package eu.cisodiagonal.youforge.thumb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * On-device vision helpers (MediaPipe Tasks). Models are bundled in assets, so
 * everything runs offline with no cloud. Android-specific by nature; the future
 * desktop builds will provide their own implementation behind the same idea.
 *
 * Every call is fully guarded — on any failure it returns the original image (or
 * null) and never throws into the UI.
 */
object VisionTools {

    private const val SEG_MODEL = "selfie_segmenter.tflite"
    private const val FACE_MODEL = "blaze_face_short_range.tflite"

    enum class BackgroundStyle { DARK, BLUR, COLOR }

    /**
     * Cut the subject out with the selfie segmenter and recomposite over a new
     * background. Returns null on any failure (e.g. MediaPipe vision native lib
     * unavailable on this ABI) so the caller can surface an honest error instead
     * of silently leaving the photo unchanged.
     */
    fun removeBackground(
        context: Context,
        source: Bitmap,
        style: BackgroundStyle = BackgroundStyle.DARK,
        color: Int = 0xFF0E0E12.toInt()
    ): Bitmap? {
        var seg: ImageSegmenter? = null
        try {
            val opts = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(SEG_MODEL).build())
                .setRunningMode(RunningMode.IMAGE)
                .setOutputConfidenceMasks(true)
                .setOutputCategoryMask(false)
                .build()
            seg = ImageSegmenter.createFromOptions(context, opts)

            val src = source.copy(Bitmap.Config.ARGB_8888, false)
            val w = src.width; val h = src.height
            val result = seg.segment(BitmapImageBuilder(src).build())
            val masks = result.confidenceMasks().orElse(null) ?: return null
            if (masks.isEmpty()) return null
            val maskImg = masks[0]
            val buf = ByteBufferExtractor.extract(maskImg).order(ByteOrder.LITTLE_ENDIAN)
            val floats = buf.asFloatBuffer()
            val mw = maskImg.width; val mh = maskImg.height

            val fg = IntArray(w * h)
            src.getPixels(fg, 0, w, 0, 0, w, h)
            val bg = backgroundLayer(src, style, color)
            val bgPx = IntArray(w * h)
            bg.getPixels(bgPx, 0, w, 0, 0, w, h)

            val out = IntArray(w * h)
            for (y in 0 until h) {
                val my = if (mh == h) y else (y * mh / h).coerceIn(0, mh - 1)
                for (x in 0 until w) {
                    val mx = if (mw == w) x else (x * mw / w).coerceIn(0, mw - 1)
                    val p = floats.get(my * mw + mx)             // 0..1 foreground prob
                    val i = y * w + x
                    out[i] = blend(bgPx[i], fg[i], p.coerceIn(0f, 1f))
                }
            }
            return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            return null
        } finally {
            seg?.close()
        }
    }

    /**
     * Detect the largest face and return a crop that frames the subject (face in
     * the upper third, ~32% tall). Null if no face / on failure.
     */
    fun autoCropToFace(context: Context, source: Bitmap): Bitmap? {
        var det: FaceDetector? = null
        try {
            val opts = FaceDetector.FaceDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(FACE_MODEL).build())
                .setRunningMode(RunningMode.IMAGE)
                .setMinDetectionConfidence(0.5f)
                .build()
            det = FaceDetector.createFromOptions(context, opts)

            val src = source.copy(Bitmap.Config.ARGB_8888, false)
            val res = det.detect(BitmapImageBuilder(src).build())
            val faces = res.detections()
            if (faces.isEmpty()) return null
            val box: RectF = faces.maxByOrNull { it.boundingBox().width() * it.boundingBox().height() }!!
                .boundingBox()

            val w = src.width; val h = src.height
            val faceCx = (box.left + box.right) / 2f
            val faceCy = (box.top + box.bottom) / 2f
            // Want the face ~32% of the crop height → derive crop height from face height.
            val cropH = (box.height() / 0.32f).coerceIn(box.height(), h.toFloat())
            val cropW = (cropH * w / h).coerceAtMost(w.toFloat())
            // Face sits in the upper third: face centre at ~38% down the crop.
            var top = faceCy - cropH * 0.38f
            var left = faceCx - cropW / 2f
            top = top.coerceIn(0f, h - cropH)
            left = left.coerceIn(0f, w - cropW)

            val r = Rect(
                left.roundToInt(), top.roundToInt(),
                (left + cropW).roundToInt().coerceAtMost(w),
                (top + cropH).roundToInt().coerceAtMost(h)
            )
            if (r.width() <= 0 || r.height() <= 0) return null
            return Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
        } catch (_: Throwable) {
            return null
        } finally {
            det?.close()
        }
    }

    private fun backgroundLayer(src: Bitmap, style: BackgroundStyle, color: Int): Bitmap {
        val w = src.width; val h = src.height
        return when (style) {
            BackgroundStyle.COLOR, BackgroundStyle.DARK -> {
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                Canvas(b).drawColor(if (style == BackgroundStyle.DARK) 0xFF0E0E12.toInt() else color)
                b
            }
            BackgroundStyle.BLUR -> cheapBlur(src)
        }
    }

    /** Cheap separable blur via downscale → upscale (no RenderScript/RenderEffect dep). */
    private fun cheapBlur(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val small = Bitmap.createScaledBitmap(src, max(1, w / 16), max(1, h / 16), true)
        val up = Bitmap.createScaledBitmap(small, w, h, true)
        // Darken slightly so the subject still pops.
        val out = up.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawColor(0x33000000, android.graphics.PorterDuff.Mode.SRC_OVER)
        return out
    }

    private fun blend(bg: Int, fg: Int, w: Float): Int {
        val iw = 1f - w
        val r = (Color.red(fg) * w + Color.red(bg) * iw).roundToInt().coerceIn(0, 255)
        val g = (Color.green(fg) * w + Color.green(bg) * iw).roundToInt().coerceIn(0, 255)
        val b = (Color.blue(fg) * w + Color.blue(bg) * iw).roundToInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }
}
