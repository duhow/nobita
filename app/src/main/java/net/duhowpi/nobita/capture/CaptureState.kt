package net.duhowpi.nobita.capture

sealed interface CaptureState {
    data object Idle : CaptureState
    data object Preparing : CaptureState
    data class Capturing(val startedAt: Long, val target: String) : CaptureState
    data object GeneratingBugreport : CaptureState
    data object Extracting : CaptureState
    data object Parsing : CaptureState
    data object Filtering : CaptureState
    data object WritingPcapng : CaptureState
    data class Completed(val output: String) : CaptureState
    data class Failed(val stage: String, val message: String) : CaptureState
}
