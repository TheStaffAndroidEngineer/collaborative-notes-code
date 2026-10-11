package staff.android.margin

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

class DraftStore(directory: File) {
    private val file = AtomicFile(File(directory, "paragraph-draft.json"))

    fun read(): JSONObject? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val wrapper = JSONObject(String(file.readFully(), Charsets.UTF_8))
        require(wrapper.has("draft"))
        if (wrapper.isNull("draft")) return null
        return wrapper.getJSONObject("draft").also { d ->
            require(d.getString("paragraphId").isNotBlank() && d.getString("baseText").isNotBlank())
            require(d.get("baseRevision") is Int && d.getInt("baseRevision") > 0)
            require(d.getString("text").length <= 2000)
        }
    }

    fun save(draft: JSONObject?) {
        val out = file.startWrite()
        try {
            out.write(JSONObject().put("draft", draft ?: JSONObject.NULL).toString().toByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }
}
