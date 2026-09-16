package net.duhowpi.nobita.capture

sealed interface CaptureState {
    data object Idle : CaptureState
    data object Connecting : CaptureState
    data class Capturing(val startedAt: Long, val target: String) : CaptureState
    data object StoppingStream : CaptureState
    data object FinalizingRaw : CaptureState
    data object Converting : CaptureState
    data object ExportPending : CaptureState
    data class Completed(val output: String) : CaptureState
    data class Failed(val stage: String, val message: String) : CaptureState
}
