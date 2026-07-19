package com.example.cameramessage

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.cameramessage.databinding.ActivityShelfManageBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ShelfManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShelfManageBinding
    private var pollJob: Job? = null
    private var selectedUnregisteredId: String? = null
    private lateinit var exceptionHelper: ExceptionDialogHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShelfManageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        exceptionHelper = ExceptionDialogHelper(this)

        setupNavigations()
    }

    override fun onResume() {
        super.onResume()
        startPolling()
        exceptionHelper.startPollingAlerts()
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
        exceptionHelper.stopPollingAlerts()
    }

    private fun setupNavigations() {
        binding.navCheckin.setOnClickListener {
            startActivity(Intent(this, CheckinActivity::class.java))
            finish()
        }

        binding.navCheckout.setOnClickListener {
            startActivity(Intent(this, CheckoutActivity::class.java))
            finish()
        }

        binding.navShelf.setOnClickListener {
            // Already here
        }
        
        binding.btnAddShelf.setOnClickListener {
            Toast.makeText(this, "새 선반 추가 기능은 웹 관리자 페이지를 이용해 주세요.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startPolling() {
        pollJob = lifecycleScope.launch {
            while (true) {
                try {
                    val shelves = NetworkClient.api.getShelves()
                    val configs = NetworkClient.api.getShelfConfigs()
                    
                    runOnUiThread {
                        updateUnregisteredStrip(shelves)
                        renderShelfGrids(configs, shelves)
                    }
                } catch (e: Exception) {
                    // ignore network issue
                }
                delay(3000)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
    }

    private fun updateUnregisteredStrip(shelves: List<ShelfDevice>) {
        val unregistered = shelves.filter { it.status == "unregistered" }
        if (unregistered.isEmpty()) {
            binding.unregisteredStripCard.visibility = View.GONE
            selectedUnregisteredId = null
            return
        }

        binding.unregisteredStripCard.visibility = View.VISIBLE
        binding.unregisteredDevicesLayout.removeAllViews()

        unregistered.forEach { device ->
            val textView = TextView(this).apply {
                text = "${device.id}\n(배터리 ${device.battery}%)"
                gravity = Gravity.CENTER
                setPadding(16, 12, 16, 12)
                textSize = 13sp
                setTextColor(if (selectedUnregisteredId == device.id) 0xFFFFFFFF.toInt() else 0xFF1E2A56.toInt())
                setBackgroundResource(if (selectedUnregisteredId == device.id) R.drawable.card_primary_bg else R.drawable.card_white_bg)
                clickable = true
                focusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = 16
                }
                setOnClickListener {
                    selectedUnregisteredId = if (selectedUnregisteredId == device.id) null else device.id
                    updateUnregisteredStrip(shelves)
                }
            }
            binding.unregisteredDevicesLayout.addView(textView)
        }
    }

    private fun renderShelfGrids(configs: List<ShelfConfig>, shelves: List<ShelfDevice>) {
        binding.shelvesContainer.removeAllViews()

        configs.forEach { config ->
            // Create Shelf Container Card
            val cardLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.card_white_bg)
                padding = 24
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 24
                }
            }

            // Shelf Header Layout
            val headerLayout = RelativeLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 16
                }
            }

            val titleLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

            val titleText = TextView(this).apply {
                text = "선반 ${config.id}"
                textSize = 18sp
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(0xFF1E2A56.toInt())
            }
            titleLayout.addView(titleText)

            val specsText = TextView(this).apply {
                text = "${config.rows}행 × ${config.cols}열"
                textSize = 12sp
                setTextColor(0xFF8B97BE.toInt())
            }
            titleLayout.addView(specsText)

            headerLayout.addView(titleLayout)

            // Add Row button on header
            val addRowBtn = Button(this).apply {
                text = "+ 행 추가"
                textSize = 12sp
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF3D6BF5.toInt())
                layoutParams = RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.WRAP_CONTENT,
                    RelativeLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_END)
                    addRule(RelativeLayout.CENTER_VERTICAL)
                }
                setOnClickListener {
                    lifecycleScope.launch {
                        try {
                            NetworkClient.api.updateShelfConfig(config.id, mapOf("rows" to config.rows + 1))
                            Toast.makeText(this@ShelfManageActivity, "행이 추가되었습니다.", Toast.LENGTH_SHORT).show()
                            startPolling()
                        } catch (e: Exception) {
                            Toast.makeText(this@ShelfManageActivity, "수정 실패", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            headerLayout.addView(addRowBtn)
            cardLayout.addView(headerLayout)

            // Shelf Grid Layout
            val gridLayout = GridLayout(this).apply {
                columnCount = config.cols
                rowCount = config.rows
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setBackgroundColor(0xFFF2F5FF.toInt())
                padding = 12
            }

            for (r in 1..config.rows) {
                for (c in 1..config.cols) {
                    val device = shelves.find { 
                        it.parent_shelf == config.id && it.row == r && it.col == c && it.status == "registered" 
                    }

                    val cellView = if (device != null) {
                        // Occupied cell layout
                        val layout = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(12, 12, 12, 12)
                            setBackgroundResource(if (device.led_on) R.drawable.banner_bg else R.drawable.card_white_bg)
                            layoutParams = GridLayout.LayoutParams().apply {
                                width = 0
                                height = GridLayout.LayoutParams.WRAP_CONTENT
                                columnSpec = GridLayout.spec(c - 1, 1f)
                                rowSpec = GridLayout.spec(r - 1, 1f)
                                setMargins(6, 6, 6, 6)
                            }
                        }

                        val cellHeader = RelativeLayout(this)
                        val nameTv = TextView(this).apply {
                            text = device.name ?: "${config.id}${r}-${c}"
                            textSize = 11sp
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setTextColor(0xFF8B97BE.toInt())
                        }
                        cellHeader.addView(nameTv)

                        val battTv = TextView(this).apply {
                            text = "🔋${device.battery}%"
                            textSize = 10sp
                            setTextColor(0xFF8B97BE.toInt())
                            layoutParams = RelativeLayout.LayoutParams(
                                RelativeLayout.LayoutParams.WRAP_CONTENT,
                                RelativeLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                addRule(RelativeLayout.ALIGN_PARENT_END)
                            }
                        }
                        cellHeader.addView(battTv)
                        layout.addView(cellHeader)

                        val idTv = TextView(this).apply {
                            text = device.id
                            textSize = 12sp
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setTextColor(0xFF1E2A56.toInt())
                            setPadding(0, 4, 0, 4)
                        }
                        layout.addView(idTv)

                        val cellFooter = RelativeLayout(this)
                        val weightTv = TextView(this).apply {
                            text = "${device.weight} kg"
                            textSize = 11sp
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setTextColor(0xFF3D6BF5.toInt())
                        }
                        cellFooter.addView(weightTv)

                        val ledBtn = ImageView(this).apply {
                            setImageResource(android.R.drawable.btn_star_big_off)
                            layoutParams = RelativeLayout.LayoutParams(24, 24).apply {
                                addRule(RelativeLayout.ALIGN_PARENT_END)
                            }
                            setOnClickListener {
                                lifecycleScope.launch {
                                    try {
                                        NetworkClient.api.setLedState(
                                            device.id,
                                            ShelfLedUpdate(
                                                led_on = !device.led_on,
                                                led_message = if (!device.led_on) "App manual toggle" else ""
                                            )
                                        )
                                        startPolling()
                                    } catch (e: Exception) {
                                        Toast.makeText(this@ShelfManageActivity, "LED 제어 실패", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                        cellFooter.addView(ledBtn)
                        layout.addView(cellFooter)

                        layout
                    } else {
                        // Empty cell layout
                        val layout = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            setPadding(12, 16, 12, 16)
                            setBackgroundResource(R.drawable.card_white_bg)
                            layoutParams = GridLayout.LayoutParams().apply {
                                width = 0
                                height = GridLayout.LayoutParams.WRAP_CONTENT
                                columnSpec = GridLayout.spec(c - 1, 1f)
                                rowSpec = GridLayout.spec(r - 1, 1f)
                                setMargins(6, 6, 6, 6)
                            }
                            clickable = true
                            focusable = true
                        }

                        val addIcon = TextView(this).apply {
                            text = "+"
                            textSize = 20sp
                            setTextColor(0xFF8B97BE.toInt())
                        }
                        layout.addView(addIcon)

                        val emptyText = TextView(this).apply {
                            text = "빈 슬롯"
                            textSize = 11sp
                            setTextColor(0xFF8B97BE.toInt())
                        }
                        layout.addView(emptyText)

                        layout.setOnClickListener {
                            if (selectedUnregisteredId == null) {
                                Toast.makeText(
                                    this@ShelfManageActivity,
                                    "먼저 상단의 미등록 기기를 선택하세요.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                showRegistrationDialog(config.id, r, c, selectedUnregisteredId!!)
                            }
                        }
                        layout
                    }
                    gridLayout.addView(cellView)
                }
            }
            cardLayout.addView(gridLayout)
            binding.shelvesContainer.addView(cardLayout)
        }
    }

    private fun showRegistrationDialog(shelfId: String, row: Int, col: Int, deviceId: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_wrong_location, null)
        val titleTv: TextView = dialogView.findViewById(R.id.dialogAlertTitle) ?: TextView(this)
        val msgTv: TextView = dialogView.findViewById(R.id.dialogWarningMessage)
        val btnCancel: Button = dialogView.findViewById(R.id.btnMoveToDesignated)
        val btnSubmit: Button = dialogView.findViewById(R.id.btnUpdateLocation)

        // Reuse layout for registration
        titleTv.text = "기기 등록 — $deviceId"
        msgTv.text = "선반 $shelfId · ${row}행 ${col}열에 이 기기를 연결하시겠습니까?\n기기 이름을 정해주세요."

        // Let's dynamically inject an EditText to name the slot
        val container = dialogView as LinearLayout
        val nameInput = EditText(this).apply {
            hint = "기기 식별 이름 (예: 수납칸 ${shelfId}${row * 3 - (3 - col)})"
            setText("수납칸 ${shelfId}${row * 3 - (3 - col)}")
            setPadding(16, 12, 16, 12)
            setBackgroundResource(R.drawable.card_white_bg)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16
            }
        }
        container.addView(nameInput, 3)

        btnCancel.text = "취소"
        btnSubmit.text = "등록"

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSubmit.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "이름을 입력해 주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                try {
                    NetworkClient.api.registerShelf(
                        deviceId,
                        ShelfRegister(name, shelfId, row, col)
                    )
                    Toast.makeText(this@ShelfManageActivity, "기기가 등록되었습니다.", Toast.LENGTH_SHORT).show()
                    selectedUnregisteredId = null
                    dialog.dismiss()
                    startPolling()
                } catch (e: Exception) {
                    Toast.makeText(this@ShelfManageActivity, "등록 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()
    }
}
