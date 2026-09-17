package ai.rever.boss.process

import java.io.PrintWriter
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.LauncherDiscoveryRequest
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener

/** Runs the BOSS native smoke without requiring Gradle inside the restricted guest. */
object CageforgeNativeSecurityTestMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty()) { "native security smoke does not accept arguments" }

        val request: LauncherDiscoveryRequest =
            LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(CageforgeNativeSecuritySmokeTest::class.java))
                .build()
        val listener = SummaryGeneratingListener()
        LauncherFactory.create().apply {
            registerTestExecutionListeners(listener)
            execute(request)
        }

        listener.summary.printTo(PrintWriter(System.out, true))
        check(listener.summary.totalFailureCount == 0L) {
            "native security smoke reported ${listener.summary.totalFailureCount} failure(s)"
        }
    }
}
