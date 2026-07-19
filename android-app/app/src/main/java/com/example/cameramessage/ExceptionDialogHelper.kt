package com.example.cameramessage

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.example.cameramessage.NetworkClient
import kotlinx.coroutines.*

class ExceptionDialogHelper(private val activity: Activity) {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var pollJob: Job? = null
    
    // Set to keep track of shown alerts so we don't show duplicate dialogs
    private val shownAlertIds = mutableSetOf<String>()

    fun startPollingAlerts() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                delay(5000) // check every 5s
                try {
                    val alerts = NetworkClient.api.getAlerts()
                    
                    // 1. Unscanned Checkouts check
                    if (alerts.unscanned_checkouts.isNotEmpty()) {
                        val names = alerts.unscanned_checkouts.map { it.chemical_name }
                        val alertId = "unscanned_" + names.sorted().joinToString("_")
                        if (!shownAlertIds.contains(alertId)) {
                            shownAlertIds.add(alertId)
                            showUnscannedCheckoutDialog(names)
                        }
                    }

                    // 2. Expired Warning check
                    if (alerts.expired_chemicals.isNotEmpty()) {
                        val expired = alerts.expired_chemicals.first { it.days_over > 0 }
                        val alertId = "expired_${expired.chemical_id}"
                        if (!shownAlertIds.contains(alertId)) {
                            shownAlertIds.add(alertId)
                            showExpiredWarningDialog(expired)
                        }
                    }

                    // 3. Co-storage Warning check
                    if (alerts.co_storage_warnings.isNotEmpty()) {
                        val co = alerts.co_storage_warnings.first()
                        val alertId = "costorage_${co.chemical_1_id}_${co.chemical_2_id}"
                        if (!shownAlertIds.contains(alertId)) {
                            shownAlertIds.add(alertId)
                            showCoStorageWarningDialog(co)
                        }
                    }

                } catch (e: Exception) {
                    // ignore network errors
                }
            }
        }
    }

    fun stopPollingAlerts() {
        pollJob?.cancel()
        scope.cancel()
    }

    private fun showUnscannedCheckoutDialog(chemicalNames: List<String>) {
        activity.runOnUiThread {
            val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_exception_alert, null)
            val titleTv: TextView = dialogView.findViewById(R.id.dialogAlertTitle)
            val msgTv: TextView = dialogView.findViewById(R.id.dialogAlertMessage)
            val subTv: TextView = dialogView.findViewById(R.id.dialogAlertSubText)
            val btnNeg: Button = dialogView.findViewById(R.id.btnAlertNegative)
            val btnPos: Button = dialogView.findViewById(R.id.btnAlertPositive)

            titleTv.text = "1. 반출 스캔 미완료"
            msgTv.text = "선반에서 시약 회수가 감지되었지만 반출 스캔이 완료되지 않았습니다."
            subTv.text = chemicalNames.joinToString(" · ")
            subTv.visibility = View.VISIBLE

            btnNeg.text = "나중에 하기"
            btnPos.text = "지금 스캔하기"

            val dialog = AlertDialog.Builder(activity)
                .setView(dialogView)
                .create()

            btnNeg.setOnClickListener { dialog.dismiss() }
            btnPos.setOnClickListener {
                dialog.dismiss()
                activity.startActivity(Intent(activity, CheckoutActivity::class.java))
            }

            dialog.show()
        }
    }

    private fun showExpiredWarningDialog(expired: ExpiredChemical) {
        activity.runOnUiThread {
            val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_exception_alert, null)
            val titleTv: TextView = dialogView.findViewById(R.id.dialogAlertTitle)
            val msgTv: TextView = dialogView.findViewById(R.id.dialogAlertMessage)
            val btnNeg: Button = dialogView.findViewById(R.id.btnAlertNegative)
            val btnPos: Button = dialogView.findViewById(R.id.btnAlertPositive)

            titleTv.text = "2. 유통기한 경고"
            msgTv.text = "${expired.chemical_name}의 유통기한(${expired.expiration_date})이 ${expired.days_over}일 경과했습니다.\n사용을 중단하고 폐기 절차를 권장합니다."

            btnNeg.text = "그래도 반출"
            btnPos.text = "폐기 등록"

            val dialog = AlertDialog.Builder(activity)
                .setView(dialogView)
                .create()

            btnNeg.setOnClickListener { dialog.dismiss() }
            btnPos.setOnClickListener {
                dialog.dismiss()
                Toast.makeText(activity, "폐기 절차가 성공적으로 접수되었습니다.", Toast.LENGTH_SHORT).show()
            }

            dialog.show()
        }
    }

    private fun showCoStorageWarningDialog(warning: CoStorageWarning) {
        activity.runOnUiThread {
            val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_exception_alert, null)
            val titleTv: TextView = dialogView.findViewById(R.id.dialogAlertTitle)
            val msgTv: TextView = dialogView.findViewById(R.id.dialogAlertMessage)
            val btnNeg: Button = dialogView.findViewById(R.id.btnAlertNegative)
            val btnPos: Button = dialogView.findViewById(R.id.btnAlertPositive)

            titleTv.text = "3. 인접 보관 위험"
            msgTv.text = warning.message

            btnNeg.text = "무시하고 보관"
            btnPos.text = "안전 위치 안내"

            val dialog = AlertDialog.Builder(activity)
                .setView(dialogView)
                .create()

            btnNeg.setOnClickListener { dialog.dismiss() }
            btnPos.setOnClickListener {
                dialog.dismiss()
                Toast.makeText(activity, "대시보드에서 추천 적재 위치를 확인해 주세요.", Toast.LENGTH_LONG).show()
            }

            dialog.show()
        }
    }
}
