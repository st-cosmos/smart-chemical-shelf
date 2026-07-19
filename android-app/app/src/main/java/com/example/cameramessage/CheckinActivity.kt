package com.example.cameramessage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.view.animation.TranslateAnimation
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.cameramessage.databinding.ActivityCheckinBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * app-checkin / -new / -new-complete / -alert (design-spec §3.5~3.8)
 * CameraX 프리뷰 + ML Kit 한국어 OCR → scan-in → 결과 카드 상태 변화 + 세션 폴링.
 */
class CheckinActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCheckinBinding
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    private var camera: Camera? = null
    private var torchOn = false
    private var activeSessionJob: Job? = null
    private var isScanned = false
    private var currentUser: String = ""
    private var currentNewItem = false
    private var recommendedShelfId: String? = null
    private var recommendedShelfDesc: String = ""
    private var ocrKeywords: List<String> = DEFAULT_KEYWORDS

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, "카메라 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCheckinBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("smart_shelf", Context.MODE_PRIVATE)
        currentUser = prefs.getString("username", "kim.lab") ?: "kim.lab"

        // Header: 반입/반출 화면에서는 Bell 버튼 숨김 (design-spec §1.3)
        binding.appHeader.headerTitle.text = "시약 반입"
        binding.appHeader.btnBell.visibility = View.GONE
        binding.appHeader.btnBack.setOnClickListener { finish() }

        NavBar.setup(binding.bottomNav, this, NavBar.TAB_CHECKIN)

        binding.cameraCard.clipToOutline = true
        binding.btnFlash.setOnClickListener {
            torchOn = !torchOn
            camera?.cameraControl?.enableTorch(torchOn)
        }

        startScanLineAnimation()
        fetchOcrKeywords()

        if (hasCameraPermission()) startCamera()
        else requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        activeSessionJob?.cancel()
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

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor, TextAnalyzer())

            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, selector, preview, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, "카메라 구동 실패: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** 스캔 라인 상하 이동 애니메이션 (design-spec §3.5-2) */
    private fun startScanLineAnimation() {
        val range = dp(80).toFloat()
        val anim = TranslateAnimation(0f, 0f, -range, range).apply {
            duration = 1600
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
            interpolator = LinearInterpolator()
        }
        binding.scanLine.startAnimation(anim)
    }

    private fun startLoaderAnimation() {
        val rotate = RotateAnimation(
            0f, 360f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 900
            repeatCount = Animation.INFINITE
            interpolator = LinearInterpolator()
        }
        binding.statusIcon.startAnimation(rotate)
    }

    /** 서버의 OCR 인식 대상 시약명 리스트를 키워드 필터로 사용 */
    private fun fetchOcrKeywords() {
        lifecycleScope.launch {
            try {
                val names = NetworkClient.api.getOcrChemicals()
                if (names.isNotEmpty()) ocrKeywords = names + DEFAULT_KEYWORDS
            } catch (e: Exception) {
                // 실패 시 기본 키워드 유지
            }
        }
    }

    private inner class TextAnalyzer : ImageAnalysis.Analyzer {
        @ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage == null || isScanned) {
                imageProxy.close()
                return
            }

            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val text = result.text.trim()
                    if (text.isNotEmpty() && !isScanned) {
                        detectAndTriggerCheckin(text)
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }

    private fun detectAndTriggerCheckin(text: String) {
        val upperText = text.uppercase()
        if (ocrKeywords.none { upperText.contains(it.uppercase()) }) return

        isScanned = true
        lifecycleScope.launch {
            try {
                val response = NetworkClient.api.scanIn(
                    ScanInRequest(ocr_text = text, username = currentUser)
                )
                if (response.status == "success") {
                    recommendedShelfId = response.recommended_shelf
                    recommendedShelfDesc = response.recommended_shelf_desc
                    currentNewItem = !response.has_history
                    showScanResult(response)
                    startSessionPolling()
                } else {
                    isScanned = false
                }
            } catch (e: Exception) {
                isScanned = false
            }
        }
    }

    /** 인식 결과 카드 갱신 — 기존(§3.5) / 신규(§3.7) 상태 */
    private fun showScanResult(response: ScanInResponse) {
        binding.scanResultEmpty.visibility = View.GONE
        binding.scanResultActive.visibility = View.VISIBLE
        binding.resultBadge.visibility = View.VISIBLE
        binding.resultBadge.text = "인식 완료"
        binding.scanStatusText.text = "안착 감지 중"

        binding.scannedChemicalName.text = response.chemical_name

        val warning = color(R.color.warning)
        if (response.has_history) {
            // 기존 보유 시약 · 재반입
            binding.meta1Icon.setImageResource(R.drawable.ic_history)
            binding.meta1Icon.imageTintList = ColorStateList.valueOf(color(R.color.text_muted))
            binding.meta1Text.text = "기존 보유 시약 · 재반입"
            binding.meta1Text.setTextColor(color(R.color.text_body))

            binding.meta2Icon.setImageResource(R.drawable.ic_map_pin)
            binding.meta2Icon.imageTintList = ColorStateList.valueOf(color(R.color.primary))
            binding.meta2Text.text = "지정 위치 : ${response.recommended_shelf_desc} — LED 점등됨"
            binding.meta2Text.setTextColor(color(R.color.primary))

            setStatusWaiting("안착 대기 중", "지정 위치 안착 감지 중")
        } else {
            // 신규 유입 시약
            binding.meta1Icon.setImageResource(R.drawable.ic_sparkles)
            binding.meta1Icon.imageTintList = ColorStateList.valueOf(warning)
            binding.meta1Text.text = "신규 유입 시약 · 등록 필요"
            binding.meta1Text.setTextColor(color(R.color.text_body))

            binding.meta2Icon.setImageResource(R.drawable.ic_help_circle)
            binding.meta2Icon.imageTintList = ColorStateList.valueOf(warning)
            binding.meta2Text.text = "원하는 선반 위치에 시약을 배치해 주세요."
            binding.meta2Text.setTextColor(warning)

            setStatusWaiting("안착 대기 중", "선반 배치 시 자동 등록")
        }
    }

    private fun setStatusWaiting(title: String, sub: String) {
        binding.statusCircle.backgroundTintList = ColorStateList.valueOf(color(R.color.warning_soft))
        binding.statusIcon.setImageResource(R.drawable.ic_loader)
        binding.statusIcon.imageTintList = ColorStateList.valueOf(color(R.color.warning))
        binding.statusTitle.text = title
        binding.statusTitle.setTextColor(color(R.color.warning))
        binding.statusSub.text = sub
        startLoaderAnimation()
    }

    private fun setStatusSuccess(title: String, sub: String) {
        binding.statusIcon.clearAnimation()
        binding.statusCircle.backgroundTintList = ColorStateList.valueOf(color(R.color.success_soft))
        binding.statusIcon.setImageResource(R.drawable.ic_check)
        binding.statusIcon.imageTintList = ColorStateList.valueOf(color(R.color.success))
        binding.statusTitle.text = title
        binding.statusTitle.setTextColor(color(R.color.success))
        binding.statusSub.text = sub
        binding.scanStatusText.text = "스캔 중"
    }

    private fun startSessionPolling() {
        activeSessionJob?.cancel()
        activeSessionJob = lifecycleScope.launch {
            while (true) {
                try {
                    val session = NetworkClient.api.getCheckinSession()
                    if (!session.active) {
                        // 세션 종료 → 안착 완료 또는 타임아웃
                        val chemicals = NetworkClient.api.getChemicals()
                        val latestChem = chemicals
                            .filter { it.name == session.chemical_name }
                            .maxByOrNull { it.time_in ?: "" }
                        if (latestChem != null && !session.timeout) {
                            handleCheckinComplete(latestChem)
                        } else {
                            resetScanState()
                        }
                        break
                    } else {
                        binding.statusSub.text = "남은 시간 ${session.time_left.toInt()}초"
                    }
                } catch (e: Exception) {
                    // 폴링 중 네트워크 오류는 무시
                }
                delay(1000)
            }
        }
    }

    private suspend fun handleCheckinComplete(chemical: ChemicalData) {
        val placedShelfId = chemical.shelf_id ?: ""
        val parentShelf = try {
            NetworkClient.api.getShelves().find { it.id == placedShelfId }?.parent_shelf
        } catch (e: Exception) {
            null
        }
        val placedShelfDesc =
            "선반 ${parentShelf ?: "?"} · ${chemical.shelf_row ?: 0}행 ${chemical.shelf_col ?: 0}열"

        when {
            // 신규 시약 → 등록 완료 success 모달 (§3.8)
            currentNewItem -> {
                setStatusSuccess("등록 완료", "선반 안착 확인")
                AppModal.show(
                    this, AppModal.Tone.SUCCESS, R.drawable.ic_check,
                    "신규 시약 등록 완료",
                    "신규 시약 [${chemical.name}]이(가)\n${placedShelfDesc}에 등록 완료되었습니다.",
                    null, "확인",
                    onPrimary = { resetScanState() }
                )
            }
            // 기존 시약이 지정 위치가 아닌 곳에 안착 → warning 모달 (§3.6)
            recommendedShelfId != null && placedShelfId != recommendedShelfId -> {
                AppModal.show(
                    this, AppModal.Tone.WARNING, R.drawable.ic_triangle_alert,
                    "잘못된 위치에 놓였습니다",
                    "${chemical.name}의 지정 위치는 ${recommendedShelfDesc}입니다.\n" +
                            "현재 ${placedShelfDesc}에 안착이 감지되었습니다.",
                    "지정 위치로 옮기기", "이 위치로 수정하기",
                    onSecondary = {
                        // 다시 옮겨 놓도록 초기화 → 재스캔 시 LED 재안내
                        resetScanState()
                    },
                    onPrimary = {
                        // 서버에는 이미 현재 칸으로 기록되어 있으므로 이 위치를 확정
                        setStatusSuccess("위치 변경 완료", "현재 위치로 등록됨")
                        scheduleReset()
                    }
                )
            }
            // 지정 위치 안착 성공 (§3.5)
            else -> {
                setStatusSuccess("등록 완료", "지정 위치 안착 확인")
                scheduleReset()
            }
        }
    }

    private fun scheduleReset() {
        lifecycleScope.launch {
            delay(4000)
            resetScanState()
        }
    }

    private fun resetScanState() {
        isScanned = false
        currentNewItem = false
        recommendedShelfId = null
        recommendedShelfDesc = ""
        activeSessionJob?.cancel()

        binding.statusIcon.clearAnimation()
        binding.scanResultActive.visibility = View.GONE
        binding.resultBadge.visibility = View.GONE
        binding.scanResultEmpty.visibility = View.VISIBLE
        binding.scanStatusText.text = "스캔 중"
    }

    private fun color(res: Int): Int = ContextCompat.getColor(this, res)

    companion object {
        private val DEFAULT_KEYWORDS = listOf(
            "에탄올", "메탄올", "아세톤", "황산", "질산", "염산", "시안", "아세토니트릴", "톨루엔",
            "ETHANOL", "METHANOL", "ACETONE", "SULFURIC", "NITRIC", "CYANIDE", "ACETONITRILE", "TOLUENE"
        )
    }
}
