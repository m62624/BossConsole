package ai.rever.boss.sandbox

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class SandboxOperationResultTest {
    @Test
    fun `adapter retains failures but rethrows cancellation and VM errors`(): Unit =
        runBlocking {
            val failure = IOException("Cannot read policy")
            assertSame(failure, sandboxOperationResult<Unit> { throw failure }.exceptionOrNull())
            assertFailsWith<CancellationException> { sandboxOperationResult<Unit> { throw CancellationException() } }
            assertFailsWith<OutOfMemoryError> {
                sandboxOperationResult<Unit> { throw OutOfMemoryError("test sentinel") }
            }
        }
}
