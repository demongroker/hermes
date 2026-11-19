package app.hermes.updater

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row of the list: what is installed, what is published, and the pins that matter. */
private data class Row(
  val app: CatalogApp,
  val installed: String,
  val latest: String?,
  val available: Boolean,
  val pinned: Boolean,
)

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val installer = ApkInstaller(this)
    val client = GitHubClient(tokenProvider = { TokenStore(this).token() })
    val flow = UpdateFlow(this, client, installer)
    UpdateWorker.schedule(this)

    setContent {
      MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
          AppList(installer = installer, client = client, flow = flow)
        }
      }
    }
  }

  @Composable
  private fun AppList(installer: ApkInstaller, client: GitHubClient, flow: UpdateFlow) {
    var rows by remember { mutableStateOf<List<Row>>(emptyList()) }
    var status by remember { mutableStateOf("Checking…") }
    val flowStates = remember { mutableStateMapOf<String, FlowState>() }
    var confirmRow by remember { mutableStateOf<Row?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
      rows = withContext(Dispatchers.IO) {
        AppCatalog.apps.map { app ->
          val installed = installer.installedInfo(app.packageName)?.versionName ?: "not installed"
          val latest = runCatching { client.latestRelease(app)?.tag?.removePrefix("v") }.getOrNull()
          Row(
            app = app,
            installed = installed,
            latest = latest,
            available = latest != null && installed != "not installed" &&
              Versions.isNewer(latest, installed),
            pinned = app.certPins.isNotEmpty(),
          )
        }
      }
      status = "Checked ${AppCatalog.apps.size} app(s)"
    }

    fun startFlow(row: Row) {
      scope.launch {
        val release = withContext(Dispatchers.IO) {
          runCatching { client.latestRelease(row.app) }.getOrNull()
        }
        if (release == null) {
          flowStates[row.app.id] = FlowState.Failed("Could not read the latest release.")
          return@launch
        }
        withContext(Dispatchers.IO) {
          flow.run(row.app, release) { state -> flowStates[row.app.id] = state }
        }
      }
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
        text = { Text("Download ${row.app.displayName} ${row.latest ?: ""} on mobile data?") },
        confirmButton = {
          TextButton(onClick = { confirmRow = null; startFlow(row) }) { Text("Download") }
        },
        dismissButton = {
          TextButton(onClick = { confirmRow = null }) { Text("Not now") }
        },
      )
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
      Text("Hermes", style = MaterialTheme.typography.headlineMedium)
      Text(status, style = MaterialTheme.typography.bodySmall)
      Text(
        // Pin prefixes only — never a full certificate.
        "Signing pins: " + AppCatalog.apps.joinToString(", ") { app ->
          val pin = app.certPins.firstOrNull()
          "${app.displayName}=${pin?.take(8) ?: "unpinned"}"
        },
        style = MaterialTheme.typography.bodySmall,
      )
      LazyColumn(Modifier.fillMaxSize().padding(top = 12.dp)) {
        items(rows) { row ->
          val state = flowStates[row.app.id] ?: FlowState.Idle
          val running = state is FlowState.Downloading || state is FlowState.Verifying ||
            state is FlowState.Installing
          Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.padding(12.dp)) {
              Text(row.app.displayName, style = MaterialTheme.typography.titleMedium)
              Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text("installed ${row.installed}", style = MaterialTheme.typography.bodySmall)
                Text("latest ${row.latest ?: "?"}", style = MaterialTheme.typography.bodySmall)
              }
              Text(
                when {
                  !row.pinned -> "No signing pin — installs refused (fail closed)"
                  state is FlowState.Downloading ->
                    if (state.percent >= 0) "Downloading ${state.percent}%" else "Downloading…"
                  state is FlowState.Verifying -> "Verifying checksum and signature…"
                  state is FlowState.Installing -> "Opening the system installer…"
                  state is FlowState.Blocked -> state.message
                  state is FlowState.Failed -> state.message
                  row.available -> "Update available"
                  else -> "Up to date"
                },
                style = MaterialTheme.typography.bodySmall,
              )
              if (row.available && row.pinned) {
                Button(
                  onClick = { onUpdateTap(row) },
                  enabled = !running && !flow.isBusy,
                  modifier = Modifier.padding(top = 8.dp),
                ) { Text(if (running) "Working…" else "Update") }
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
