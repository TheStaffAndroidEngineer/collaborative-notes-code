package staff.android.margin

import android.app.Application
import android.os.SystemClock
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class CaseState(
    val data: JSONObject? = null,
    val busy: Boolean = true,
    val problem: String? = null,
    val pending: JSONObject? = null,
    val outcome: JSONObject? = null,
    val observedAt: Long = 0,
    val receivedElapsed: Long = 0,
    val storageBlocked: Boolean = false
)

class CaseApi {
    private val client =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()

    fun request(path: String, body: JSONObject? = null): JSONObject {
        val builder = Request.Builder().url("http://10.0.2.2:8919$path")
        if (body != null)
            builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        client.newCall(builder.build()).execute().use { response ->
            val out = java.io.ByteArrayOutputStream()
            response.body.byteStream().use { input ->
                val bytes = ByteArray(4096)
                while (true) {
                    val n = input.read(bytes)
                    if (n < 0) break
                    if (n > 262144 - out.size())
                        throw IOException("Response exceeds fixture contract")
                    out.write(bytes, 0, n)
                }
            }
            val decoder =
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            val result =
                JSONObject(decoder.decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString())
            if (!response.isSuccessful)
                throw IOException("Service ${response.code}: ${result.optString("error")}")
            require(result.getString("kind") == "margin")
            return result
        }
    }
}

class CaseModel(application: Application) : AndroidViewModel(application) {
    private val api = CaseApi()
    private val drafts = DraftStore(application.filesDir)
    private val draftMutable = MutableStateFlow<JSONObject?>(null)
    val draft = draftMutable.asStateFlow()

    fun edit(row: JSONObject) {
        if (!ready(state.value) || draft.value != null) return
        persistDraft(
            JSONObject()
                .put("paragraphId", row.getString("id"))
                .put("baseRevision", row.getInt("revision"))
                .put("baseText", row.getString("text"))
                .put("text", row.getString("text")))
    }

    fun change(text: String) {
        if (!ready(state.value) || text.length > 2000) return
        draft.value?.let { draftMutable.value = JSONObject(it.toString()).put("text", text) }
    }

    fun keep() {
        if (ready(state.value)) persistDraft(draft.value)
    }

    fun discard() {
        if (ready(state.value)) persistDraft(null)
    }

    fun rebase(row: JSONObject) {
        if (!ready(state.value)) return
        draft.value?.let {
            persistDraft(
                JSONObject(it.toString())
                    .put("baseRevision", row.getInt("revision"))
                    .put("baseText", row.getString("text")))
        }
    }

    private fun persistDraft(value: JSONObject?) {
        mutable.value = state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { drafts.save(value) }
                draftMutable.value = value
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.value =
                    state.value.copy(problem = "Draft could not be saved; keep this editor open.")
            } finally {
                mutable.value = state.value.copy(busy = false)
            }
        }
    }

    fun submit() {
        if (!ready(state.value)) return
        val d = draft.value ?: return
        if (d.getString("text").isBlank()) return
        // Save base and local intent before the command journal admits any network write.
        mutable.value = state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { drafts.save(d) }
                mutable.value = state.value.copy(busy = false)
                send(
                    "save",
                    JSONObject()
                        .put("paragraphId", d.getString("paragraphId"))
                        .put("baseRevision", d.getInt("baseRevision"))
                        .put("text", d.getString("text")))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.value =
                    state.value.copy(
                        busy = false, problem = "Draft persistence failed. Nothing was sent.")
            }
        }
    }

    private val journal = AtomicFile(File(application.filesDir, "pending-command.json"))
    private val mutable = MutableStateFlow(CaseState())
    val state = mutable.asStateFlow()

    // Work is serialized by the busy gate, including persistence and refresh.
    init {
        viewModelScope.launch {
            try {
                draftMutable.value = withContext(Dispatchers.IO) { drafts.read() }
                val pending = withContext(Dispatchers.IO) { read() }
                mutable.value = state.value.copy(pending = pending)
                refreshInside()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.value =
                    state.value.copy(
                        busy = false,
                        storageBlocked = true,
                        problem =
                            "Saved request could not be read. It is preserved for investigation.")
            }
        }
    }

    private fun read(): JSONObject? {
        if (!journal.baseFile.exists() && !File(journal.baseFile.path + ".bak").exists())
            return null
        val wrapper = JSONObject(String(journal.readFully(), Charsets.UTF_8))
        require(wrapper.has("pending"))
        if (wrapper.isNull("pending")) return null
        val command = wrapper.getJSONObject("pending")
        require(UUID.fromString(command.getString("id")).toString() == command.getString("id"))
        require(command.getString("action").isNotBlank())
        command.getJSONObject("args")
        return command
    }

    private fun save(command: JSONObject?) {
        val output = journal.startWrite()
        try {
            output.write(
                JSONObject().put("pending", command ?: JSONObject.NULL).toString().toByteArray())
            journal.finishWrite(output)
        } catch (error: Exception) {
            journal.failWrite(output)
            throw error
        }
    }

    fun refresh() {
        if (state.value.busy || state.value.storageBlocked) return
        mutable.value = state.value.copy(busy = true, problem = null)
        viewModelScope.launch { refreshInside() }
    }

    private suspend fun refreshInside() {
        try {
            val response = withContext(Dispatchers.IO) { api.request("/view") }
            val data = response.getJSONObject("data")
            validateView(data)
            val time = response.get("observedAt")
            require(time is Long || time is Int)
            mutable.value =
                state.value.copy(
                    data = data,
                    observedAt = (time as Number).toLong(),
                    receivedElapsed = SystemClock.elapsedRealtime(),
                    busy = false,
                    problem = null)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutable.value =
                state.value.copy(
                    busy = false,
                    problem =
                        "Current state could not be checked. Any visible data is the previous observation.")
        }
    }

    fun send(action: String, args: JSONObject = JSONObject()) {
        if (state.value.busy || state.value.storageBlocked || state.value.pending != null) return
        val command =
            JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("action", action)
                .put("args", args)
        mutable.value = state.value.copy(busy = true, problem = null, outcome = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { save(command) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.value =
                    state.value.copy(
                        busy = false, problem = "Request could not be saved. Nothing new was sent.")
                return@launch
            }
            mutable.value = state.value.copy(pending = command)
            deliver(command)
        }
    }

    fun retry() {
        val before = state.value
        if (before.busy || before.storageBlocked || before.pending == null) return
        mutable.value = before.copy(busy = true, problem = null)
        viewModelScope.launch { deliver(before.pending) }
    }

    private suspend fun deliver(command: JSONObject) {
        try {
            val receipt = withContext(Dispatchers.IO) { api.request("/commands", command) }
            require(receipt.getString("commandId") == command.getString("id"))
            val outcome = receipt.getJSONObject("outcome")
            require(outcome.get("accepted") is Boolean)
            if (!outcome.getBoolean("accepted")) {
                val reason = outcome.get("reason")
                require(reason is String && reason.isNotBlank())
            }
            withContext(Dispatchers.IO) { save(null) }
            mutable.value = state.value.copy(pending = null, outcome = outcome)
            // The draft remains until explicit Close draft; its saved base can be compared after
            // restart.
            // An idempotent receipt can be historical. Never publish receipt.data as current.
            refreshInside()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutable.value =
                state.value.copy(
                    busy = false,
                    problem =
                        "This request is not acknowledged locally. Its identity is saved; retry the same request.")
        }
    }
}
