package com.example.cameramessage

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Base64
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * 스캔 프레임을 JPEG(base64)로 간헐 보관한다.
 * LLM 비전 매칭(/match/llm)과 용량 추정(/estimate-capacity)에 라벨 '사진'을
 * 첨부하기 위한 것 — ML Kit OCR 이 깨뜨린 글자를 서버의 비전 모델이 원본
 * 이미지에서 직접 읽을 수 있게 한다.
 */
class ScanFrameCache {

    companion object {
        private const val MIN_INTERVAL_MS = 1200L  // 변환 부하 제한 (프레임마다 하지 않음)
        private const val MAX_DIM = 1280           // 전송 크기 절약용 축소 상한 (px)
        private const val JPEG_QUALITY = 72
    }

    @Volatile private var lastAt = 0L

    /** 가장 최근에 캡처된 라벨 프레임 (JPEG base64, 없으면 null) */
    @Volatile var latestBase64: String? = null
        private set

    /**
     * 분석 스레드에서 imageProxy.close() 전에 호출한다.
     * 글자가 보이는 프레임만, MIN_INTERVAL_MS 에 한 번만 저장한다.
     */
    fun offer(imageProxy: ImageProxy, hasText: Boolean) {
        if (!hasText) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAt < MIN_INTERVAL_MS) return
        lastAt = now
        try {
            var bmp = imageProxy.toBitmap()
            val deg = imageProxy.imageInfo.rotationDegrees
            val scale = MAX_DIM.toFloat() / maxOf(bmp.width, bmp.height)
            val m = Matrix()
            if (scale < 1f) m.postScale(scale, scale)
            if (deg != 0) m.postRotate(deg.toFloat())
            if (!m.isIdentity) {
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            latestBase64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            // 캡처 실패는 무시 — 텍스트 매칭 경로는 그대로 동작한다
        }
    }

    fun clear() {
        latestBase64 = null
    }
}
