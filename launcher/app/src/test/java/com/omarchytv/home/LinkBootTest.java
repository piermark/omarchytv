package com.omarchytv.home;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Omarchy Link keep-alive: when it runs (a paired PC) and the manifest contract Android 14 enforces. */
public class LinkBootTest {

    private static String manifest() throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/AndroidManifest.xml")), StandardCharsets.UTF_8);
    }

    private static String element(String m, String tag, String name) {
        Matcher x = Pattern.compile("<" + tag + "\\s[^>]*android:name=\"" + Pattern.quote(name) + "\"[^>]*?(/>|>[\\s\\S]*?</" + tag + ">)").matcher(m);
        assertTrue(tag + " " + name + " declared", x.find());
        return x.group();
    }

    @Test
    public void keepAliveRunsOnlyWithAPairedPc() {
        assertTrue(LinkBoot.pairedIn("{\"tvId\":\"x\",\"pcs\":{\"pc1\":{\"name\":\"Omarchy\",\"token\":\"AAAA\"}}}"));
        assertFalse(LinkBoot.pairedIn("{\"tvId\":\"x\",\"pcs\":{}}"));
        assertFalse(LinkBoot.pairedIn("{\"tvId\":\"x\"}"));
        assertFalse(LinkBoot.pairedIn("not json"));
        assertFalse(LinkBoot.pairedIn(""));
    }

    @Test
    public void keepAliveIsAConnectedDeviceForegroundServiceWithItsPermissions() throws Exception {
        String m = manifest();
        // Android 14 (targetSdk 34): a foreground service needs its type, the type permission and, for
        // connectedDevice, one of CHANGE_NETWORK_STATE / CHANGE_WIFI_STATE / CHANGE_WIFI_MULTICAST_STATE.
        assertTrue(element(m, "service", ".OmarchyLink$KeepAlive").contains("android:foregroundServiceType=\"connectedDevice\""));
        assertTrue(m.contains("android.permission.FOREGROUND_SERVICE\""));
        assertTrue(m.contains("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE\""));
        assertTrue(m.contains("android.permission.CHANGE_NETWORK_STATE\""));
    }

    @Test
    public void linkStartsAgainAfterBootAndAfterAnUpdate() throws Exception {
        String receiver = element(manifest(), "receiver", ".HomeCheck");
        assertTrue(receiver.contains("android.intent.action.BOOT_COMPLETED"));
        assertTrue(receiver.contains("android.intent.action.MY_PACKAGE_REPLACED"));
    }
}
