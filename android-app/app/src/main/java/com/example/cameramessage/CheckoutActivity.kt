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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * app-checkout / -search (design-spec §3.9, §3.10)
 * 검색바(시약 검색 → 결과 모달: 미니맵 LED 셀 + select-led) + CameraX/OCR → scan-out.
 */
class CheckoutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCheckoutBinding
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

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
                        detectAndTriggerCheckout(text)
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }

    private fun detectAndTriggerCheckout(text: String) {
        val upperText = text.uppercase()
        val hasKeyword = chemicalsList.any { upperText.contains(it.name.uppercase()) } ||
                DEFAULT_KEYWORDS.any { upperText.contains(it) }
        if (!hasKeyword) return

        isScanned = true
        lifecycleScope.launch {
            try {
                val response = NetworkClient.api.scanOut(
                    ScanOutRequest(ocr_text = text, username = currentUser)
                )
                if (response.status == "success") {
                    showCheckoutResult(response.chemical)
                } else {
                    isScanned = false
                }
            } catch (e: Exception) {
                isScanned = false
            }
        }
    }

    private suspend fun showCheckoutResult(chemical: ChemicalData) {
        val parentShelf = try {
            NetworkClient.api.getShelves().find { it.id == chemical.shelf_id }?.parent_shelf
        } catch (e: Exception) {
            null
        }
        val shelfDesc =
            "선반 ${parentShelf ?: "?"} · ${chemical.shelf_row ?: 0}행 ${chemical.shelf_col ?: 0}열"

        binding.scanResultEmpty.visibility = View.GONE
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
        isScanned = false
        binding.scanResultActive.visibility = View.GONE
        binding.resultBadge.visibility = View.GONE
        binding.scanResultEmpty.visibility = View.VISIBLE
        fetchChemicals()
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

    companion object {
        private val DEFAULT_KEYWORDS = listOf(
            "에탄올", "메탄올", "아세톤", "황산", "질산", "염산", "시안", "아세토니트릴", "톨루엔",
            "ETHANOL", "METHANOL", "ACETONE", "SULFURIC", "NITRIC", "CYANIDE", "ACETONITRILE", "TOLUENE"
        )
    }
}
