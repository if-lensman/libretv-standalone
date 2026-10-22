package family.libretv.tv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Ordered delivery using the same single-episode cast command. Never retries an uncertain request. */
internal class PhoneCastBatch(private val intervalMs: Long = 1200) {
    data class Result(val accepted: List<Int>, val unconfirmed: Int? = null, val stopped: Boolean = false)

    suspend fun run(
        range: IntRange,
        shouldStop: () -> Boolean,
        beforeSend: suspend (Int) -> Unit,
        send: suspend (Int) -> Boolean,
        accepted: suspend (Int, Int) -> Unit
    ): Result {
        require(!range.isEmpty() && range.first >= 0)
        val sent = mutableListOf<Int>()
        for(index in range) {
            if(shouldStop()) return Result(sent.toList(), stopped = true)
            beforeSend(index)
            if(shouldStop()) return Result(sent.toList(), stopped = true)
            val ok = try { send(index) }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { false }
            if(!ok) return Result(sent.toList(), unconfirmed = index)
            sent.add(index)
            accepted(index, sent.size)
            if(index != range.last && !shouldStop()) delay(intervalMs)
        }
        return Result(sent.toList(), stopped = shouldStop())
    }
}
