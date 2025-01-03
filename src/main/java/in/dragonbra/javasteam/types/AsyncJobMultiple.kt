package `in`.dragonbra.javasteam.types

import `in`.dragonbra.javasteam.steam.steamclient.AsyncJobFailedException
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackMsg
import kotlinx.coroutines.CancellationException
import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * @author Lossy
 * @since 2023-03-17
 */
class AsyncJobMultiple<T : CallbackMsg>(
    client: SteamClient,
    jobId: JobID,
    private val finishCondition: (T) -> Boolean?,
) : AsyncJob(client, jobId) {

    class ResultSet(
        var complete: Boolean = false,
        var failed: Boolean = false,
        var results: List<CallbackMsg> = listOf(),
    )

    private val future = CompletableFuture<ResultSet>()

    private val results = mutableListOf<T>()

    init {
        registerJob(client)
    }

    @Deprecated("Use toFuture() instead", ReplaceWith("toFuture()"))
    fun toDeferred(): CompletableFuture<ResultSet> = toFuture()

    fun toFuture(): CompletableFuture<ResultSet> = future

    fun await(): ResultSet = toFuture().get()

    @Suppress("unused")
    @Throws(CancellationException::class)
    fun runBlock(): ResultSet = toFuture().get()

    override fun addResult(callback: CallbackMsg): Boolean {
        @Suppress("UNCHECKED_CAST")
        val callbackMsg = callback as T

        // add this callback to our result set
        results.add(callbackMsg)

        return if (finishCondition(callbackMsg) == true) {
            future.complete(ResultSet(complete = true, failed = false, results = Collections.unmodifiableList(results)))
            true
        } else {
            heartbeat()
            false
        }
    }

    override fun setFailed(dueToRemoteFailure: Boolean) {
        if (results.isEmpty()) {
            // if we have zero callbacks in our result set, we cancel this task
            if (dueToRemoteFailure) {
                // if we're canceling with a remote failure, post a job failure exception
                future.completeExceptionally(AsyncJobFailedException())
            } else {
                // otherwise, normal task cancellation for timeouts
                future.cancel(true)
            }
        } else {
            val resultSet = ResultSet(false, dueToRemoteFailure, Collections.unmodifiableList(results))
            future.complete(resultSet)
        }
    }
}
