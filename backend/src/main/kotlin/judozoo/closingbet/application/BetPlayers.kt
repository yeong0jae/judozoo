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
