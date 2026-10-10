package com.dut.runmate.run

import android.location.Location
import com.dut.runmate.data.Checkpoint
import kotlinx.coroutines.flow.MutableStateFlow

/** 服务与 UI 之间的共享状态 */
object RunBus {

    enum class CpState { PENDING, NEAR, IN_ZONE, GRACE, CONFIRMED, MISSED }

    data class CpSnap(
        val cp: Checkpoint,
        val state: CpState,
        val distM: Int,
        val missedCount: Int
    )

    data class Stats(
        val elapsedMs: Long = 0,
        val meters: Double = 0.0,
        val fixes: Int = 0
    ) {
        val pace: String
            get() {
                if (meters < 50) return "--"
                val secPerKm = elapsedMs / 1000.0 / (meters / 1000.0)
                return String.format("%d'%02d\"", (secPerKm / 60).toInt(), (secPerKm % 60).toInt())
            }
        val timeStr: String
            get() {
                val t = elapsedMs / 1000
                return String.format("%d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60)
            }
    }

    data class Summary(
        val confirmed: List<String>,
        val missed: List<String>,
        val passedGps: List<String>,
        val stats: Stats
    )

    data class UiState(
        val running: Boolean = false,
        val fix: Location? = null,
        val gpsAcc: Float? = null,
        val nearest: CpSnap? = null,
        val inZoneName: String? = null,
        val apiDistance: Double? = null,
        val apiErr: String? = null,
        val snaps: List<CpSnap> = emptyList(),
        val stats: Stats = Stats(),
        val summary: Summary? = null,
        /** 手动「立即核对」请求计数：UI 递增，服务轮询循环感知后立即拉取一次 */
        val pollReq: Long = 0L
    )

    val state = MutableStateFlow(UiState())

    fun update(transform: (UiState) -> UiState) {
        state.value = transform(state.value)
    }

    fun resetSession() {
        update { it.copy(summary = null) }
    }
}
