package at.backend.trading.domain

class AlreadyClosedException(id: Long) : RuntimeException("이미 종료된 사이클입니다: $id")
