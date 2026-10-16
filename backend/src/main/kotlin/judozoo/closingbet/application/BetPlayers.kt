package judozoo.closingbet.application

import judozoo.closingbet.domain.BetPlayer
import judozoo.closingbet.domain.BetPlayerRepository
import judozoo.closingbet.domain.Nicknames
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * 처음 베팅하는 사람에게 랜덤 닉네임을 붙인다. 동시에 같은 닉네임이 나오면 한 번 더 뽑는다.
 *
 * 새 트랜잭션에서 넣는다 — 유니크 키에 걸린 실패가 바깥 베팅 트랜잭션까지 되돌리지 않게.
 */
@Component
class BetPlayers(private val players: BetPlayerRepository, transactions: PlatformTransactionManager) {
    private val fresh = TransactionTemplate(transactions).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    fun ensure(userId: Long, at: LocalDateTime): BetPlayer {
        players.findById(userId).orElse(null)?.let { return it }
        repeat(3) {
            try {
                return fresh.execute { players.saveAndFlush(BetPlayer(userId, Nicknames.random(players.nicknames(), Random.Default), at)) }!!
            } catch (e: DataIntegrityViolationException) {
                players.findById(userId).orElse(null)?.let { return it }
            }
        }
        throw IllegalStateException("닉네임을 만들지 못했다")
    }
}
