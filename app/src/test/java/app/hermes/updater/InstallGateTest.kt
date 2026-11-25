package app.hermes.updater

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class InstallGateTest {
  @Test
  fun onlyOneInstallerHandOffAtATime() = runBlocking {
    val active = AtomicInteger(0)
    val peak = AtomicInteger(0)
    val keys = listOf("app.vela.player", "com.xerxes.videodroid", "app.hermes.updater")
    val jobs = keys.map { key ->
      launch(Dispatchers.Default) {
        InstallCoordinator.withInstallGate {
          peak.updateAndGet { maxOf(it, active.incrementAndGet()) }
          delay(25)
          active.decrementAndGet()
        }
      }
    }
    jobs.forEach { it.join() }
    assertEquals(1, peak.get())
  }

  @Test
  fun phasesAreIsolatedByPackageName() {
    InstallCoordinator.clear("app.vela.player")
    InstallCoordinator.clear("com.xerxes.videodroid")
    InstallCoordinator.begin("app.vela.player")
    assertEquals(InstallPhase.WAITING_FOR_USER, InstallCoordinator.phase("app.vela.player"))
    assertEquals(InstallPhase.IDLE, InstallCoordinator.phase("com.xerxes.videodroid"))
    InstallCoordinator.apply("com.xerxes.videodroid", InstallEvent.Succeeded)
    assertEquals(InstallPhase.WAITING_FOR_USER, InstallCoordinator.phase("app.vela.player"))
  }
}
