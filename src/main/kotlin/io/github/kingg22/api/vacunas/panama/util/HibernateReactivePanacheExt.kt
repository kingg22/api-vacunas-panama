package io.github.kingg22.api.vacunas.panama.util

import io.quarkus.hibernate.reactive.panache.kotlin.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.hibernate.reactive.mutiny.Mutiny

/* Inspired on: https://github.com/quarkusio/quarkus/issues/34101#issuecomment-2687147471 */
val panacheLogger = logger("HibernateReactivePanacheExt")

/**
 * Executes a given block within an active transaction using the Mutiny API.
 * Ensures a current transaction exists, passing it to the block for transaction-safe operations.
 *
 * **Important**: If calling suspend functions inside, make sure to use the provided [CoroutineScope] to maintain the Vert.x context.
 * Failure to do so may result in loss of context and transactional consistency.
 *
 * If the current transaction is null, an [IllegalStateException] is thrown.
 *
 * @param block A suspending lambda to be executed within the context of the active transaction.
 * The `block` receives a [Mutiny.Transaction] as a parameter for performing operations.
 * @return The result of the executed block.
 * @throws IllegalStateException If no current transaction is available.
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend inline fun <T> withTransaction(crossinline block: suspend CoroutineScope.(Mutiny.Transaction) -> T): T =
    withVertx(true) { scope ->
        Panache.withTransaction {
            val uniTransaction: Uni<Mutiny.Transaction?> = Panache.currentTransaction()
            // this is CoroutineScope
            uni(scope) {
                try {
                    val transaction: Mutiny.Transaction? = uniTransaction.awaitSuspending()
                    checkNotNull(transaction) { "Current transaction is null when previous call withTransaction" }
                    block(scope, transaction)
                } catch (e: IllegalStateException) {
                    panacheLogger.error("Error in Vertx Mutiny withTransaction", e)
                    // Rethrow to be handled by the caller
                    throw e
                }
            }
        }.awaitSuspending() // awaits the Uni from withTransaction
    }

/**
 * Executes a suspending block of code within the context of a Hibernate Reactive session.
 * The session is managed automatically, ensuring proper resource handling, such as session creation and closure,
 * and any exceptions within the block are propagated to the caller.
 *
 * **Important**: To preserve the Vert.x context, always use the given [CoroutineScope] if making suspend calls inside this block.
 * Losing the scope breaks context-sensitive operations like session management or entity flushing.
 *
 * @param block A suspending block of code that receives a [Mutiny.Session] to perform database operations.
 * @return The result of the executed block.
 * @throws IllegalStateException If an error occurs while initializing the session or during block execution.
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend inline fun <T> withSession(crossinline block: suspend CoroutineScope.(Mutiny.Session) -> T): T =
    withVertx(true) { scope ->
        Panache.withSession {
            val session: Uni<Mutiny.Session> = Panache.getSession()
            // Use the given CoroutineScope
            uni(scope) {
                try {
                    val unwrappedSession = session.awaitSuspending()
                    block(scope, unwrappedSession)
                } catch (e: IllegalStateException) {
                    panacheLogger.error("Error in Vertx Mutiny withSession", e)
                    // Rethrow to be handled by the caller
                    throw e
                }
            }
        }.awaitSuspending() // awaits the Uni from withSession
    }

/**
 * Executes a given block of code within the context of a Hibernate Reactive session and transaction.
 * Provides a safe environment for performing operations requiring a database session and transaction.
 *
 * **Important**: If nested suspend functions are invoked, propagate the provided [CoroutineScope] to preserve Vert.x context.
 * Not doing so can break reactive behavior, particularly for session lifecycle and transaction propagation.
 *
 * @param block A suspending lambda that contains the operations to execute within the session and transaction.
 * The lambda receives a [Mutiny.Session] and [Mutiny.Transaction] as parameters for its operations.
 * @return The result of the executed block.
 * @throws IllegalStateException If the current transaction is null or another issue occurs during execution.
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend inline fun <T> withSessionAndTransaction(
    crossinline block: suspend CoroutineScope.(Mutiny.Session, Mutiny.Transaction) -> T,
): T = withVertx(true) { scope ->
    Panache.withTransaction {
        val uniTransaction: Uni<Mutiny.Transaction?> = Panache.currentTransaction()
        val uniSession: Uni<Mutiny.Session> = Panache.getSession()
        uni(scope) {
            // this is CoroutineScope
            try {
                val transaction: Mutiny.Transaction? = uniTransaction.awaitSuspending()
                val session = uniSession.awaitSuspending()
                checkNotNull(transaction) { "Current transaction is null when previous call withTransaction" }
                block(scope, session, transaction)
            } catch (e: IllegalStateException) {
                panacheLogger.error("Error in Vertx Mutiny withSessionAndTransaction", e)
                // Rethrow to be handled by the caller
                throw e
            }
        }
    }.awaitSuspending() // awaits the Uni from withTransaction
}
