package at.backend.leadingstock.domain

/** 시장 순매수 시그널의 주체 — 외국인/기관/개인. */
enum class InvestorType {
    FOREIGN,
    INSTITUTION,
    INDIVIDUAL,
}
