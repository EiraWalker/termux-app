package com.termux.app.privateadb;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import java.io.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=28)
public class MaintenanceCliTest {
    @Test public void rootAndShellAreNotAppOwnersAndNeverBootstrapAnotherCoordinator() throws Exception {
        int prior=android.os.Process.myUid();
        try {
            for(int uid:new int[]{0,2000}) {
                org.robolectric.shadows.ShadowProcess.setUid(uid);
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                int code=MaintenanceCli.run(new String[]{"status"},new ByteArrayInputStream(("{\"version\":1,\"token\":\""+MaintenanceLeaseTest.HASH+"\"}").getBytes("UTF-8")),new PrintStream(out));
                assertEquals(3,code);assertEquals("{\"version\":1,\"error\":\"unsupported\"}\n",out.toString("UTF-8"));
            }
        } finally {org.robolectric.shadows.ShadowProcess.setUid(prior);}
    }
    @Test public void invalidVerbAndMalformedInputFailBeforeTransport() throws Exception {
        for(String verb:new String[]{"status","capabilities","shell"}) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            assertEquals(2,MaintenanceCli.run(new String[]{verb},new ByteArrayInputStream("{}".getBytes("UTF-8")),new PrintStream(out)));
            assertEquals("{\"version\":1,\"error\":\"invalid_request\"}\n",out.toString("UTF-8"));
        }
    }
}
