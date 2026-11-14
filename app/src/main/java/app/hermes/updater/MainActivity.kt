package app.hermes.updater

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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
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
    UpdateWorker.schedule(this)

    setContent {
      MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
          AppList(installer = installer, client = client)
        }
      }
    }
  }

  @Composable
  private fun AppList(installer: ApkInstaller, client: GitHubClient) {
    var rows by remember { mutableStateOf<List<Row>>(emptyList()) }
    var status by remember { mutableStateOf("Checking…") }

    LaunchedEffect(Unit) {
      rows = withContext(Dispatchers.IO) {
        AppCatalog.apps.map { app ->
          val installed = installer.installedInfo(app.packageName)?.versionName ?: "not installed"
          val latest = runCatching { client.latestRelease(app)?.tag?.removePrefix("v") }.getOrNull()
          Row(
            app = app,
            installed = installed,
            latest = latest,
            available = latest != null && installed != "not installed" && latest != installed,
            pinned = app.certPins.isNotEmpty(),
          )
        }
      }
      status = "Checked ${AppCatalog.apps.size} app(s)"
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
                  row.available -> "Update available"
                  else -> "Up to date"
                },
                style = MaterialTheme.typography.bodySmall,
              )
            }
          }
        }
      }
    }
  }
}
