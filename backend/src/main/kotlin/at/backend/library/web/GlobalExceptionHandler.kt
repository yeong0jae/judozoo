package at.backend.library.web

import at.backend.trading.domain.AlreadyClosedException
import at.backend.trading.domain.TradingValidationException
import at.backend.library.exception.EntityNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(TradingValidationException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleTradingValidation(ex: TradingValidationException) =
        ApiResponse.error(ex.errorCode.name, 400)

    @ExceptionHandler(AlreadyClosedException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleAlreadyClosed(ex: AlreadyClosedException) =
        ApiResponse.error("ALREADY_CLOSED", 409)

    @ExceptionHandler(EntityNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleEntityNotFound(ex: EntityNotFoundException) =
        ApiResponse.error("NOT_FOUND", 404)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleMethodArgumentNotValid(ex: MethodArgumentNotValidException) =
        ApiResponse.error("INVALID_PARAMETER", 400)
}
