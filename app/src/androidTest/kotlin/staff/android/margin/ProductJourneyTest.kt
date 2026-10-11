package staff.android.margin
import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
@RunWith(AndroidJUnit4::class)
class ProductJourneyTest {
 @get:Rule(order=0)val permission=GrantPermissionRule.grant(Manifest.permission.ACCESS_LOCAL_NETWORK)
 @get:Rule(order=1)val compose=createAndroidComposeRule<MainActivity>()
 @Test fun completeCaseJourney(){try{
            waitTag("edit-p-arrival");tap("edit-p-arrival");waitTag("draft")
            compose.onNodeWithTag("draft").performScrollTo().performTextReplacement("Meet at the north studio at 09:30.")
            tap("keep");waitReady()
            command("remote",JSONObject().put("paragraphId","p-arrival").put("baseRevision",1).put("text","Meet at the garden studio at 09:00."))
            tap("save");waitTag("review-conflict")
            compose.onNodeWithTag("draft").performScrollTo().performTextReplacement("Meet at the garden studio at 09:30.")
            tap("review-conflict");tap("rebase");waitReady();request("/__control","{\"drop_reply\":true}");tap("save");waitTag("retry")
            request("/__control","{}");tap("retry");waitReady()
            val row=current().getJSONArray("paragraphs").getJSONObject(0)
            assertEquals(3,row.getInt("revision"));assertEquals("Meet at the garden studio at 09:30.",row.getString("text"))
            compose.onNodeWithText("Save acknowledged").assertExists()
            tap("discard");tap("confirm-discard");waitTag("edit-p-supplies")
            tap("edit-p-supplies");waitTag("draft")
            compose.onNodeWithText("Save acknowledged").assertDoesNotExist()
            assertEquals("Bring pencils and a notebook.", current().getJSONArray("paragraphs").getJSONObject(1).getString("text"))
 }finally{request("/__control","{}")}}
 private fun context()=InstrumentationRegistry.getInstrumentation().targetContext
 private fun current()=request("/view").getJSONObject("data")
 private fun command(action:String,args:JSONObject)=request("/commands",JSONObject().put("id",UUID.randomUUID().toString()).put("action",action).put("args",args).toString())
 private fun tap(tag:String){
        val node = compose.onNodeWithTag(tag)
        if (tag in setOf("refresh", "lab-tab", "rebase", "confirm-discard")) node.performClick() else node.performScrollTo().performClick()
    }
 private fun waitTag(tag:String){compose.waitUntil(45000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()};waitReady()}
 private fun waitReady(){compose.waitUntil(45000){compose.onAllNodesWithTag("busy").fetchSemanticsNodes().isEmpty()}}
 private fun request(path:String,body:String?=null):JSONObject{
  val connection=URL("http://10.0.2.2:8919$path").openConnection() as HttpURLConnection
  connection.connectTimeout=10000;connection.readTimeout=10000
  if(body!=null){connection.requestMethod="POST";connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");connection.outputStream.use{it.write(body.toByteArray())}}
  return try{JSONObject(connection.inputStream.bufferedReader().use{it.readText()})}finally{connection.disconnect()}
 }
}
