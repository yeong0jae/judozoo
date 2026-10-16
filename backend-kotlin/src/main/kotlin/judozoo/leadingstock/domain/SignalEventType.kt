package judozoo.leadingstock.domain

/** 적재 대상 종목 시그널 전이 종류. DB에는 제거된 종류(돌파·임박·종목 반등·꺾임)의 옛 행이 남아 있어 컬럼은 글자로 둔다. */
enum class SignalEventType {
    /** 1분 거래대금 배율이 임계를 처음 넘긴 순간 */
    VOLUME_SPIKE,
}
