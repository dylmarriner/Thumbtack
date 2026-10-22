package ai.paperclip.dashboard.ui

import ai.paperclip.dashboard.data.Agent
import ai.paperclip.dashboard.data.PaperclipApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun AgentsScreen(api: PaperclipApi, companyId: String, onOpen: (String) -> Unit) {
    val state = rememberLoad(companyId) { api.agents(companyId) }
    LoadedContent(state) { agents ->
        val visible = agents.filter { it.status != "terminated" }.sortedWith(compareBy({ agentOrder(it.status) }, { it.name }))
        if (visible.isEmpty()) {
            LazyColumn(Modifier.fillMaxSize()) { item { EmptyState("No agents") } }
        } else {
            LazyColumn {
                items(visible, key = { it.id }) { a ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onOpen(a.id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(a.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            StatusPill(a.status)
                        }
                        Text(
                            listOfNotNull(a.title ?: humanize(a.role), a.adapterType.ifBlank { null }, a.lastHeartbeatAt?.let { "seen " + relativeTime(it) })
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun agentOrder(status: String) = when (status) {
    "error" -> 0
    "running" -> 1
    "active" -> 2
    "idle" -> 3
    "pending_approval" -> 4
    "paused" -> 5
    else -> 6
}

@Composable
fun AgentDetailScreen(api: PaperclipApi, companyId: String, agentId: String, agentsById: Map<String, Agent>, onChanged: () -> Unit) {
    val state = rememberLoad(agentId) { api.agent(agentId, companyId) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun act(label: String, block: suspend () -> Unit) {
        busy = true
        scope.launch {
            message = runCatching { block() }.fold({ "$label requested" }, { it.message ?: "Failed" })
            busy = false
            state.reload()
            onChanged()
        }
    }

    LoadedContent(state) { a ->
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(a.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(a.status)
                        Text(a.title ?: humanize(a.role), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    a.errorReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    a.pauseReason?.let { Text("Paused: ${humanize(it)}", color = statusColor("paused")) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy && a.status != "paused" && a.status != "terminated", onClick = {
                        act("Heartbeat") { api.invokeAgent(a.id, companyId) }
                    }) { Text("Run now") }
                    if (a.status == "paused") {
                        OutlinedButton(enabled = !busy, onClick = { act("Resume") { api.resumeAgent(a.id, companyId) } }) { Text("Resume") }
                    } else if (a.status != "terminated") {
                        OutlinedButton(enabled = !busy, onClick = { act("Pause") { api.pauseAgent(a.id, companyId) } }) { Text("Pause") }
                    }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
            }
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Budget this month", style = MaterialTheme.typography.titleSmall)
                        if (a.budgetMonthlyCents > 0) {
                            LinearProgressIndicator(
                                progress = { (a.spentMonthlyCents.toFloat() / a.budgetMonthlyCents).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("${money(a.spentMonthlyCents)} of ${money(a.budgetMonthlyCents)}")
                        } else {
                            Text("${money(a.spentMonthlyCents)} spent · no budget cap")
                        }
                    }
                }
            }
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Detail("Adapter", a.adapterType)
                        Detail("Reports to", a.reportsTo?.let { agentsById[it]?.name ?: it } ?: "Board")
                        Detail("Last heartbeat", a.lastHeartbeatAt?.let(::relativeTime) ?: "Never")
                        a.capabilities?.takeIf { it.isNotBlank() }?.let { Detail("Capabilities", it) }
                    }
                }
            }
        }
    }
}

@Composable
fun Detail(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
