package app.hermes.updater

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.unit.dp
import app.hermes.updater.ui.CatalogCopy
import app.hermes.updater.ui.HermesTheme
import app.hermes.updater.ui.LocalHermesTones
import app.hermes.updater.ui.Tone
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row of the list: what is installed, what is published, and the pins that matter. */
private data class Row(
  val app: CatalogApp,
  val installed: String,
  val latest: String?,
  val available: Boolean,
  val pinned: Boolean,
  val failed: Boolean = false,
)

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val installer = ApkInstaller(this)
    val client = GitHubClient(tokenProvider = { TokenStore(this).token() })
    val flow = UpdateFlow(this, client, installer)
    UpdateWorker.schedule(this)

    setContent {
      HermesTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          AppList(installer = installer, client = client, flow = flow)
        }
      }
    }
  }

  @Composable
  private fun AppList(installer: ApkInstaller, client: GitHubClient, flow: UpdateFlow) {
    var rows by remember { mutableStateOf<List<Row>>(emptyList()) }
    var status by remember { mutableStateOf("Checking… (0/${AppCatalog.apps.size})") }
    var lastChecked by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableStateOf(0) }
    var showPins by remember { mutableStateOf(false) }
    val flowStates = remember { mutableStateMapOf<String, FlowState>() }
    // Worker progress: survives the Activity, so a download that started earlier
    // (or that finished while the app was backgrounded) still shows here.
    val downloadStates by DownloadWorker.states.collectAsState()
    var confirmRow by remember { mutableStateOf<Row?>(null) }
    val scope = rememberCoroutineScope()
    val timeFormat = remember { DateFormat.getTimeFormat(this) }

    LaunchedEffect(refreshKey) {
      checking = true
      status = "Checking… (0/${AppCatalog.apps.size})"
      // Render the list IMMEDIATELY from local package info — never a blank screen while
      // the network calls run. Each app's "latest" fills in as its own call returns.
      rows = withContext(Dispatchers.IO) {
        AppCatalog.apps.map { app ->
          Row(
            app = app,
            installed = installer.installedInfo(app.packageName)?.versionName
              ?: CatalogCopy.NOT_INSTALLED,
            latest = null,
            available = false,
            pinned = app.certPins.isNotEmpty(),
          )
        }
      }
      var done = 0
      coroutineScope {
        AppCatalog.apps.forEach { app ->
          launch {
            val latest = withContext(Dispatchers.IO) {
              runCatching { client.latestRelease(app)?.tag?.removePrefix("v") }.getOrNull()
            }
            rows = rows.map { r ->
              if (r.app.id != app.id) r
              else r.copy(
                latest = latest,
                failed = latest == null,
                available = latest != null && r.installed != CatalogCopy.NOT_INSTALLED &&
                  Versions.isNewer(latest, r.installed),
              )
            }
            done++
            status = if (done == AppCatalog.apps.size) "Checked ${AppCatalog.apps.size} apps"
            else "Checking… ($done/${AppCatalog.apps.size})"
          }
        }
      }
      lastChecked = timeFormat.format(Date())
      checking = false
    }

    fun startFlow(row: Row) {
      // Hand the download to WorkManager: a coroutine in this scope dies when the Activity
      // stops, which is exactly why switching apps killed the Vela update at 3% with no
      // notification. The worker runs in the background and posts a progress notification.
      flowStates[row.app.id] = FlowState.Downloading(-1)
      DownloadWorker.enqueue(applicationContext, row.app.id)
    }

    fun openApp(row: Row) {
      val intent = DownloadWorker.launchIntent(this, row.app.packageName) ?: return
      runCatching { startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun onUpdateTap(row: Row) {
      val unmetered = isUnmetered(this)
      if (NetworkPolicy.decide(unmetered) == NetworkDecision.ASK_FIRST) {
        confirmRow = row
      } else {
        startFlow(row)
      }
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
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column {
          Text(
            "Hermes",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
          )
          Text(
            if (lastChecked == null) status else "$status · $lastChecked",
            style = MaterialTheme.typography.bodySmall,
            color = LocalHermesTones.current.muted,
          )
        }
        Column(horizontalAlignment = Alignment.End) {
          TextButton(onClick = { refreshKey++ }, enabled = !checking) {
            Text(if (checking) "Checking…" else "Check now")
          }
          TextButton(onClick = { showPins = true }) { Text("Pins") }
        }
      }

      LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
        items(rows) { row ->
          val state = downloadStates[row.app.id] ?: flowStates[row.app.id] ?: FlowState.Idle
          val running = CatalogCopy.isRunning(state)
          val line = CatalogCopy.status(
            pinned = row.pinned,
            installed = row.installed,
            latest = row.latest,
            failed = row.failed,
            available = row.available,
            state = state,
          )
          val action = CatalogCopy.action(
            running = running,
            pinned = row.pinned,
            installed = row.installed,
            latest = row.latest,
            failed = row.failed,
            available = row.available,
          )
          val tones = LocalHermesTones.current
          val toneColor: Color = when (line.tone) {
            Tone.GOOD -> tones.success
            Tone.WARN -> tones.warning
            Tone.BAD -> tones.danger
            Tone.BUSY -> MaterialTheme.colorScheme.primary
            Tone.NEUTRAL -> tones.muted
          }

          Card(
            Modifier.fillMaxWidth().padding(vertical = 5.dp),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
          ) {
            Column(Modifier.padding(14.dp)) {
              Text(
                row.app.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Spacer(Modifier.height(4.dp))
              Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  CatalogCopy.installedLabel(row.installed),
                  style = MaterialTheme.typography.bodySmall,
                  color = tones.muted,
                )
                Text(
                  CatalogCopy.latestLabel(row.latest, row.failed),
                  style = MaterialTheme.typography.bodySmall,
                  color = tones.muted,
                )
              }
              Spacer(Modifier.height(8.dp))
              Text(
                line.text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (line.tone == Tone.BAD) FontWeight.SemiBold else FontWeight.Normal,
                color = toneColor,
              )
              CatalogCopy.progressPercent(state)?.let { pct ->
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                  progress = { pct / 100f },
                  modifier = Modifier.fillMaxWidth(),
                )
              }
              if (action != null) {
                Button(
                  onClick = { onUpdateTap(row) },
                  enabled = !running && !flow.isBusy,
                  modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                  shape = MaterialTheme.shapes.small,
                ) { Text(action) }
              }
              // Launch the installed app without leaving Hermes. Hidden when not installed
              // (NOT_INSTALLED) or while its update is running.
              if (row.installed != CatalogCopy.NOT_INSTALLED && !running) {
                TextButton(
                  onClick = { openApp(row) },
                  modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                ) { Text("Open") }
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
