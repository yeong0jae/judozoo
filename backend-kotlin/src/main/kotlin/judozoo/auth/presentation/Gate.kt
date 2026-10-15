package judozoo.auth.presentation

/**
 * 공개 API 허용목록. Python `auth/gate.py`와 **같은 표**다 — 한쪽만 고치면 경로마다 공개 여부가 달라진다.
 *
 * 기본이 차단이다 — 여기 없는 `/api` 경로는 로그인을 요구한다. 등록을 빠뜨려도 노출이 아니라
 * 불편으로 끝난다. 미리보기처럼 일부만 여는 경로는 엔드포인트가 직접 잘라 내려보낸다.
 */
object Gate {
    /** 경로 파라미터는 하나의 세그먼트만 받는다. */
    private const val SEG = "[^/]+"

    private val publicPatterns = listOf(
        // 주도주 후보 **목록만** 공개한다. 종목 상세(일봉·분봉·수급)는 로그인 뒤다.
        "/api/leading-stocks/candidates",
        // 첫 화면 주도주 — 후보 목록에서 추린 것이라 새로 여는 것이 없다
        "/api/leading-stocks/leaders",
        "/api/overseas-leading-stocks/leaders",
        // 주도주 캘린더 — 위 카드가 마감 때 고른 것을 모은 것
        "/api/leader-calendar",
        // 주도주 타임라인 — 같은 주도주를 1분마다 모은 것
        "/api/leader-timeline",
        // 왜 오르나 — 사유 한 줄은 공개. 근거·관련 기사는 엔드포인트가 로그인일 때만 싣는다
        "/api/insight/reasons",
        // 모의 종가베팅 — 판돈·LIVE·목록(등급)·랭킹은 공개. 베팅·닉네임·시장 수급 표(`/market`)는 로그인 뒤다
        "/api/closingbet/now",
        "/api/closingbet/stocks",
        "/api/closingbet/feed",
        "/api/closingbet/rounds/$SEG",
        // 종목 시그널 — 미리보기로 최신 몇 건만, 자르는 건 엔드포인트가 한다. 지수 시그널은 로그인 뒤다.
        "/api/leading-stocks/signal-events",
        // 지지·저항도 같은 방식 — 상위 몇 개만 엔드포인트가 잘라 내려보낸다
        "/api/leading-stocks/breakout-radar",
        "/api/overseas-leading-stocks/candidates",
        // 휴장 배너
        "/api/market/calendar/status",
        // 지수 시세 — 어디서나 얻을 수 있는 정보다
        "/api/market/kospi",
        "/api/market/kosdaq",
        "/api/market/nasdaq/quote",
        "/api/market/macro/quotes",
        // 시세와 차트만 연다. 투자자별 수급(`investor/*`)은 이 서비스가 가공한 값이라 로그인 뒤다.
        "/api/market/$SEG/candles",
        "/api/market/futures/$SEG/quote",
        "/api/market/futures/$SEG/candles",
        // 로그인 흐름 자체
        "/api/auth/.*",
    )

    // 전체를 한 번 더 묶어야 끝의 `/?`가 모든 항목에 걸린다
    private val public = Regex("(?:" + publicPatterns.joinToString("|") { "(?:$it)" } + ")/?")

    /** `/api` 밖(정적 파일·헬스체크)은 이 함수가 판단하지 않는다 — 호출자가 먼저 거른다. */
    fun isPublic(path: String): Boolean = public.matches(path)
}
