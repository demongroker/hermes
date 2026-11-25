package app.hermes.updater

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateFix002cTest {
  private val root = File(System.getProperty("user.dir")).parentFile
  private fun src(path: String) = File(root, path).readText()

  @Test
  fun settledCardResetsToIdleAfterInstallOrResume() {
    assertTrue(UpdateCardSync.isSettled("2.9.2", "2.9.2"))
    assertFalse(UpdateCardSync.isSettled("2.9.1", "2.9.2"))
    assertFalse(UpdateCardSync.isSettled("2.9.2", null))
    assertFalse(UpdateCardSync.isSettled("not installed", "not installed"))
    val main = src("app/src/main/java/app/hermes/updater/MainActivity.kt")
    assertTrue(main.contains("UpdateCardSync.isSettled(row.installed, row.latest)"))
    assertTrue(main.contains("rows.filter { UpdateCardSync.isSettled(it.installed, it.latest) }"))
  }

  @Test
  fun successCancelsAllNotificationsForThatPackage() {
    val notifier = src("app/src/main/java/app/hermes/updater/UpdateNotifier.kt")
    assertTrue(notifier.contains("fun cancelForPackage(ctx: Context, packageName: String)"))
    val worker = src("app/src/main/java/app/hermes/updater/DownloadWorker.kt")
    assertTrue(worker.contains("UpdateNotifier.cancelForPackage(ctx, pkg)"))
    val coord = src("app/src/main/java/app/hermes/updater/InstallCoordinator.kt")
    assertTrue(coord.contains("UpdateNotifier.cancelForPackage(context, packageName)"))
  }

  @Test
  fun pendingUserActionIsNotAFailureAndCannotOverlapNextInstall() {
    assertEquals(
      InstallEvent.Handled,
      InstallStatus.eventFor(android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION),
    )
    val waiting = InstallStateMachine.reduce(InstallPhase.IDLE, InstallEvent.Handled)
    assertEquals(InstallPhase.WAITING_FOR_USER, waiting)
    assertFalse(InstallStateMachine.isTerminal(waiting))
    assertFalse(InstallRetry.shouldRetry(waiting, 0))
    assertEquals(null, InstallStateMachine.message(waiting))
    val worker = src("app/src/main/java/app/hermes/updater/DownloadWorker.kt")
    assertTrue(worker.contains("InstallCoordinator.withInstallGate"))
    assertTrue(worker.contains("InstallCoordinator.awaitTerminal(pkg, InstallStateMachine.TIMEOUT_MS)"))
  }

  @Test
  fun failedCheckNeverStartsADownload() {
    assertFalse(CheckRetry.canStartDownload(ReleaseResult.HttpError(504)))
    assertFalse(CheckRetry.canStartDownload(ReleaseResult.Offline("SocketTimeoutException")))
    assertFalse(CheckRetry.canStartDownload(ReleaseResult.Missing("no assets")))
    val worker = src("app/src/main/java/app/hermes/updater/DownloadWorker.kt")
    assertTrue(worker.contains("if (!CheckRetry.canStartDownload(result))"))
    val gateIndex = worker.indexOf("if (!CheckRetry.canStartDownload(result))")
    val downloadIndex = worker.indexOf("UpdateFlow(ctx, client, installer).run")
    assertTrue("the check gate must come before the download call", gateIndex in 0 until downloadIndex)
  }
}
