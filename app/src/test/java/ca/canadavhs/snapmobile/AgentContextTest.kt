package ca.canadavhs.snapmobile

import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class AgentContextTest {
    private fun row(role:String,body:String="",at:Long=100,attachments:String="[]",calls:String="[]",callId:String="")=ChatMessageRow(conversationId="fixture",role=role,body=body,createdAt=at,attachmentsJson=attachments,toolCallsJson=calls,toolCallId=callId)
    @Test fun modesDescribeAppBoundariesWithoutClaimingInspection() {
        val c=ChatConversationRow(title="Fixture",provider="OPENAI",model="fixture",authMode="API_KEY")
        for(mode in AgentPermission.entries) {val chat=c.copy(toolsEnabled=mode!=AgentPermission.CHAT_ONLY,proposalsEnabled=mode==AgentPermission.FULL_AGENT);val prompt=AgentContext.instructions(chat);assertEquals(mode,AgentPermission.of(chat));assertTrue(prompt.contains(mode.label));assertTrue(prompt.contains("SnapIMS Next"));assertTrue(prompt.contains("Never claim inspection"));assertTrue(prompt.contains("untrusted data"));assertTrue(prompt.contains("No monitoring"));assertTrue(prompt.contains("Never attach/send inventory photos automatically"))}
    }
    @Test fun contextEpochExcludesEarlierPrivateToolHistoryAndRetainsLocalRecords() {
        val history=listOf(row("user","Old request",1),row("tool","Private result",2),row("user","New request",100),row("assistant","New answer",101))
        val window=AgentContext.window(history,AgentPolicyRow("fixture",changedAt=100));assertEquals(listOf("New request","New answer"),window.map {it.body});assertEquals(4,history.size)
    }
    @Test fun previousAttachmentsAreNotResentButExplicitCurrentAttachmentIsIncluded() {
        val rows=listOf(row("user","Old image",attachments="[{\"name\":\"old.jpg\"}]"),row("assistant","Observed old image"),row("user","Current image",attachments="[{\"name\":\"new.jpg\"}]"))
        val window=AgentContext.window(rows,AgentPolicyRow("fixture"));assertEquals("[]",window.first().attachmentsJson);assertTrue(window.last().attachmentsJson.contains("new.jpg"));assertTrue(rows.first().attachmentsJson.contains("old.jpg"))
    }
    @Test fun boundedContextRetainsWholeToolChainsAndDropsOldIncompleteTurns() {
        val call="[{\"type\":\"function_call\",\"name\":\"app_overview\",\"call_id\":\"call1\",\"arguments\":\"{}\"}]"
        val old=listOf(row("user","Incomplete old turn"),row("assistant",calls=call))
        val current=listOf(row("user","Current request"),row("assistant",calls=call),row("tool","{\"count\":1}",callId="call1"))
        val window=AgentContext.window(old+current,AgentPolicyRow("fixture",contextChars=8000));assertEquals(current.map {it.role},window.map {it.role});assertEquals("call1",window.last().toolCallId)
    }
    @Test fun oversizedCurrentTurnIsRejectedBeforeModelRequestAndLargeEarlierResultsAreSummarized() {
        try {AgentContext.window(listOf(row("user","X".repeat(8001))),AgentPolicyRow("fixture",contextChars=8000));fail()} catch(_:IllegalStateException) {}
        val call="[{\"name\":\"app_overview\",\"call_id\":\"call1\"}]"
        val rows=listOf(row("user","Old request"),row("assistant",calls=call),row("tool","X".repeat(15000),callId="call1"),row("user","Current request"))
        val window=AgentContext.window(rows,AgentPolicyRow("fixture",contextChars=8000));assertTrue(window.sumOf {it.body.length+it.toolCallsJson.length}<8000);assertTrue(window.first {it.role=="tool"}.body.contains("contextPreview"));assertEquals("Current request",window.last().body)
    }
    @Test fun invalidBudgetsNeverExpandAccessOrRequestLimits() {
        for(policy in listOf(AgentPolicyRow("fixture",contextChars=7999),AgentPolicyRow("fixture",contextChars=64001),AgentPolicyRow("fixture",maxRounds=13),AgentPolicyRow("fixture",maxCalls=49))) try {AgentContext.window(emptyList(),policy);fail()} catch(_:IllegalArgumentException) {}
    }
    @Test fun currentToolResultsAreCompleteAndKeepTheirPaginationAndFieldChunks() {
        val value=JSONObject().put("text","R".repeat(4000)).put("nextOffset",4000).put("totalCharacters",12000).toString()
        val call="[{\"name\":\"app_read\",\"call_id\":\"field-call\"}]"
        val current=listOf(row("user","Read the entire field"),row("assistant",calls=call),row("tool",value,callId="field-call"))
        val window=AgentContext.window(current,AgentPolicyRow("fixture"));assertEquals(value,window.last().body);assertEquals(4000,JSONObject(window.last().body).getString("text").length);assertEquals(4000,JSONObject(window.last().body).getInt("nextOffset"))
    }

}
