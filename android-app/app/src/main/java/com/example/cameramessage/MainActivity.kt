package com.example.cameramessage

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.cameramessage.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.Executors

/**
 * 예제 4 — OCR 로 LED 제어 ★ (강의 마무리)
 *
 * 카메라에 비친 글자를 ML Kit 으로 읽어, **"ON" / "OFF"(켜기/끄기)** 가 보이면
 * 예제 2의 **LED 서버**로 명령을 보냅니다. 카메라로 "ON" 종이를 비추면 웹 페이지와
 * ESP32 보드의 실제 LED가 함께 켜지는, 카메라·OCR·서버를 한데 묶은 예제입니다.
 *
 *  카메라(Preview) + 프레임 분석(ImageAnalysis) → ML Kit OCR → ON/OFF 판별
 *   → 바뀌었을 때만 PUT /api/led (중복 전송 방지)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // 카메라 프레임 분석을 메인 스레드와 분리해 처리할 실행기
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // 한국어(+영문/숫자) 문자 인식기
    private val recognizer =
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    // 서버로 보낸 마지막 상태 (같은 상태를 계속 보내지 않도록)
    private var lastSent: Boolean? = null

    // 예제 2와 같은 Retrofit 클라이언트 (LED 서버)
    private val api: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, "카메라 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (hasCameraPermission()) startCamera()
        else requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }

            // 프레임 분석 — 가장 최신 프레임만 처리(밀리지 않게)
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor, TextAnalyzer())

            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, selector, preview, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, "카메라를 시작할 수 없습니다: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** 카메라 프레임 하나하나를 ML Kit 으로 분석하는 분석기. */
    private inner class TextAnalyzer : ImageAnalysis.Analyzer {
        @ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return
            }

            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    runOnUiThread { handleText(result.text) }
                }
                .addOnCompleteListener {
                    // 다음 프레임을 받으려면 반드시 닫아야 합니다.
                    imageProxy.close()
                }
        }
    }

    // 감지할 시약병 특정 키워드 리스트
    private val keywords = listOf(
        "ETHANOL", "METHANOL", "ACETONE", "ACID", "WATER", "SODIUM",
        "에탄올", "메탄올", "아세톤", "염산", "황산", "질산", "수산화나트륨"
    )

    /** 인식된 글자를 화면에 보여 주고, 키워드가 보이면 서버로 보냅니다. */
    private fun handleText(text: String) {
        binding.resultText.text = text.ifBlank { getString(R.string.ocr_hint) }

        val target = detectChemical(text)
        if (target == lastSent) return              // 이미 그 상태면 다시 보내지 않음
        lastSent = target
        sendDeviceState(target)
    }

    /**
     * 글자 속에서 특정 키워드가 있는지 판별합니다.
     *  - 키워드가 하나라도 포함되어 있으면 true (디바이스 ON)
     *  - 키워드가 전혀 없거나 비어 있으면 false (디바이스 OFF)
     */
    private fun detectChemical(text: String): Boolean {
        if (text.isBlank()) return false
        val upper = text.uppercase()
        return keywords.any { upper.contains(it) }
    }

    /** 판별한 상태를 서버(PUT /api/device/{device_id})로 보냅니다. */
    private fun sendDeviceState(on: Boolean) {
        lifecycleScope.launch {
            try {
                val state = api.setDevice("device1", DeviceCommand(on = on, by = "OCR"))
                val mark = if (state.on) "🟡 ON" else "⚪ OFF"
                binding.statusText.text = "Device: $mark  (${state.by} · ${state.time})"
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "전송 실패: 서버 주소(BASE_URL)와 같은 WiFi인지 확인하세요.",
                    Toast.LENGTH_LONG
                ).show()
                lastSent = null   // 실패했으니 다음에 다시 시도하도록
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        // 에뮬레이터에서 'PC의 localhost' 는 10.0.2.2 입니다.
        // 실제 폰에서는 PC의 실제 IP로 바꾸세요. 예: "http://192.168.0.10:8000/"
        // (끝에 슬래시 / 를 꼭 붙입니다.)
        private const val BASE_URL = "http://10.98.81.70:8000/"
    }
}
