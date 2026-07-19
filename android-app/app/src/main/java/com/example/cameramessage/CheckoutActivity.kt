package com.example.cameramessage

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.cameramessage.databinding.ActivityCheckoutBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class CheckoutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCheckoutBinding
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

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

        binding.btnBack.setOnClickListener {
            onBackPressed()
        }

        binding.btnResetScan.setOnClickListener {
            resetScanState()
        }

        setupNavigations()
        setupSearch()

        if (hasCameraPermission()) startCamera()
        else requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        
        // Fetch chemicals list for search cache
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
                cameraProvider.bindToLifecycle(this, selector, preview, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, "카메라 구동 실패: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupNavigations() {
        binding.navCheckin.setOnClickListener {
            startActivity(Intent(this, CheckinActivity::class.java))
            finish()
        }

        binding.navCheckout.setOnClickListener {
            // Already here
        }

        binding.navShelf.setOnClickListener {
            startActivity(Intent(this, ShelfManageActivity::class.java))
            finish()
        }
    }

    private fun setupSearch() {
        binding.searchResultsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.searchResultsRecyclerView.adapter = SearchResultsAdapter { chem ->
            // Trigger LED
            lifecycleScope.launch {
                try {
                    val response = NetworkClient.api.selectLed(SelectLedRequest(chem_id = chem.id))
                    if (response.status == "success") {
                        showLedGuidanceDialog(chem, response)
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@CheckoutActivity, "LED 명령 실패", Toast.LENGTH_SHORT).show()
                }
            }
            binding.searchEditText.setText("")
            binding.searchResultsRecyclerView.visibility = View.GONE
        }

        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(300)
                    val query = s?.toString()?.trim() ?: ""
                    if (query.length >= 2) {
                        val filtered = chemicalsList.filter { 
                            it.current_status == "비치중" && (
                                it.name.contains(query, ignoreCase = true) ||
                                (it.formula?.contains(query, ignoreCase = true) ?: false)
                            )
                        }
                        runOnUiThread {
                            val adapter = binding.searchResultsRecyclerView.adapter as? SearchResultsAdapter
                            adapter?.updateData(filtered)
                            binding.searchResultsRecyclerView.visibility = if (filtered.isNotEmpty()) View.VISIBLE else View.GONE
                        }
                    } else {
                        runOnUiThread {
                            binding.searchResultsRecyclerView.visibility = View.GONE
                        }
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
            } catch (e: Exception) {}
        }
    }

    private fun showLedGuidanceDialog(chem: ChemicalData, response: SelectLedResponse) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_exception_alert, null)
        val titleTv: TextView = dialogView.findViewById(R.id.dialogAlertTitle)
        val msgTv: TextView = dialogView.findViewById(R.id.dialogAlertMessage)
        val btnNeg: Button = dialogView.findViewById(R.id.btnAlertNegative)
        val btnPos: Button = dialogView.findViewById(R.id.btnAlertPositive)

        titleTv.text = "검색 결과 — LED 안내 중"
        msgTv.text = "${chem.name} (${chem.formula})\n보관 위치: 선반 ${response.parent_shelf} · ${response.row}행 ${response.col}열\n\n해당 선반의 LED가 점등되어 위치를 안내하고 있습니다."

        btnNeg.visibility = View.GONE
        btnPos.text = "확인했어요"

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnPos.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
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
                        detectAndTriggerCheckout(text)
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }

    private fun detectAndTriggerCheckout(text: String) {
        val keywords = listOf("에탄올", "메탄올", "아세톤", "황산", "시안", "ETHANOL", "METHANOL", "ACETONE", "SULFURIC", "CYANIDE")
        val upperText = text.uppercase()
        val hasKeyword = keywords.any { upperText.contains(it) }

        if (!hasKeyword) return

        isScanned = true
        lifecycleScope.launch {
            try {
                val response = NetworkClient.api.scanOut(ScanOutRequest(ocr_text = text, username = currentUser))
                if (response.status == "success") {
                    runOnUiThread {
                        showCheckoutResult(response.chemical)
                    }
                } else {
                    isScanned = false
                }
            } catch (e: Exception) {
                isScanned = false
                Toast.makeText(this@CheckoutActivity, "반출 처리 실패. 재고 여부를 확인하세요.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showCheckoutResult(chemical: ChemicalData) {
        binding.scanResultEmpty.visibility = View.GONE
        binding.scanResultActive.visibility = View.VISIBLE

        binding.scannedChemicalName.text = "${chemical.name} (${chemical.formula ?: ""})"
        
        val timeString = chemical.time_out?.substring(11, 16) ?: ""
        val prefs = getSharedPreferences("smart_shelf", Context.MODE_PRIVATE)
        val nickname = prefs.getString("nickname", "연구원")
        binding.scannedOperatorInfo.text = "반출자 : $nickname · 오늘 $timeString"
        
        val shelfId = chemical.shelf_id ?: ""
        val shelfDesc = "선반 ${shelfId.replace("SHELF-", "").substring(0, 1)} · ${chemical.shelf_row}행 ${chemical.shelf_col}열"
        binding.scannedCheckoutDetails.text = "보관 위치였던 $shelfDesc 이 비워졌습니다"
    }

    private fun resetScanState() {
        isScanned = false
        binding.scanResultActive.visibility = View.GONE
        binding.scanResultEmpty.visibility = View.VISIBLE
        fetchChemicals() // refresh cache
    }

    private class SearchResultsAdapter(private val onClick: (ChemicalData) -> Unit) :
        RecyclerView.Adapter<SearchResultsAdapter.ViewHolder>() {

        private var items = listOf<ChemicalData>()

        fun updateData(newItems: List<ChemicalData>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chemical_search, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.nameTv.text = "${item.name} (${item.formula ?: ""})"
            holder.detailsTv.text = "잔량 ${Math.round(item.weight * 100 / 2.0)}% · 유통기한 ${item.expiration_date ?: "-"}"
            
            val shelfId = item.shelf_id ?: ""
            holder.locationTv.text = "선반 ${shelfId.replace("SHELF-", "").substring(0, 1)} · ${item.shelf_row}행 ${item.shelf_col}열"
            
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameTv: TextView = view.findViewById(R.id.chemSearchName)
            val detailsTv: TextView = view.findViewById(R.id.chemSearchDetails)
            val locationTv: TextView = view.findViewById(R.id.chemSearchLocation)
        }
    }
}
