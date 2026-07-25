package com.example.cameramessage

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.TranslateAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.cameramessage.databinding.ActivityCheckoutBinding
import com.example.cameramessage.databinding.DialogSearchResultBinding
import com.example.cameramessage.databinding.ItemChemicalSearchBinding
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
 * app-checkout / -search (design-spec §3.9, §3.10)
 * 검색바(시약 검색 → 결과 모달: 미니맵 LED 셀 + select-led) +
 * CameraX·ML Kit OCR/바코드 → ChemicalScanner(서버 매칭) → scan-out.
 *
 * 인식 확신이 낮으면 확인 모달, 계속 실패하면 "인식이 잘 안됩니다" 안내와
 * 비치중 시약 목록에서 직접 선택하는 폴백을 제공한다.
 */
class CheckoutActivity : AppCompatActivity(), ChemicalScanner.Listener {

    private lateinit var binding: ActivityCheckoutBinding
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
    private var isScanned = false
    private var currentUser: String = ""
    private var chemicalsList = listOf<ChemicalData>()
    private var searchJob: Job? = null
    private lateinit var exceptionHelper: ExceptionDialogHelper

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, "카메라 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCheckoutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        exceptionHelper = ExceptionDialogHelper(this)
        scanner = ChemicalScanner(lifecycleScope, this)

        val prefs = getSharedPreferences("smart_shelf", Context.MODE_PRIVATE)
        currentUser = prefs.getString("username", "kim.lab") ?: "kim.lab"

        binding.appHeader.headerTitle.text = "시약 반출"
        binding.appHeader.btnBell.visibility = View.GONE
        binding.appHeader.btnBack.setOnClickListener { finish() }

        NavBar.setup(binding.bottomNav, this, NavBar.TAB_CHECKOUT)

        binding.cameraCard.clipToOutline = true
        binding.btnFlash.setOnClickListener {
            torchOn = !torchOn
            camera?.cameraControl?.enableTorch(torchOn)
        }
        binding.btnManualSelect.setOnClickListener { openManualSelect() }

        startScanLineAnimation()
        setupSearch()

        if (hasCameraPermission()) startCamera()
        else requestPermissionLauncher.launch(Manifest.permission.CAMERA)

        fetchChemicals()
    }

    override fun onResume() {
        super.onResume()
        exceptionHelper.startPollingAlerts()
    }

    override fun onPause() {
        super.onPause()
        exceptionHelper.stopPollingAlerts()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
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

    // ---------- 검색 ----------

    private fun setupSearch() {
        binding.searchResultsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.searchResultsRecyclerView.adapter = SearchResultsAdapter { chem ->
            binding.searchEditText.setText("")
            binding.searchResultsCard.visibility = View.GONE
            selectChemicalForLed(chem)
        }

        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(300)
                    val query = s?.toString()?.trim() ?: ""
                    if (query.length >= 1) {
                        val filtered = chemicalsList.filter {
                            it.current_status == "비치중" && (
                                    it.name.contains(query, ignoreCase = true) ||
                                            (it.formula?.contains(query, ignoreCase = true) ?: false)
                                    )
                        }
                        (binding.searchResultsRecyclerView.adapter as? SearchResultsAdapter)
                            ?.updateData(filtered)
                        binding.searchResultsCard.visibility =
                            if (filtered.isNotEmpty()) View.VISIBLE else View.GONE
                    } else {
                        binding.searchResultsCard.visibility = View.GONE
                    }
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun fetchChemicals() {
        lifecycleScope.launch {
            try {
                chemicalsList = NetworkClient.api.getChemicals()
            } catch (e: Exception) {
            }
        }
    }

    private fun selectChemicalForLed(chem: ChemicalData) {
        lifecycleScope.launch {
            try {
                val response = NetworkClient.api.selectLed(SelectLedRequest(chem_id = chem.id))
                if (response.status == "success") {
                    val configs = try {
                        NetworkClient.api.getShelfConfigs()
                    } catch (e: Exception) {
                        emptyList()
                    }
                    showSearchResultDialog(chem, response, configs)
                }
            } catch (e: Exception) {
                Toast.makeText(this@CheckoutActivity, "LED 안내 요청 실패", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 검색 결과 모달 (§3.10): 시약 정보 + 선반 미니맵 LED 셀 표시 */
    private fun showSearchResultDialog(
        chem: ChemicalData,
        response: SelectLedResponse,
        configs: List<ShelfConfig>
    ) {
        val b = DialogSearchResultBinding.inflate(LayoutInflater.from(this))

        val formula = chem.formula?.let { " ($it)" } ?: ""
        b.searchChemName.text = "${chem.name}$formula"
        b.searchChemMeta.text =
            "잔량 ${chem.weight} kg · 유통기한 ${chem.expiration_date ?: "-"}"
        b.searchLocationText.text =
            "선반 ${response.parent_shelf} · ${response.row}행 ${response.col}열"

        // 미니맵 그리드 (해당 위치 셀 = primary + flask 아이콘)
        val config = configs.find { it.id == response.parent_shelf }
        val rows = config?.rows ?: 3
        val cols = config?.cols ?: 4
        b.minimapGrid.removeAllViews()
        for (r in 1..rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (c in 1..cols) {
                val isTarget = r == response.row && c == response.col
                val cell = FrameLayout(this).apply {
                    setBackgroundResource(R.drawable.shape_rounded_8)
                    backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(
                            this@CheckoutActivity,
                            if (isTarget) R.color.primary else R.color.surface
                        )
                    )
                    if (isTarget) elevation = dp(3).toFloat()
                    layoutParams = LinearLayout.LayoutParams(dp(64), dp(36)).apply {
                        setMargins(dp(3), dp(3), dp(3), dp(3))
                    }
                }
                if (isTarget) {
                    val icon = ImageView(this).apply {
                        setImageResource(R.drawable.ic_flask_conical)
                        imageTintList = ColorStateList.valueOf(Color.WHITE)
                        layoutParams = FrameLayout.LayoutParams(dp(16), dp(16)).apply {
                            gravity = android.view.Gravity.CENTER
                        }
                    }
                    cell.addView(icon)
                }
                rowLayout.addView(cell)
            }
            b.minimapGrid.addView(rowLayout)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(b.root)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        b.btnCloseSearch.setOnClickListener { dialog.dismiss() }
        b.btnConfirmSearch.setOnClickListener { dialog.dismiss() }
        dialog.show()
        dialog.window?.setLayout(dp(390), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    // ---------- 반출 스캔 ----------

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
        requestScanOut(name, ocrText, barcode.takeIf { result.method != "barcode" }, learnToken = null)
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
                requestScanOut(
                    name, ocrText,
                    barcode.takeIf { result.method != "barcode" },
                    learnToken = result.matched_token
                )
            }
        )
    }

    // ---------- 반출 요청 / 학습 ----------

    private fun requestScanOut(name: String, ocrText: String, barcode: String?, learnToken: String?) {
        lifecycleScope.launch {
            try {
                val response = NetworkClient.api.scanOut(
                    ScanOutRequest(ocr_text = ocrText, username = currentUser, chemical_name = name)
                )
                if (response.status == "success") {
                    showCheckoutResult(response.chemical)
                    learnMatch(name, barcode, learnToken)
                } else {
                    resumeScanning(1500)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@CheckoutActivity,
                    httpErrorDetail(e) ?: "반출 기록 실패 — 잠시 후 다시 시도합니다.",
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
        val stocked = chemicalsList.filter { it.current_status == "비치중" }
        if (stocked.isEmpty()) {
            Toast.makeText(this, "비치 중인 시약이 없습니다.", Toast.LENGTH_SHORT).show()
            fetchChemicals()
            resumeScanning(500)
            return
        }
        val labels = stocked.map { chem ->
            "${chem.name} · ${chem.shelf_row ?: "-"}행 ${chem.shelf_col ?: "-"}열"
        }
        AlertDialog.Builder(this)
            .setTitle("반출할 시약 직접 선택")
            .setItems(labels.toTypedArray()) { _, which ->
                requestScanOut(stocked[which].name, scanner.aggregatedText, scanner.activeBarcode, null)
            }
            .setNegativeButton("취소") { _, _ -> resumeScanning() }
            .setOnCancelListener { resumeScanning() }
            .show()
    }

    private fun resumeScanning(cooldownMs: Long = 0L) {
        isScanned = false
        scanner.resume(cooldownMs)
    }

    // ---------- 결과 카드 ----------

    private suspend fun showCheckoutResult(chemical: ChemicalData) {
        val parentShelf = try {
            NetworkClient.api.getShelves().find { it.id == chemical.shelf_id }?.parent_shelf
        } catch (e: Exception) {
            null
        }
        val shelfDesc =
            "선반 ${parentShelf ?: "?"} · ${chemical.shelf_row ?: 0}행 ${chemical.shelf_col ?: 0}열"

        binding.scanResultEmpty.visibility = View.GONE
        binding.btnManualSelect.visibility = View.GONE
        binding.scanResultActive.visibility = View.VISIBLE
        binding.resultBadge.visibility = View.VISIBLE

        val formula = chemical.formula?.let { " ($it)" } ?: ""
        binding.scannedChemicalName.text = "${chemical.name}$formula"

        val prefs = getSharedPreferences("smart_shelf", Context.MODE_PRIVATE)
        val nickname = prefs.getString("nickname", "연구원")
        val timeString = chemical.time_out?.takeIf { it.length >= 16 }?.substring(11, 16) ?: ""
        binding.scannedOperatorInfo.text = "반출자 : $nickname · 오늘 $timeString"
        binding.scannedCheckoutDetails.text = "보관 위치였던 ${shelfDesc}이 비워졌습니다"

        // 잠시 후 다음 스캔을 위해 초기화
        lifecycleScope.launch {
            delay(5000)
            resetScanState()
        }
    }

    private fun resetScanState() {
        binding.scanResultActive.visibility = View.GONE
        binding.resultBadge.visibility = View.GONE
        binding.scanResultEmpty.visibility = View.VISIBLE
        binding.btnManualSelect.visibility = View.GONE
        fetchChemicals()
        resumeScanning()
    }

    private class SearchResultsAdapter(private val onClick: (ChemicalData) -> Unit) :
        RecyclerView.Adapter<SearchResultsAdapter.ViewHolder>() {

        private var items = listOf<ChemicalData>()

        fun updateData(newItems: List<ChemicalData>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ItemChemicalSearchBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val formula = item.formula?.let { " ($it)" } ?: ""
            holder.b.chemSearchName.text = "${item.name}$formula"
            holder.b.chemSearchDetails.text =
                "잔량 ${item.weight} kg · 유통기한 ${item.expiration_date ?: "-"}"
            holder.b.chemSearchLocation.text =
                "${item.shelf_row ?: "-"}행 ${item.shelf_col ?: "-"}열"
            holder.b.root.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(val b: ItemChemicalSearchBinding) : RecyclerView.ViewHolder(b.root)
    }
}
