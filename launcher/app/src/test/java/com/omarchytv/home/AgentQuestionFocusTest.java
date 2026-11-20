package com.omarchytv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

/** v2.22: a reflex OK on the remote never approves: the focus never starts on a yes. */
public class AgentQuestionFocusTest {
    @Test
    public void approvalsStartOnThePc() {
        assertEquals(2, AgentQuestionActivity.startFocus(Arrays.asList("Allow", "Deny", "On the PC")));
    }

    @Test
    public void otherQuestionsSkipTheYes() {
        assertEquals(1, AgentQuestionActivity.startFocus(Arrays.asList("Yes", "No")));
        assertEquals(1, AgentQuestionActivity.startFocus(Arrays.asList("OK", "Later")));
        assertEquals(1, AgentQuestionActivity.startFocus(Arrays.asList("Approve deploy", "Wait")));
        assertEquals(0, AgentQuestionActivity.startFocus(Arrays.asList("Merge", "Wait")));
        assertEquals(0, AgentQuestionActivity.startFocus(Arrays.asList("Yes")));
    }

    @Test
    public void okIsIgnoredRightAfterTheQuestionAppears() {
        assertTrue(AgentQuestionActivity.OK_GUARD_MS >= 1000);
    }
}
