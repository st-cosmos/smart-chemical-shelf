package com.example.cameramessage

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
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

class CheckinActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCheckinBinding
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    private var activeSessionJob: Job? = null
    private var isScanned = false
    private var currentUser: String = ""
    private var recommendedShelfId: String? = null
    private var recommendedShelfDesc: String = ""

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

        binding.btnBack.setOnClickListener {
            onBackPressed()
        }

        binding.btnCancelSession.setOnClickListener {
            cancelCheckinSession()
        }

        setupNavigations()

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
                cameraProvider.bindToLifecycle(this, selector, preview, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, "카메라 구동 실패: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupNavigations() {
        binding.navCheckin.setOnClickListener {
            // Already here
        }

        binding.navCheckout.setOnClickListener {
            startActivity(Intent(this, CheckoutActivity::class.java))
            finish()
        }

        binding.navShelf.setOnClickListener {
            startActivity(Intent(this, ShelfManageActivity::class.java))
            finish()
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
        // Simple client-side filters for speed
        val keywords = listOf("에탄올", "메탄올", "아세톤", "황산", "시안", "ETHANOL", "METHANOL", "ACETONE", "SULFURIC", "CYANIDE")
        val upperText = text.uppercase()
        val hasKeyword = keywords.any { upperText.contains(it) }

        if (!hasKeyword) return

        isScanned = true
        lifecycleScope.launch {
            try {
                val response = NetworkClient.api.scanIn(ScanInRequest(ocr_text = text, username = currentUser))
                if (response.status == "success") {
                    recommendedShelfId = response.recommended_shelf
                    recommendedShelfDesc = response.recommended_shelf_desc
                    
                    runOnUiThread {
                        showScanResult(response)
                        startSessionPolling()
                    }
                } else {
                    isScanned = false
                }
            } catch (e: Exception) {
                isScanned = false
                Toast.makeText(this@CheckinActivity, "스캔 연동 실패", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showScanResult(response: ScanInResponse) {
        binding.scanResultEmpty.visibility = View.GONE
        binding.scanResultActive.visibility = View.VISIBLE
        
        binding.scannedChemicalName.text = response.chemical_name
        binding.scannedHistoryType.text = if (response.has_history) "기존 보유 시약 · 재반입" else "신규 시약 등록"
        binding.scannedLocationDesc.text = if (response.has_history) {
            "지정 위치: ${response.recommended_shelf_desc} — LED 점등됨"
        } else {
            "선반의 원하는 빈 슬롯에 시약을 내려놓아주세요."
        }

        binding.placementStatusIcon.setImageResource(android.R.drawable.stat_sys_download)
        binding.placementStatusText.text = "시약을 선반의 지정된 위치에 내려놓아 주세요"
    }

    private fun startSessionPolling() {
        activeSessionJob?.cancel()
        activeSessionJob = lifecycleScope.launch {
            while (true) {
                try {
                    val session = NetworkClient.api.getCheckinSession()
                    
                    // If session is no longer active, it means checkin completed!
                    if (!session.active) {
                        // Let's verify if chemical was registered
                        val chemicals = NetworkClient.api.getChemicals()
                        // Find latest chem registered by current user
                        val latestChem = chemicals.filter { it.name == session.chemical_name }
                            .maxByOrNull { it.time_in ?: "" }
                            
                        if (latestChem != null) {
                            runOnUiThread {
                                handleCheckinComplete(latestChem)
                            }
                        } else {
                            runOnUiThread {
                                resetScanState()
                            }
                        }
                        break
                    } else {
                        runOnUiThread {
                            binding.placementStatusText.text = "시약 안착 대기 중... (${session.time_left.toInt()}초 남음)"
                        }
                    }
                } catch (e: Exception) {
                    // ignore network error during poll
                }
                delay(1000)
            }
        }
    }

    private fun handleCheckinComplete(chemical: ChemicalData) {
        activeSessionJob?.cancel()
        
        val placedShelfId = chemical.shelf_id ?: ""
        val placedShelfDesc = "선반 ${placedShelfId.replace("SHELF-", "").substring(0, 1)} · ${chemical.shelf_row}행 ${chemical.shelf_col}열"

        // Check if placed in wrong spot (if it had history)
        if (recommendedShelfId != null && placedShelfId != recommendedShelfId) {
            showWrongLocationDialog(chemical, placedShelfDesc)
        } else {
            // Success placement
            binding.placementStatusIcon.setImageResource(android.R.drawable.checkbox_on_background)
            binding.placementStatusText.text = "등록 완료\n지정 위치($placedShelfDesc) 안착 확인됨"
            
            lifecycleScope.launch {
                delay(3000)
                resetScanState()
            }
        }
    }

    private fun showWrongLocationDialog(chemical: ChemicalData, placedShelfDesc: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_wrong_location, null)
        val msgTv: TextView = dialogView.findViewById(R.id.dialogWarningMessage)
        val btnMove: Button = dialogView.findViewById(R.id.btnMoveToDesignated)
        val btnKeep: Button = dialogView.findViewById(R.id.btnUpdateLocation)

        msgTv.text = "${chemical.name}의 지정 위치는 ${recommendedShelfDesc}입니다.\n현재 ${placedShelfDesc}에 안착이 감지되었습니다."

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnMove.setOnClickListener {
            // User wants to move it.
            // Dismiss dialog, and reset scan state so they can place it again (which triggers new checkin session).
            dialog.dismiss()
            resetScanState()
        }

        btnKeep.setOnClickListener {
            // User wants to keep it at this new location.
            // The DB is already registered here, so just show success and close.
            dialog.dismiss()
            
            binding.placementStatusIcon.setImageResource(android.R.drawable.checkbox_on_background)
            binding.placementStatusText.text = "위치 변경 완료\n새 위치($placedShelfDesc)에 등록되었습니다."
            
            lifecycleScope.launch {
                delay(3000)
                resetScanState()
            }
        }

        dialog.show()
    }

    private fun cancelCheckinSession() {
        lifecycleScope.launch {
            try {
                NetworkClient.api.cancelCheckinSession()
            } catch (e: Exception) {}
            resetScanState()
        }
    }

    private fun resetScanState() {
        isScanned = false
        recommendedShelfId = null
        recommendedShelfDesc = ""
        activeSessionJob?.cancel()
        
        binding.scanResultActive.visibility = View.GONE
        binding.scanResultEmpty.visibility = View.VISIBLE
    }
}
