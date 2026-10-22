package family.libretv.tv

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PhoneCastBatchTest {
    @Test fun deliversOnlySelectedRangeInOrder() = runBlocking {
        val requests=mutableListOf<Int>(); val commits=mutableListOf<Int>()
        val result=PhoneCastBatch(0).run(2..4,{false},{},{ requests.add(it); true },{ index,_ -> commits.add(index) })
        assertEquals(listOf(2,3,4),requests); assertEquals(requests,commits)
        assertEquals(requests,result.accepted); assertNull(result.unconfirmed)
    }
    @Test fun failureAndExceptionsAreUnconfirmedAndNeverRetried() = runBlocking {
        for(throws in listOf(false,true)) {
            val requests=mutableListOf<Int>()
            val result=PhoneCastBatch(0).run(0..3,{false},{},{
                requests.add(it)
                if(it==1) { if(throws) throw java.io.IOException("fixture") else false } else true
            },{_,_->})
            assertEquals(listOf(0,1),requests); assertEquals(listOf(0),result.accepted)
            assertEquals(1,result.unconfirmed)
        }
    }
    @Test fun stopWaitsForInFlightResultAndDoesNotSendTheNextItem() = runBlocking {
        var stop=false; val requests=mutableListOf<Int>()
        val result=PhoneCastBatch(0).run(0..3,{stop},{},{ requests.add(it); stop=true; true },{_,_->})
        assertEquals(listOf(0),requests); assertEquals(listOf(0),result.accepted); assertTrue(result.stopped)
    }
    @Test(expected = CancellationException::class) fun lifecycleCancellationIsNotRetried() = runBlocking {
        PhoneCastBatch(0).run(0..3,{false},{},{throw CancellationException("fixture")},{_,_->})
        Unit
    }
}
