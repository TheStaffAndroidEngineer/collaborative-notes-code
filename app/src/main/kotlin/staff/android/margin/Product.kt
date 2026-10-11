package staff.android.margin
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.json.JSONObject
const val ABOUT="Margin edits a shared studio note using paragraph revisions and explicit conflict resolution. It persists a draft when you tap Keep draft or Save paragraph; unsaved typing is not crash durable. This is compare-and-set, not a character-level CRDT or OT editor."
fun validateView(data:JSONObject){
    val rows = data.getJSONArray("paragraphs")
    require(rows.length() == 3)
    val ids = mutableSetOf<String>()
    for (i in 0 until rows.length()) {
        val p = rows.getJSONObject(i)
        require(ids.add(p.getString("id")))
        require(
            p.get("revision") is Int &&
                p.getInt("revision") > 0 &&
                p.get("deleted") is Boolean &&
                p.getString("text").isNotBlank())
    }
}
@Composable fun Product(state: CaseState, model: CaseModel, elapsed: Long) {
    val draft by model.draft.collectAsState()
    val rows = state.data?.getJSONArray("paragraphs") ?: org.json.JSONArray()
    val current = (0 until rows.length()).map { rows.getJSONObject(it) }
    var compare by remember { mutableStateOf(false) }
    var close by remember { mutableStateOf(false) }
    BackHandler(enabled = draft != null) { if (compare) compare = false else close = true }
    Text("SHARED STUDIO NOTE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Text("Saturday studio", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text(if (draft == null) "${current.count { !it.getBoolean("deleted") }} paragraphs · shared plan" else "Editing one paragraph", color = MaterialTheme.colorScheme.onSurfaceVariant)
    state.outcome?.takeIf { it.optBoolean("accepted") && it.has("paragraphId") && it.has("revision") && draft?.optString("paragraphId") == it.getString("paragraphId") }?.let {
        InfoCard("Save acknowledged", "The service accepted this paragraph as revision ${it.getInt("revision")}. Compare your draft with the latest shared version before saving again.")
    }
    if (draft == null) {
        if (state.data == null) InfoCard(if (state.busy) "Opening the note" else "Note unavailable", "Refresh when the local service is connected.")
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                for ((index, row) in current.withIndex()) if (!row.getBoolean("deleted")) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("PARAGRAPH ${index + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { model.edit(row) }, enabled = ready(state), modifier = Modifier.testTag("edit-${row.getString("id")}")) { Text("Edit paragraph") }
                    }
                    Text(row.getString("text"), style = MaterialTheme.typography.bodyLarge)
                    HorizontalDivider()
                }
            }
        }
    } else {
        val d = draft!!
        val row = current.find { it.getString("id") == d.getString("paragraphId") }
        val conflict = row != null && !row.getBoolean("deleted") && row.getInt("revision") != d.getInt("baseRevision")
        if (conflict) {
            InfoCard("Shared version has changed", "The shared revision differs from your draft’s starting point. Compare the versions, combine the changes you want, then choose the current version as your base.")
            OutlinedButton(onClick = { compare = true }, modifier = Modifier.testTag("review-conflict")) { Text("Compare versions") }
        }
        OutlinedTextField(value = d.getString("text"), onValueChange = model::change, label = { Text("Your draft") }, enabled = ready(state), modifier = Modifier.fillMaxWidth().testTag("draft"), minLines = 5, maxLines = 10)
        Text("Typing stays in memory. Keep draft or Save paragraph writes it to this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (row == null) InfoCard("Current version unavailable", "Your base and draft remain here. Refresh before saving or comparing.")
        if (row?.getBoolean("deleted") == true) InfoCard("Paragraph deleted", "The shared paragraph was removed. Your local draft is retained, but it cannot be saved over a deletion.")
        Button(onClick = model::submit, enabled = ready(state) && row != null && !row.getBoolean("deleted") && d.getString("text").isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("save")) { Text("Save paragraph") }
        OutlinedButton(onClick = model::keep, enabled = ready(state), modifier = Modifier.fillMaxWidth().testTag("keep")) { Text("Keep draft on device") }
        TextButton(onClick = { close = true }, enabled = ready(state), modifier = Modifier.testTag("discard")) { Text("Close and discard draft") }
        if (compare && row != null) AlertDialog(onDismissRequest = { compare = false }, title = { Text("Compare paragraph versions") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Starting version", fontWeight = FontWeight.Bold); Text(d.getString("baseText")); HorizontalDivider()
                Text("Your draft", fontWeight = FontWeight.Bold); Text(d.getString("text")); HorizontalDivider()
                Text("Current shared version", fontWeight = FontWeight.Bold); Text(if (row.getBoolean("deleted")) "Deleted" else row.getString("text"))
                Text("Edit your draft outside this comparison to combine the intended changes. Choosing the current base keeps your draft text.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { if (!row.getBoolean("deleted") && row.getInt("revision") != d.getInt("baseRevision")) TextButton(onClick = { model.rebase(row); compare = false }, enabled = ready(state), modifier = Modifier.testTag("rebase")) { Text("Use current revision as base") } }, dismissButton = { TextButton(onClick = { compare = false }) { Text("Keep editing") } })
    }
    if (close) AlertDialog(onDismissRequest = { close = false }, title = { Text("Discard this draft?") }, text = { Text("This removes the draft from this device. It does not change the shared paragraph.") }, confirmButton = { TextButton(onClick = { close = false; model.discard() }, enabled = ready(state), modifier = Modifier.testTag("confirm-discard")) { Text("Discard draft") } }, dismissButton = { TextButton(onClick = { close = false }) { Text("Keep editing") } })
}
