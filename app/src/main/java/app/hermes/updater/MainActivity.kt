package app.hermes.updater

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hermes.updater.ui.CatalogCopy
import app.hermes.updater.ui.HermesTheme
import app.hermes.updater.ui.LocalHermesTones
import app.hermes.updater.ui.Tone
import app.hermes.updater.ui.hermesCardBorder
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

  /** Bumped in [onResume] so returning from the installer refreshes every card. */
  private val resumeTick = mutableStateOf(0)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Android 13+ gates notifications behind a runtime permission; ask once at startup.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }
    val installer = ApkInstaller(this)
    val sharer = ApkSharer(this)
    val client = GitHubClient(tokenProvider = { TokenStore(this).token() })
    val flow = UpdateFlow(this, client, installer)
    UpdateWorker.schedule(this)

    setContent {
      HermesTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          AppList(installer = installer, sharer = sharer, client = client, flow = flow)
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    // Coming back from the system installer must refresh the cards immediately, not on the
    // next 24 h check: this is what clears "Opening the system installer…".
    resumeTick.value++
  }

  @Composable
  private fun AppList(
    installer: ApkInstaller,
    sharer: ApkSharer,
    client: GitHubClient,
    flow: UpdateFlow,
  ) {
    var rows by remember { mutableStateOf<List<UpdateRow>>(emptyList()) }
    var status by remember { mutableStateOf("Checking… (0/${AppCatalog.apps.size})") }
    var lastChecked by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableStateOf(0) }
    var showPins by remember { mutableStateOf(false) }
    val flowStates = remember { mutableStateMapOf<String, FlowState>() }
    // Worker progress: survives the Activity, so a download that started earlier
    // (or that finished while the app was backgrounded) still shows here.
    val downloadStates by DownloadWorker.states.collectAsState()
    var confirmRow by remember { mutableStateOf<UpdateRow?>(null) }
    var confirmBatch by remember { mutableStateOf<List<UpdateRow>?>(null) }
    val scope = rememberCoroutineScope()
    val timeFormat = remember { DateFormat.getTimeFormat(this) }

    // Installer verdicts (success / cancelled / failed / timeout) live outside Compose state,
    // so the card can never keep showing a stale "Working…".
    val installPhases by InstallCoordinator.phases.collectAsState()

    LaunchedEffect(resumeTick.value) {
      if (resumeTick.value > 0) refreshKey++
    }

    LaunchedEffect(refreshKey) {
      checking = true
      status = "Checking… (0/${AppCatalog.apps.size})"
      // Render the list IMMEDIATELY from local package info — never a blank screen while
      // the network calls run. Each app's "latest" fills in as its own call returns.
      rows = withContext(Dispatchers.IO) {
        AppCatalog.apps.map { app ->
          UpdateRow(
            app = app,
            installed = installer.installedInfo(app.packageName)?.versionName
              ?: CatalogCopy.NOT_INSTALLED,
            latest = null,
            available = false,
            pinned = app.certPins.isNotEmpty(),
          )
        }
      }
      try {
      var done = 0
      coroutineScope {
        AppCatalog.apps.forEach { app ->
          launch {
            val result = withContext(Dispatchers.IO) { CheckRetry.latestRelease(client, app) }
            val latest = (result as? ReleaseResult.Ok)?.release?.tag?.removePrefix("v")
            val error = if (latest == null) result.message else null
            rows = rows.map { r ->
              if (r.app.id != app.id) r
              else {
                // The card's "update available" flag and the batch decision are the SAME
                // function — the header count and the enqueue list cannot drift apart.
                val checked = r.copy(latest = latest, failed = latest == null, checkError = error)
                checked.copy(available = UpdatePlanner.isUpdate(checked))
              }
            }
            if (latest == null) {
              // A failed check must never leave the row stuck on "Working…".
              flowStates.remove(app.id)
              DownloadWorker.clear(app.id)
            }
            done++
            status = if (done == AppCatalog.apps.size) "Checked ${AppCatalog.apps.size} apps"
            else "Checking… ($done/${AppCatalog.apps.size})"
          }
        }
      }
      // On install success or app resume, re-read installed versions (done above) and
      // clear any stale installer/progress state for cards that are now settled.
      rows.filter { UpdateCardSync.isSettled(it.installed, it.latest) }.forEach { row ->
        flowStates.remove(row.app.id)
        DownloadWorker.clear(row.app.id)
        InstallCoordinator.clear(row.app.packageName)
      }
      lastChecked = timeFormat.format(Date())
      } finally { checking = false }
    }

    // Pure: the same list feeds the header count and the batch enqueue call.
    val pending = UpdatePlanner.pending(rows)
    val runningAny = rows.any { row ->
      CatalogCopy.isRunning(downloadStates[row.app.id] ?: flowStates[row.app.id] ?: FlowState.Idle)
    }

    fun startFlow(row: UpdateRow) {
      // Hand the download to WorkManager: a coroutine in this scope dies when the Activity
      // stops, which is exactly why switching apps killed the Vela update at 3% with no
      // notification. The worker runs in the background and posts a progress notification.
      DownloadWorker.enqueue(applicationContext, row.app.packageName)
    }

    fun startBatch(batch: List<UpdateRow>) {
      // One worker per app, each under its own unique name — parallel downloads, single-flight
      // per app. flowStates gives immediate feedback before the workers publish their own state.
      DownloadWorker.enqueueAll(applicationContext, batch.map { it.app.packageName })
    }

    fun openApp(row: UpdateRow) {
      val intent = DownloadWorker.launchIntent(this, row.app.packageName) ?: return
      runCatching { startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun onShareTap(row: UpdateRow) {
      // Copying a tens-of-MB APK must not run on the main thread; the chooser is started from
      // inside [ApkSharer.share] once the copy is done.
      scope.launch {
        val error = withContext(Dispatchers.IO) { sharer.share(row.app) }
        if (error != null) {
          Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
        }
      }
    }

    fun onCancelTap(row: UpdateRow) {
      DownloadWorker.cancel(applicationContext, row.app.packageName)
      Toast.makeText(this@MainActivity, "Download cancelled", Toast.LENGTH_SHORT).show()
    }

    fun onUpdateTap(row: UpdateRow) {
      val unmetered = isUnmetered(this)
      if (NetworkPolicy.decide(unmetered) == NetworkDecision.ASK_FIRST) {
        confirmRow = row
      } else {
        startFlow(row)
      }
    }

    fun onUpdateAllTap(batch: List<UpdateRow>) {
      // One confirmation for the whole batch, not one per app.
      val unmetered = isUnmetered(this)
      if (NetworkPolicy.decide(unmetered) == NetworkDecision.ASK_FIRST) {
        confirmBatch = batch
      } else {
        startBatch(batch)
      }
    }

    confirmBatch?.let { batch ->
      AlertDialog(
        onDismissRequest = { confirmBatch = null },
        title = { Text("Metered connection") },
        text = {
          Text(
            "You are on mobile data. Download ${batch.size} update(s) " +
              "(${batch.joinToString { it.app.displayName }}) now? Hermes verifies the " +
              "checksum and the signing certificate of every APK before anything is installed.",
          )
        },
        confirmButton = {
          TextButton(onClick = { confirmBatch = null; startBatch(batch) }) { Text("Download") }
        },
        dismissButton = {
          TextButton(onClick = { confirmBatch = null }) { Text("Not now") }
        },
      )
    }

    confirmRow?.let { row ->
      AlertDialog(
        onDismissRequest = { confirmRow = null },
        title = { Text("Metered connection") },
        text = {
          Text(
            "You are on mobile data. Download ${row.app.displayName} " +
              "${row.latest ?: ""} now? Hermes verifies the checksum and the signing " +
              "certificate before anything is installed.",
          )
        },
        confirmButton = {
          TextButton(onClick = { confirmRow = null; startFlow(row) }) { Text("Download") }
        },
        dismissButton = {
          TextButton(onClick = { confirmRow = null }) { Text("Not now") }
        },
      )
    }

    if (showPins) {
      AlertDialog(
        onDismissRequest = { showPins = false },
        title = { Text("Signing pins") },
        text = {
          Column {
            Text(
              "Every install is refused unless the APK carries one of these certificates. " +
                "Prefixes shown only.",
              style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            AppCatalog.apps.forEach { app ->
              val pin = app.certPins.firstOrNull()
              Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(
                  app.displayName,
                  style = MaterialTheme.typography.bodyMedium,
                  modifier = Modifier.width(110.dp),
                )
                Text(
                  pin?.take(8) ?: "unpinned",
                  style = MaterialTheme.typography.bodyMedium,
                  color = if (pin == null) LocalHermesTones.current.danger
                  else LocalHermesTones.current.muted,
                )
              }
            }
          }
        },
        confirmButton = { TextButton(onClick = { showPins = false }) { Text("Close") } },
      )
    }

    Column(
      Modifier
        .fillMaxSize()
        .safeDrawingPadding()
        .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
      // Header: identity + last check on the left, the batch/refetch/pins controls on the
      // right. One compact line-height each so the five cards below own the screen.
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column {
          Text(
            "Hermes",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
          )
          Text(
            if (lastChecked == null) status else "$status · $lastChecked",
            style = MaterialTheme.typography.bodySmall,
            color = LocalHermesTones.current.muted,
          )
        }
        Column(horizontalAlignment = Alignment.End) {
          // The batch control: hidden at zero, "Updating n…" and disabled while any download
          // is in flight so a batch can never be enqueued twice.
          if (pending.isNotEmpty()) {
            Button(
              onClick = { onUpdateAllTap(pending) },
              enabled = !runningAny && !checking,
              modifier = Modifier.height(30.dp),
              shape = MaterialTheme.shapes.small,
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) {
              Text(
                if (runningAny) "Updating ${pending.size}…" else "Update all (${pending.size})",
                style = MaterialTheme.typography.labelMedium,
              )
            }
          }
          Row {
            TextButton(
              onClick = { refreshKey++ },
              enabled = !checking,
              modifier = Modifier.height(32.dp),
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
              Text(
                if (checking) "Checking…" else "Check now",
                style = MaterialTheme.typography.labelMedium,
              )
            }
            TextButton(
              onClick = { showPins = true },
              modifier = Modifier.height(32.dp),
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
              Text("Pins", style = MaterialTheme.typography.labelMedium)
            }
          }
        }
      }

      LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
        items(rows) { row ->
          val base = downloadStates[row.app.id] ?: flowStates[row.app.id] ?: FlowState.Idle
          // Once an APK has been handed over, the installer machine drives the card. Byte and
          // checksum progress still win while those are moving.
          val installPhase = installPhases[row.app.packageName] ?: InstallPhase.IDLE
          val state = when {
            UpdateCardSync.isSettled(row.installed, row.latest) -> FlowState.Idle
            base is FlowState.Downloading || base is FlowState.Verifying -> base
            installPhase != InstallPhase.IDLE -> InstallStateMachine.flowState(installPhase)
            else -> base
          }
          val running = CatalogCopy.isRunning(state)
          val line = CatalogCopy.status(
            pinned = row.pinned,
            installed = row.installed,
            latest = row.latest,
            failed = row.failed,
            available = row.available,
            state = state,
            checkError = row.checkError,
          )
          val action = CatalogCopy.action(
            running = running,
            pinned = row.pinned,
            installed = row.installed,
            latest = row.latest,
            failed = row.failed,
            available = row.available,
          )
          val pct = CatalogCopy.progressPercent(state)
          val downloading = state is FlowState.Downloading
          val tones = LocalHermesTones.current
          val toneColor: Color = when (line.tone) {
            Tone.GOOD -> tones.success
            Tone.WARN -> tones.warning
            Tone.BAD -> tones.danger
            Tone.BUSY -> MaterialTheme.colorScheme.primary
            Tone.NEUTRAL -> tones.muted
          }

          Card(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            border = hermesCardBorder,
          ) {
            Column(Modifier.padding(10.dp)) {
              // ONE dense row: identity + versions on the left, the single action and the
              // open/share icon actions on the right. Nothing stacks full-width any more.
              Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                Column(Modifier.weight(1f)) {
                  Text(
                    row.app.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                  Text(
                    "${CatalogCopy.installedLabel(row.installed)} · " +
                      CatalogCopy.latestLabel(row.latest, row.failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = tones.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                  // "Up to date" is the one status with nothing to act on — it stays implicit
                  // so an up-to-date app costs a single line instead of two.
                  if (line.tone != Tone.GOOD) {
                    Text(
                      line.text,
                      style = MaterialTheme.typography.bodySmall,
                      fontWeight = if (line.tone == Tone.BAD) FontWeight.SemiBold
                      else FontWeight.Normal,
                      color = toneColor,
                      maxLines = 2,
                      overflow = TextOverflow.Ellipsis,
                    )
                  }
                }
                // The card's one primary action as a compact pill. While the bytes are moving
                // the pill is replaced by the live percent, so the row never grows for it.
                if (downloading) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                      pct?.let { "$it%" } ?: "…",
                      style = MaterialTheme.typography.labelMedium,
                      color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(
                      onClick = { onCancelTap(row) },
                      modifier = Modifier.height(32.dp),
                      contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) {
                      Text("Cancel", style = MaterialTheme.typography.labelSmall)
                    }
                  }
                } else if (action != null) {
                  Button(
                    onClick = { onUpdateTap(row) },
                    enabled = !running && !flow.isBusy,
                    modifier = Modifier.height(32.dp),
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                  ) {
                    Text(action, style = MaterialTheme.typography.labelMedium)
                  }
                }
                // Launch the installed app without leaving Hermes. Hidden when not installed
                // (NOT_INSTALLED) or while its update is running.
                if (row.installed != CatalogCopy.NOT_INSTALLED && !running) {
                  IconButton(onClick = { openApp(row) }) {
                    Icon(
                      Icons.Filled.ExitToApp,
                      contentDescription = "Open ${row.app.displayName}",
                      modifier = Modifier.size(18.dp),
                      tint = tones.muted,
                    )
                  }
                }
                // Offer the installed build to another phone. Only the third-party apps the
                // catalog marks shareable get this; Hermes never shares itself.
                if (row.app.shareable && row.installed != CatalogCopy.NOT_INSTALLED && !running) {
                  IconButton(onClick = { onShareTap(row) }) {
                    Icon(
                      Icons.Filled.Share,
                      contentDescription = "Share ${row.app.displayName}",
                      modifier = Modifier.size(18.dp),
                      tint = tones.muted,
                    )
                  }
                }
              }
              // Thin progress rule under the row, only while the download is actually moving.
              if (downloading) {
                LinearProgressIndicator(
                  progress = { (pct ?: 0) / 100f },
                  modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
              }
            }
          }
        }
      }
    }
  }

  /** null = no active network (NetworkPolicy then asks instead of auto-downloading). */
  private fun isUnmetered(context: Context): Boolean? {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
      ?: return null
    val network = cm.activeNetwork ?: return null
    val caps = cm.getNetworkCapabilities(network) ?: return null
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
  }
}
