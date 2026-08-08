package com.example.cameramessage

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.example.cameramessage.databinding.DialogAppModalBinding

/**
 * Modal/App (design-spec §1.4) 공통 모달.
 * 톤(primary/success/warning/danger)에 따라 IconWrap 배경(soft), 아이콘, Primary 버튼 색을 지정한다.
 */
object AppModal {

    enum class Tone(val colorRes: Int, val softColorRes: Int) {
        PRIMARY(R.color.primary, R.color.primary_soft),
        SUCCESS(R.color.success, R.color.success_soft),
        WARNING(R.color.warning, R.color.warning_soft),
        DANGER(R.color.danger, R.color.danger_soft)
    }

    fun show(
        activity: Activity,
        tone: Tone,
        @DrawableRes iconRes: Int,
        title: String,
        message: String,
        secondaryText: String?,
        primaryText: String,
        cancelable: Boolean = false,
        autoDismissMs: Long? = null,
        dateBadge: String? = null,        // 지정 시 메시지 아래에 톤 색 pill 배지로 강조 표시
        inputTextHint: String? = null,    // 지정 시 Input/App 스타일 입력 필드 표시
        inputType: Int? = null,
        inputErrorText: String? = null,   // onInputSubmit 이 false 를 반환하면 표시할 오류 문구
        onSecondary: (() -> Unit)? = null,
        onPrimary: (() -> Unit)? = null,
        onInputSubmit: ((String) -> Boolean)? = null  // true 반환 시에만 모달을 닫는다
    ): AlertDialog? {
        if (activity.isFinishing || activity.isDestroyed) return null

        val binding = DialogAppModalBinding.inflate(LayoutInflater.from(activity))
        val toneColor = ContextCompat.getColor(activity, tone.colorRes)
        val softColor = ContextCompat.getColor(activity, tone.softColorRes)

        binding.modalIconWrap.backgroundTintList = ColorStateList.valueOf(softColor)
        binding.modalIcon.setImageResource(iconRes)
        binding.modalIcon.imageTintList = ColorStateList.valueOf(toneColor)
        binding.modalTitle.text = title
        binding.modalMessage.text = message
        binding.btnModalPrimary.text = primaryText
        binding.btnModalPrimary.backgroundTintList = ColorStateList.valueOf(toneColor)

        if (secondaryText == null) {
            // 버튼 1개 변형: Secondary 숨김 → Primary 전체 폭
            binding.btnModalSecondary.visibility = View.GONE
        } else {
            binding.btnModalSecondary.text = secondaryText
        }

        if (dateBadge != null) {
            binding.modalDatePill.visibility = View.VISIBLE
            binding.modalDatePill.backgroundTintList = ColorStateList.valueOf(softColor)
            binding.modalDateIcon.imageTintList = ColorStateList.valueOf(toneColor)
            binding.modalDateText.setTextColor(toneColor)
            binding.modalDateText.text = dateBadge
        }

        if (inputTextHint != null) {
            binding.modalInput.visibility = View.VISIBLE
            binding.modalInput.hint = inputTextHint
            if (inputType != null) {
                binding.modalInput.inputType = inputType
            }
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(binding.root)
            .setCancelable(cancelable)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // 자동 닫힘 타이머 — 버튼을 누르면 취소된다
        var autoRunnable: Runnable? = null
        if (autoDismissMs != null) {
            autoRunnable = Runnable {
                if (dialog.isShowing && !activity.isFinishing && !activity.isDestroyed) {
                    dialog.dismiss()
                    onPrimary?.invoke()
                }
            }
            binding.root.postDelayed(autoRunnable, autoDismissMs)
        }

        binding.btnModalSecondary.setOnClickListener {
            autoRunnable?.let { r -> binding.root.removeCallbacks(r) }
            dialog.dismiss()
            onSecondary?.invoke()
        }
        binding.btnModalPrimary.setOnClickListener {
            // 입력 모달: 검증 통과(true)일 때만 닫는다
            if (onInputSubmit != null) {
                val ok = onInputSubmit(binding.modalInput.text.toString().trim())
                if (!ok) {
                    binding.modalInput.error = inputErrorText ?: "입력 형식을 확인해 주세요"
                    return@setOnClickListener
                }
            }
            autoRunnable?.let { r -> binding.root.removeCallbacks(r) }
            dialog.dismiss()
            onPrimary?.invoke()
        }

        if (inputTextHint != null) {
            binding.modalInput.requestFocus()
            dialog.window?.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            )
        }

        dialog.show()
        // parent 없이 inflate 하면 루트의 layout_width 가 무시되므로 창 크기를 직접 지정
        dialog.window?.setLayout(activity.dp(360), ViewGroup.LayoutParams.WRAP_CONTENT)
        return dialog
    }
}
