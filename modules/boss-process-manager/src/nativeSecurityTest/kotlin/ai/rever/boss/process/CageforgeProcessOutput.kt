package ai.rever.boss.process

import java.io.ByteArrayOutputStream

internal fun readAvailableOutput(process: Process): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(1024)
    repeat(100) {
        var available = process.inputStream.available()
        while (available > 0) {
            val read = process.inputStream.read(buffer, 0, minOf(available, buffer.size))
            if (read <= 0) break
            output.write(buffer, 0, read)
            available = process.inputStream.available()
        }
        if (process.inputStream.available() == 0) {
            Thread.sleep(10)
        }
    }
    return output.toString(Charsets.UTF_8.name())
}
