package at.backend.leadingstock.application.filter

import at.backend.leadingstock.domain.LeadingStockSnapshot

interface StockFilter {
    val name: String
    fun filter(stock: LeadingStockSnapshot): Boolean
    fun evaluate(stock: LeadingStockSnapshot): FilterEvaluationResult
}
