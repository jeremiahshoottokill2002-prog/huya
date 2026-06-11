package com.zaza.cloudycam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.Surface
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: CameraRenderer
    private lateinit var effectButton: Button
    private lateinit var recordButton: Button
    private lateinit var zoomButton: Button
    private lateinit var flipButton: Button
    private lateinit var qualityButton: Button

    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private var surfaceReady = false
    private var permissionsGranted = false
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var zoomIndex = 0

    private val zoomLevels = floatArrayOf(1.0f, 1.5f, 2.0f, 3.0f)
    private val effectNames = arrayOf(
        "Normal", "Fisheye", "Concave", "Wide",
        "Mirror", "B&W", "Crisp", "Smooth"
    )
    private val qualityNames = arrayOf("HD", "FHD", "SD")
    private val qualityValues = arrayOf(Quality.HD, Quality.FHD, Quality.SD)
    private var qualityIndex = 0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results[Manifest.permission.CAMERA] == true
        if (permissionsGranted) {
            maybeStartCamera()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        renderer = CameraRenderer(
            onSurfaceReady = {
                surfaceReady = true
                runOnUiThread { maybeStartCamera() }
            },
            requestRender = { glView.requestRender() }
        )

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }

        effectButton = makeButton("✨ Normal") {
            renderer.effectMode = (renderer.effectMode + 1) % effectNames.size
            effectButton.text = "✨ " + effectNames[renderer.effectMode]
        }

        zoomButton = makeButton("🔍 1.0x") {
            zoomIndex = (zoomIndex + 1) % zoomLevels.size
            camera?.cameraControl?.setZoomRatio(zoomLevels[zoomIndex])
            zoomButton.text = "🔍 " + zoomLevels[zoomIndex] + "x"
        }

        flipButton = makeButton("🔄") {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            zoomIndex = 0
            zoomButton.text = "🔍 1.0x"
            maybeStartCamera()
        }

        recordButton = makeButton("● REC") { toggleRecording() }
        recordButton.setTextColor(Color.RED)

        qualityButton = makeButton("🎞 HD") {
            if (activeRecording != null) {
                Toast.makeText(this, "Stop recording first", Toast.LENGTH_SHORT).show()
                return@makeButton
            }
            qualityIndex = (qualityIndex + 1) % qualityNames.size
            qualityButton.text = "🎞 " + qualityNames[qualityIndex]
            maybeStartCamera() // rebind with new quality
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(effectButton, lpWrap())
            addView(zoomButton, lpWrap())
            addView(qualityButton, lpWrap())
            addView(flipButton, lpWrap())
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(topRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(recordButton, LinearLayout.LayoutParams(
                dp(200),
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }

        val root = FrameLayout(this).apply {
            addView(glView)
            addView(
                buttonRow,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                ).apply { bottomMargin = dp(24) }
            )
        }

        setContentView(root)

        permissionLauncher.launch(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        )
    }

    private fun maybeStartCamera() {
        if (!surfaceReady || !permissionsGranted) return

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider { request ->
                val st = renderer.surfaceTexture ?: return@setSurfaceProvider
                st.setDefaultBufferSize(
                    request.resolution.width,
                    request.resolution.height
                )
                val surface = Surface(st)
                request.provideSurface(
                    surface,
                    ContextCompat.getMainExecutor(this)
                ) { surface.release() }
            }

            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        qualityValues[qualityIndex],
                        androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                )
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val selector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, preview, videoCapture)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error: " + e.message, Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleRecording() {
        val capture = videoCapture ?: return

        // Stop if already recording
        activeRecording?.let {
            it.stop()
            activeRecording = null
            return
        }

        val name = "CloudyCam_" + SimpleDateFormat(
            "yyyyMMdd_HHmmss", Locale.US
        ).format(System.currentTimeMillis())

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CloudyCam")
            }
        }

        val outputOptions = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        val pending = capture.output.prepareRecording(this, outputOptions)
        val hasAudio = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasAudio) pending.withAudioEnabled()

        activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    recordButton.text = "■ STOP"
                }
                is VideoRecordEvent.Finalize -> {
                    recordButton.text = "● REC"
                    if (event.hasError()) {
                        Toast.makeText(
                            this, "Recording failed: " + event.error, Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            this, "Saved to Movies/CloudyCam 🎬", Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                else -> {}
            }
        }
    }

    private fun makeButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setBackgroundColor(Color.argb(160, 20, 20, 30))
            setTextColor(Color.WHITE)
            setOnClickListener { onClick() }
        }

    private fun lpWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { marginStart = dp(4); marginEnd = dp(4) }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
