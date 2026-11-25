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

    /** v2.22 #163: the web settings approval (Deny, Allow read-only, Allow) starts on Deny in every language. */
    @Test
    public void webApprovalStartsOnDenyInEveryLanguage() throws Exception {
        java.io.File[] dirs = new java.io.File("src/main/res").listFiles((d, n) -> n.startsWith("values"));
        int seen = 0;
        for (java.io.File d : dirs) {
            java.io.File f = new java.io.File(d, "strings.xml");
            if (!f.isFile()) continue;
            String x = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            String deny = str(x, "web_deny"), read = str(x, "web_allow_read"), allow = str(x, "web_allow");
            if (deny == null) continue;
            // the order OmarchyLink.webChoices() gives, and LinkServer's default
            assertEquals(d.getName(), 0, AgentQuestionActivity.startFocus(Arrays.asList(deny, read, allow)));
            seen++;
        }
        assertTrue(seen >= 24);
        assertEquals(0, AgentQuestionActivity.startFocus(Arrays.asList("Deny", "Allow read-only", "Allow")));
    }

    private static String str(String xml, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<string name=\"" + name + "\">([^<]*)</string>").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    @Test
    public void okIsIgnoredRightAfterTheQuestionAppears() {
        assertTrue(AgentQuestionActivity.OK_GUARD_MS >= 1000);
    }
}
