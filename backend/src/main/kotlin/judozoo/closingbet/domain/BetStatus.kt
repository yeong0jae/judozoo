package judozoo.closingbet.domain

enum class BetStatus(val value: String) {
    OPEN("open"),
    FILLED("filled"),
    SETTLED("settled"),
    VOID("void"),
}
