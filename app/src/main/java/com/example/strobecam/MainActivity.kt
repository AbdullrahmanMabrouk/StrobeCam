package com.example.strobecam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.os.*
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var tvHz: TextView
    private lateinit var sbHz: SeekBar
    private lateinit var btnStrobe: Button
    private lateinit var btnRecord: Button

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private lateinit var previewRequestBuilder: CaptureRequest.Builder

    private var isStrobeActive = false
    private var frequencyHz = 5.0 // Changed from 5 to 5.0
    private val strobeHandler = Handler(Looper.getMainLooper())
    private var isFlashOn = false

    private var isRecording = false
    private var mediaRecorder: MediaRecorder? = null
    private var videoFile: File? = null

    private val strobeRunnable = object : Runnable {
        override fun run() {
            if (!isStrobeActive) return
            try {
                isFlashOn = !isFlashOn
                previewRequestBuilder.set(
                    CaptureRequest.FLASH_MODE,
                    if (isFlashOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
                )
                captureSession?.setRepeatingRequest(previewRequestBuilder.build(), null, null)
            } catch (e: Exception) { e.printStackTrace() }
            
            val delay = (1000 / (2 * frequencyHz)).toLong()
            strobeHandler.postDelayed(this, delay)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        tvHz = findViewById(R.id.tvHz)
        sbHz = findViewById(R.id.sbHz)
        btnStrobe = findViewById(R.id.btnStrobe)
        btnRecord = findViewById(R.id.btnRecord)

        sbHz.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Divide by 10.0 to turn an integer like 25 into 2.5 Hz
                val hz = if (progress < 1) 0.1 else progress / 10.0
                frequencyHz = hz
                tvHz.text = "Frequency: $hz Hz"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnStrobe.setOnClickListener { toggleStrobe() }
        btnRecord.setOnClickListener { toggleRecording() }

        requestPermissions()
    }

    private fun requestPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 100)
        } else {
            initCamera()
        }
    }

    private fun initCamera() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList[0]
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                startCameraSession()
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); cameraDevice = null }
            override fun onError(camera: CameraDevice, error: Int) { camera.close(); cameraDevice = null }
        }, null)
    }

    private fun startCameraSession() {
        val camera = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(1920, 1080)
        val previewSurface = Surface(texture)

        videoFile = File(getExternalFilesDir(null), "strobe_video_${System.currentTimeMillis()}.mp4")
        setupMediaRecorder()

        val recordSurface = mediaRecorder?.surface ?: return
        val surfaces = listOf(previewSurface, recordSurface)

        previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        previewRequestBuilder.addTarget(previewSurface)
        previewRequestBuilder.addTarget(recordSurface)

        camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                captureSession?.setRepeatingRequest(previewRequestBuilder.build(), null, null)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {}
        }, null)
    }

    private fun setupMediaRecorder() {
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(videoFile?.absolutePath)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoSize(1920, 1080)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(10000000)
            prepare()
        }
    }

    private fun toggleStrobe() {
        isStrobeActive = !isStrobeActive
        if (isStrobeActive) {
            btnStrobe.text = "Stop Strobe"
            strobeHandler.post(strobeRunnable)
        } else {
            btnStrobe.text = "Start Strobe"
            strobeHandler.removeCallbacks(strobeRunnable)
            previewRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            captureSession?.setRepeatingRequest(previewRequestBuilder.build(), null, null)
        }
    }

    private fun toggleRecording() {
        if (isRecording) {
            try {
                mediaRecorder?.stop()
            } catch (e: Exception) { e.printStackTrace() }
            mediaRecorder?.reset()
            isRecording = false
            btnRecord.text = "Start Record"
            Toast.makeText(this, "Video saved to: ${videoFile?.name}", Toast.LENGTH_LONG).show()
            startCameraSession() // Reconfigure session for the next run
        } else {
            mediaRecorder?.start()
            isRecording = true
            btnRecord.text = "Stop Record"
        }
    }
}
