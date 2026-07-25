package com.example.cameramessage

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * app-exception-modals (design-spec §3.11)
 * GET /api/chemicals/alerts 를 폴링하여 3종 예외 모달을 Modal/App 스타일로 표시한다.
 *  1) 반출 스캔 미완료 (danger / clipboard-list)
 *  2) 유통기한 경과 (warning / clock-alert)
 *  3) 인접 보관 위험 (danger / shield-alert)
 */
class ExceptionDialogHelper(private val activity: Activity) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null

    // 같은 알림을 반복해서 띄우지 않기 위한 기록
    private val shownAlertIds = mutableSetOf<String>()

    fun startPollingAlerts() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                delay(5000)
                try {
                    val alerts = NetworkClient.api.getAlerts()
                    handleAlerts(alerts)
                } catch (e: Exception) {
                    // 네트워크 오류 무시
                }
            }
        }
    }

    fun stopPollingAlerts() {
        pollJob?.cancel()
    }

    private fun handleAlerts(alerts: ChemicalAlerts) {
        // 1. 반출 스캔 미완료
        if (alerts.unscanned_checkouts.isNotEmpty()) {
            val names = alerts.unscanned_checkouts.map { it.chemical_name }
            val alertId = "unscanned_" + names.sorted().joinToString("_")
            if (shownAlertIds.add(alertId)) {
                showUnscannedCheckoutDialog(names)
            }
        }

        // 2. 유통기한 경과 (추후 구현 예정으로 알림 팝업 트리거 일시 중단)
        /*
        val expired = alerts.expired_chemicals.firstOrNull { it.days_over > 0 }
        if (expired != null) {
            val alertId = "expired_${expired.chemical_id}"
            if (shownAlertIds.add(alertId)) {
                showExpiredWarningDialog(expired)
            }
        }
        */

        // 3. 인접 보관 위험
        if (alerts.co_storage_warnings.isNotEmpty()) {
            val co = alerts.co_storage_warnings.first()
            val alertId = "costorage_${co.chemical_1_id}_${co.chemical_2_id}"
            if (shownAlertIds.add(alertId)) {
                showCoStorageWarningDialog(co)
            }
        }
    }

    private fun showUnscannedCheckoutDialog(chemicalNames: List<String>) {
        AppModal.show(
            activity, AppModal.Tone.DANGER, R.drawable.ic_clipboard_list,
            "반출 스캔 미완료 알림",
            "선반에서 ${chemicalNames.size}개 시약의 회수가 감지되었지만 " +
                    "반출 스캔이 완료되지 않았습니다.\n${chemicalNames.joinToString(" · ")}",
            "나중에 하기", "지금 스캔하기",
            onPrimary = {
                if (activity !is CheckoutActivity) {
                    activity.startActivity(Intent(activity, CheckoutActivity::class.java))
                }
            }
        )
    }

    private fun showExpiredWarningDialog(expired: ExpiredChemical) {
        AppModal.show(
            activity, AppModal.Tone.WARNING, R.drawable.ic_clock_alert,
            "유통기한 경과 시약",
            "${expired.chemical_name}의 유통기한(${expired.expiration_date})이 " +
                    "${expired.days_over}일 경과했습니다.\n사용을 중단하고 폐기 절차를 권장합니다.",
            "그래도 반출", "폐기 등록",
            onPrimary = {
                Toast.makeText(activity, "폐기 절차가 접수되었습니다.", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun showCoStorageWarningDialog(warning: CoStorageWarning) {
        AppModal.show(
            activity, AppModal.Tone.DANGER, R.drawable.ic_shield_alert,
            "함께 두면 위험한 시약",
            "${warning.message}\n안전한 보관 위치를 다시 안내해드릴게요.",
            "무시하고 보관", "안전 위치 안내",
            onPrimary = {
                Toast.makeText(activity, "웹 대시보드에서 추천 보관 위치를 확인해 주세요.", Toast.LENGTH_LONG).show()
            }
        )
    }
}
