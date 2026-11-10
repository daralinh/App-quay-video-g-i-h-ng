package com.example.packvideo

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var preview: PreviewView
    private lateinit var btnRec: Button
    private lateinit var btnScan: Button
    private lateinit var btnPost: Button
    private lateinit var tvCode: TextView
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var currentCode: String? = null
    private var scanning = false
    private var lastUri: Uri? = null
    private val scanner = BarcodeScanning.getClient()
    private val prefs by lazy { getSharedPreferences("s", 0) }
    private val VIDEO_DIR = "Movies/DonHang"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        preview = PreviewView(this)
        tvCode = TextView(this).apply { setTextColor(Color.YELLOW); textSize = 18f; setBackgroundColor(0x88000000.toInt()); setPadding(24, 16, 24, 16); text = "Chưa có mã đơn" }
        btnRec = Button(this).apply { text = "● QUAY"; setOnClickListener { toggleRecord() } }
        btnScan = Button(this).apply { text = "Quét mã"; setOnClickListener { scanning = true; toast("Hướng camera vào QR/mã vạch...") } }
        btnPost = Button(this).apply { text = "Quét video vừa quay"; setOnClickListener { lastUri?.let { scanVideo(it) } ?: toast("Chưa có video") } }
        val btnSet = Button(this).apply { text = "⚙ Số ngày"; setOnClickListener { showSettings() } }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            listOf(btnScan, btnRec, btnPost, btnSet).forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
        }
        val root = FrameLayout(this)
        root.addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(tvCode, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        root.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "cleanup", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CleanupWorker>(1, TimeUnit.DAYS).build()
        )
        CleanupWorker.runCleanup(this)

        val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (need.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startCamera()
        else registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
            if (it[Manifest.permission.CAMERA] == true) startCamera() else toast("Cần quyền camera")
        }.launch(need)
    }

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val p = f.get()
            val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val rec = Recorder.Builder().setQualitySelector(
                QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build()
            videoCapture = VideoCapture.withOutput(rec)
            val ia = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            ia.setAnalyzer(ContextCompat.getMainExecutor(this)) { analyze(it) }
            p.unbindAll()
            p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, videoCapture, ia)
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val img = proxy.image
        if (!scanning || img == null) { proxy.close(); return }
        scanner.process(InputImage.fromMediaImage(img, proxy.imageInfo.rotationDegrees))
            .addOnSuccessListener { list ->
                val v = list.firstNotNullOfOrNull { it.rawValue }
                if (v != null && scanning) { scanning = false; currentCode = v; tvCode.text = "Mã đơn: $v" }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun toggleRecord() {
        if (recording != null) { recording?.stop(); recording = null; btnRec.text = "● QUAY"; return }
        val vc = videoCapture ?: return
        currentCode = null; tvCode.text = "Đang quay... bấm \"Quét mã\" hoặc để tự quét"
        scanning = true // tự động quét ngay khi bắt đầu quay
        val name = "VID_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, VIDEO_DIR)
        }
        val opts = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(cv).build()
        var pr = vc.output.prepareRecording(this, opts)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            pr = pr.withAudioEnabled()
        recording = pr.start(ContextCompat.getMainExecutor(this)) { ev ->
            if (ev is VideoRecordEvent.Finalize) {
                scanning = false
                if (ev.hasError()) { toast("Lỗi quay video"); return@start }
                lastUri = ev.outputResults.outputUri
                val c = currentCode
                if (c != null) renameVideo(lastUri!!, c) else toast("Chưa có mã. Bấm \"Quét video vừa quay\"")
            }
        }
        btnRec.text = "■ DỪNG"
    }

    private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").trim().take(100)

    private fun exists(name: String, excludeId: Long): Boolean =
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.DISPLAY_NAME}=? AND ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media._ID}!=?",
            arrayOf("$name.mp4", "$VIDEO_DIR%", excludeId.toString()), null
        )?.use { it.count > 0 } ?: false

    private fun renameVideo(uri: Uri, code: String) {
        val id = ContentUris.parseId(uri)
        val base = safe(code).ifEmpty { "video" }
        var name = base; var i = 0
        while (exists(name, id)) { i++; name = "${base}_$i" }   // trùng tên -> _1, _2, _3...
        try {
            contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
            }, null, null)
            toast("Đã lưu: $name.mp4")
        } catch (e: Exception) { toast("Không đổi được tên: ${e.message}") }
    }

    // Nhận diện sau khi quay: lấy khung hình mỗi 0.7s rồi quét
    private fun scanVideo(uri: Uri) {
        toast("Đang quét video...")
        thread {
            var found: String? = null
            try {
                val r = MediaMetadataRetriever(); r.setDataSource(this, uri)
                val dur = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L) * 1000
                var t = 0L
                while (t <= dur && found == null) {
                    r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)?.let { bmp ->
                        found = Tasks.await(scanner.process(InputImage.fromBitmap(bmp, 0))).firstNotNullOfOrNull { it.rawValue }
                    }
                    t += 700_000
                }
                r.release()
            } catch (_: Exception) {}
            runOnUiThread {
                found?.let { currentCode = it; tvCode.text = "Mã đơn: $it"; renameVideo(uri, it) } ?: toast("Không thấy mã trong video")
            }
        }
    }

    private fun showSettings() {
        val et = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(prefs.getInt("days", 28).toString()) }
        AlertDialog.Builder(this).setTitle("Tự xoá video sau bao nhiêu ngày?").setView(et)
            .setPositiveButton("Lưu") { _, _ ->
                val d = et.text.toString().toIntOrNull()
                if (d != null && d > 0) { prefs.edit().putInt("days", d).apply(); CleanupWorker.runCleanup(this); toast("Đã đặt $d ngày") }
            }.setNegativeButton("Huỷ", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
