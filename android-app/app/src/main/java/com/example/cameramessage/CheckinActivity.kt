package com.example.cameramessage

import android.Manifest
import android.app.AlertDialog
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
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * app-checkin / -new / -new-complete / -alert (design-spec §3.5~3.8)
 * CameraX 프리뷰 + ML Kit OCR·바코드 → ChemicalScanner(서버 매칭) → scan-in → 세션 폴링.
 *
 * 인식 확신이 낮으면 "이 시약이 맞나요?" 확인 모달을 띄우고, 계속 실패하면
 * "인식이 잘 안됩니다" 안내와 함께 목록 직접 선택 폴백을 제공한다.
 * 확인·직접 선택 결과는 서버 별칭/바코드 사전에 학습되어 다음 스캔부터 즉시 인식된다.
 */
class CheckinActivity : AppCompatActivity(), ChemicalScanner.Listener {

    private lateinit var binding: ActivityCheckinBinding
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    private val barcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E
            )
            .build()
    )
    private lateinit var scanner: ChemicalScanner

    private var camera: Camera? = null
    private var torchOn = false
    private var activeSessionJob: Job? = null
    private var isScanned = false
    private var currentUser: String = ""
    private var currentNewItem = false
    private var recommendedShelfId: String? = null
    private var recommendedShelfDesc: String = ""
    private var scannedName: String = ""  // 세션 완료 후 결과 조회용 (세션 리셋 시 이름이 비므로)

    // 세션 완료를 폴링 루프와 WebSocket 트리거가 동시에 감지해 완료 모달이
    // 두 번 뜨는 것을 막기 위한 가드. 서버는 완료 결과를 다음 세션 시작
    // 전까지 계속 돌려주므로, 클라이언트에서 1회만 처리하도록 막는다.
    private var sessionCompleted = false

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

        scanner = ChemicalScanner(lifecycleScope, this)

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
        binding.btnManualSelect.setOnClickListener { openManualSelect() }

        startScanLineAnimation()

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
            analysis.setAnalyzer(cameraExecutor, ScanAnalyzer())

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

    /** OCR + 바코드를 한 프레임에서 함께 분석해 ChemicalScanner 로 전달 */
    private inner class ScanAnalyzer : ImageAnalysis.Analyzer {
        @ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage == null || isScanned) {
                imageProxy.close()
                return
            }

            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            val textTask = recognizer.process(image)
            val barcodeTask = barcodeScanner.process(image)
            Tasks.whenAllComplete(textTask, barcodeTask).addOnCompleteListener {
                val text = if (textTask.isSuccessful) textTask.result?.text?.trim().orEmpty() else ""
                val barcode = if (barcodeTask.isSuccessful) {
                    barcodeTask.result?.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                } else null
                runOnUiThread { if (!isScanned) scanner.onFrame(text, barcode) }
                imageProxy.close()
            }
        }
    }

    // ---------- ChemicalScanner.Listener ----------

    override fun onScanStatus(phase: ChemicalScanner.Phase, chipText: String, guideText: String) {
        binding.scanStatusText.text = chipText
        if (binding.scanResultEmpty.visibility == View.VISIBLE) {
            binding.scanResultEmpty.text = guideText
            binding.btnManualSelect.visibility =
                if (phase == ChemicalScanner.Phase.WEAK || phase == ChemicalScanner.Phase.FAILED)
                    View.VISIBLE else View.GONE
        }
    }

    override fun onMatched(result: MatchResult, ocrText: String, barcode: String?) {
        isScanned = true
        val name = result.chemical_name ?: run { resumeScanning(); return }
        // 바코드로 매칭된 경우는 이미 학습된 것 → 재학습 불필요
        requestScanIn(name, ocrText, barcode.takeIf { result.method != "barcode" }, learnToken = null)
    }

    override fun onNeedsConfirmation(result: MatchResult, ocrText: String, barcode: String?) {
        isScanned = true
        val name = result.chemical_name ?: run { resumeScanning(); return }
        val percent = (result.confidence * 100).toInt()
        AppModal.show(
            this, AppModal.Tone.PRIMARY, R.drawable.ic_flask_conical,
            "이 시약이 맞나요?",
            "인식 결과: $name\n(일치율 ${percent}%)",
            "아니요", "맞아요",
            onSecondary = {
                scanner.declineCandidate(name)
                Toast.makeText(
                    this, "다시 비춰주세요. 계속 안 되면 '직접 선택'을 이용하세요.", Toast.LENGTH_SHORT
                ).show()
                resumeScanning(800)
            },
            onPrimary = {
                // 사용자 확인 결과를 학습(별칭·바코드)해 다음부터 즉시 인식되게 한다
                requestScanIn(
                    name, ocrText,
                    barcode.takeIf { result.method != "barcode" },
                    learnToken = result.matched_token
                )
            }
        )
    }

    // ---------- 반입 요청 / 학습 ----------

    private fun requestScanIn(name: String, ocrText: String, barcode: String?, learnToken: String?) {
        lifecycleScope.launch {
            try {
                val response = NetworkClient.api.scanIn(
                    ScanInRequest(ocr_text = ocrText, username = currentUser, chemical_name = name)
                )
                if (response.status == "success") {
                    scannedName = response.chemical_name
                    recommendedShelfId = response.recommended_shelf
                    recommendedShelfDesc = response.recommended_shelf_desc
                    currentNewItem = !response.has_history
                    showScanResult(response)
                    startSessionPolling()
                    learnMatch(name, barcode, learnToken)
                } else {
                    resumeScanning(1500)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@CheckinActivity,
                    httpErrorDetail(e) ?: "반입 요청 실패 — 잠시 후 다시 시도합니다.",
                    Toast.LENGTH_SHORT
                ).show()
                resumeScanning(2500)
            }
        }
    }

    /** 확인·선택된 매핑을 서버에 학습 (실패해도 동작에는 지장 없음) */
    private fun learnMatch(name: String, barcode: String?, token: String?) {
        if (barcode == null && token == null) return
        lifecycleScope.launch {
            try {
                NetworkClient.api.confirmMatch(MatchConfirmRequest(name, barcode, token))
            } catch (e: Exception) {
                // 학습 실패는 무시
            }
        }
    }

    // ---------- 직접 선택 폴백 ----------

    private fun openManualSelect() {
        isScanned = true
        scanner.freeze()
        lifecycleScope.launch {
            val names = try {
                NetworkClient.api.getKnownNames()
            } catch (e: Exception) {
                emptyList()
            }
            if (names.isEmpty()) {
                Toast.makeText(this@CheckinActivity, "시약 목록을 불러오지 못했습니다.", Toast.LENGTH_SHORT).show()
                resumeScanning(500)
                return@launch
            }
            AlertDialog.Builder(this@CheckinActivity)
                .setTitle("반입할 시약 직접 선택")
                .setItems(names.toTypedArray()) { _, which ->
                    requestScanIn(names[which], scanner.aggregatedText, scanner.activeBarcode, null)
                }
                .setNegativeButton("취소") { _, _ -> resumeScanning() }
                .setOnCancelListener { resumeScanning() }
                .show()
        }
    }

    private fun resumeScanning(cooldownMs: Long = 0L) {
        isScanned = false
        scanner.resume(cooldownMs)
    }

    // ---------- 결과 카드 / 세션 ----------

    /** 인식 결과 카드 갱신 — 기존(§3.5) / 신규(§3.7) 상태 */
    private fun showScanResult(response: ScanInResponse) {
        binding.scanResultEmpty.visibility = View.GONE
        binding.btnManualSelect.visibility = View.GONE
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
        sessionCompleted = false
        AppWebSocketManager.connect(NetworkClient.BASE_URL)

        val wsListener: (String) -> Unit = { _ ->
            lifecycleScope.launch {
                checkCheckinSessionOnce()
            }
        }
        AppWebSocketManager.addListener(wsListener)

        activeSessionJob = lifecycleScope.launch {
            try {
                while (true) {
                    val finished = checkCheckinSessionOnce()
                    if (finished) break
                    delay(1000)
                }
            } finally {
                AppWebSocketManager.removeListener(wsListener)
            }
        }
    }

    private suspend fun checkCheckinSessionOnce(): Boolean {
        try {
            val session = NetworkClient.api.getCheckinSession()
            if (!session.active) {
                // 폴링 루프와 WS 트리거가 동시에 완료를 감지해도 한 번만 처리한다.
                // (아래 suspend 호출 전에 플래그를 설정해야 동시 호출 시에도 안전하다)
                if (sessionCompleted) return true
                sessionCompleted = true

                // 세션 종료 → 안착 완료 또는 타임아웃
                val targetName = session.chemical_name.ifBlank { scannedName }
                val chemicals = NetworkClient.api.getChemicals()
                val latestChem = chemicals
                    .filter { it.name == targetName }
                    .maxByOrNull { it.time_in ?: "" }
                if (latestChem != null && !session.timeout) {
                    handleCheckinComplete(latestChem)
                } else {
                    resetScanState()
                }
                return true
            } else {
                binding.statusSub.text = "남은 시간 ${session.time_left.toInt()}초"
            }
        } catch (e: Exception) {
            // 네트워크 오류 무시
        }
        return false
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

        // 반입 즉시 인접 수납칸 혼재 금지 시약 연동 검사
        val alerts = try { NetworkClient.api.getAlerts() } catch (e: Exception) { null }
        val coWarning = alerts?.co_storage_warnings?.firstOrNull {
            it.chemical_1_id == chemical.id || it.chemical_2_id == chemical.id
        }

        if (coWarning != null) {
            val safeDesc = coWarning.recommended_safe_shelf_desc ?: "선반 C · 분리 보관 구역"
            val reasonText = coWarning.reason ?: "반응 및 발열 위험"
            AppModal.show(
                this, AppModal.Tone.DANGER, R.drawable.ic_shield_alert,
                "🚨 혼재 위험! 인접 보관 금지 수납칸에 안착됨",
                "[${chemical.name}]이(가) 인접 보관 금지 시약과 함께 안착되었습니다.\n\n" +
                        "${coWarning.message}\n💡 ${reasonText}\n\n" +
                        "👉 추천 이동 위치: ${safeDesc}",
                "이 위치 유지", "안전 위치로 이동",
                onPrimary = {
                    AppModal.show(
                        this, AppModal.Tone.SUCCESS, R.drawable.ic_check,
                        "추천 안전 보관 위치 안내",
                        "[${chemical.name}]을(를)\n${safeDesc}(으)로 이동하여 안착해 주세요.",
                        null, "확인",
                        autoDismissMs = 3500L,
                        onPrimary = { resetScanState() }
                    )
                },
                onSecondary = { resetScanState() }
            )
            return
        }

        when {
            // 신규 시약 → 등록 완료 success 모달 (§3.8)
            currentNewItem -> {
                setStatusSuccess("등록 완료", "선반 안착 확인")
                AppModal.show(
                    this, AppModal.Tone.SUCCESS, R.drawable.ic_check,
                    "신규 시약 등록 완료",
                    "신규 시약 [${chemical.name}]이(가)\n${placedShelfDesc}에 등록 완료되었습니다.",
                    null, "확인",
                    autoDismissMs = 3000L,
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
                        AppModal.show(
                            this, AppModal.Tone.SUCCESS, R.drawable.ic_check,
                            "위치 변경 완료",
                            "[${chemical.name}]의 보관 위치가\n${placedShelfDesc}(으)로 변경되었습니다.",
                            null, "확인",
                            autoDismissMs = 3000L,
                            onPrimary = { resetScanState() }
                        )
                    }
                )
            }
            // 지정 위치 안착 성공 (§3.5) — 하단 카드 갱신 + 완료 팝업
            else -> {
                setStatusSuccess("등록 완료", "지정 위치 안착 확인")
                AppModal.show(
                    this, AppModal.Tone.SUCCESS, R.drawable.ic_check,
                    "반입 완료",
                    "[${chemical.name}]이(가)\n${placedShelfDesc}에 반입 완료되었습니다.",
                    null, "확인",
                    autoDismissMs = 3000L,
                    onPrimary = { resetScanState() }
                )
            }
        }
    }

    private fun resetScanState() {
        currentNewItem = false
        recommendedShelfId = null
        recommendedShelfDesc = ""
        scannedName = ""
        activeSessionJob?.cancel()

        binding.statusIcon.clearAnimation()
        binding.scanResultActive.visibility = View.GONE
        binding.resultBadge.visibility = View.GONE
        binding.scanResultEmpty.visibility = View.VISIBLE
        binding.btnManualSelect.visibility = View.GONE
        resumeScanning()
    }

    private fun color(res: Int): Int = ContextCompat.getColor(this, res)
}
