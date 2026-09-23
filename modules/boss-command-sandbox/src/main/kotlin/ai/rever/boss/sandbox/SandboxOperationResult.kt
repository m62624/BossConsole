package ai.rever.boss.sandbox

import ai.cageforge.CageforgeException
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Adapter boundary: present expected validation/native/I/O failures without swallowing cancellation or VM errors. */
suspend fun <T> sandboxOperationResult(action: suspend () -> T): Result<T> =
    try {
        Result.success(action())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: CageforgeException) {
        Result.failure(failure)
    } catch (failure: IOException) {
        Result.failure(failure)
    } catch (failure: IllegalArgumentException) {
        Result.failure(failure)
    } catch (failure: IllegalStateException) {
        Result.failure(failure)
    } catch (failure: SecurityException) {
        Result.failure(failure)
    } catch (failure: UnsatisfiedLinkError) {
        Result.failure(failure)
    }
