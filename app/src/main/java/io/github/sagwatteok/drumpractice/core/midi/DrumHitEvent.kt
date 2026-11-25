package io.github.sagwatteok.drumpractice.core.midi

enum class Articulation { STANDARD, SIDE_STICK, RIMSHOT, PEDAL, OPEN, CLOSED }

enum class Drum(val label: String, val defaultNote: Int?, val articulation: Articulation = Articulation.STANDARD) {
    KICK("킥", 36),
    SNARE("스네어", 38),
    SIDE_STICK("사이드 스틱", 37, Articulation.SIDE_STICK),
    RIMSHOT("스네어 림샷", null, Articulation.RIMSHOT),
    HI_HAT("하이햇", null),
    HI_HAT_PEDAL("하이햇 페달", 44, Articulation.PEDAL),
    OPEN_HI_HAT("오픈 하이햇", 46, Articulation.OPEN),
    CLOSED_HI_HAT("클로즈드 하이햇", 42, Articulation.CLOSED),
    TOM_1("탐 1", 50),
    TOM_2("탐 2", 47),
    FLOOR_TOM("플로어 탐", 45),
    CRASH("크래시", 49),
    RIDE("라이드", 51),
}

data class DrumHitEvent(
    val noteNumber: Int,
    val velocity: Int,
    val channel: Int,
    val timestampNanos: Long,
    val drumType: Drum?,
    val articulation: Articulation?,
    val receivedAtNanos: Long,
)
