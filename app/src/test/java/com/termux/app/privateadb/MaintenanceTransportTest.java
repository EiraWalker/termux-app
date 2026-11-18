package com.termux.app.privateadb;
import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;

public class MaintenanceTransportTest {
    static final class Connection implements MaintenanceTransport.Connection {
        int uid=10001;InputStream input;ByteArrayOutputStream output=new ByteArrayOutputStream();boolean closed,finished;
        Connection(byte[] bytes){input=new ByteArrayInputStream(bytes);}
        public int peerUid(){return uid;}
        public InputStream input(){return input;}
        public OutputStream output(){return output;}
        public void finishOutput(){finished=true;}
        public void close(){closed=true;}
    }
    byte[] request() throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        MaintenanceWire.writeRequest(out,"status","{\"version\":1,\"token\":\""+MaintenanceLeaseTest.HASH+"\"}");
        return out.toByteArray();
    }
    @Test public void serverRoutesFixedVerbOnlyAfterExactPeerUidAndStrictFrame() throws Exception {
        Connection c=new Connection(request());
        MaintenanceTransport.serve(c,10001,(verb,payload) -> {
            assertEquals("status",verb);return "{\"version\":1,\"phase\":\"pending\"}";
        });
        assertTrue(c.finished);
        assertEquals("{\"version\":1,\"phase\":\"pending\"}",MaintenanceWire.readResponse(new ByteArrayInputStream(c.output.toByteArray())));
        for(int foreign:new int[]{0,2000,10002}) {
            Connection bad=new Connection(request());bad.uid=foreign;
            try {MaintenanceTransport.serve(bad,10001,(v,p) -> {fail("must not dispatch foreign UID");return "";});fail("must reject");}
            catch(IOException expected){}
            assertEquals(0,bad.output.size());
        }
    }
    @Test public void clientRejectsWrongUidBeforeSendingAnyPayload() throws Exception {
        ByteArrayOutputStream reply=new ByteArrayOutputStream();MaintenanceWire.writeResponse(reply,"{\"version\":1,\"phase\":\"pending\"}");
        for(int foreign:new int[]{0,2000,10002}) {
            Connection connection=new Connection(reply.toByteArray());connection.uid=foreign;
            MaintenanceTransport.Endpoint endpoint=new MaintenanceTransport.Endpoint(){
                public MaintenanceTransport.Connection connect(){return connection;}
                public void cancel(){connection.close();}
            };
            try{MaintenanceTransport.exchange(endpoint,10001,"status","{\"version\":1,\"token\":\""+MaintenanceLeaseTest.HASH+"\"}",1000);fail("must reject");}
            catch(MaintenanceTransport.PeerRejected expected){}
            assertEquals(0,connection.output.size());assertTrue(connection.closed);
        }
    }
    @Test public void malformedServerFrameNeverReachesCoordinator() throws Exception {
        byte[] valid=request();
        for(byte[] bad:new byte[][]{new byte[0],new byte[]{1,2,3,4},java.util.Arrays.copyOf(valid,valid.length-1)}) {
            try{MaintenanceTransport.serve(new Connection(bad),10001,(v,p) -> {fail("must not dispatch malformed frame");return "";});fail("must reject");}
            catch(IOException expected){}
        }
    }
    @Test public void connectTimeoutIsBoundedAndCancelsOwnedEndpoint() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        MaintenanceTransport.Endpoint endpoint=new MaintenanceTransport.Endpoint(){
            public MaintenanceTransport.Connection connect() throws IOException {
                try {new java.util.concurrent.CountDownLatch(1).await();throw new IOException();}
                catch(InterruptedException e){throw new IOException();}
            }
            public void cancel(){cancelled.set(true);}
        };
        long start=System.nanoTime();
        try{MaintenanceTransport.exchange(endpoint,10001,"status","{\"version\":1,\"token\":\""+MaintenanceLeaseTest.HASH+"\"}",50);fail("must timeout");}
        catch(java.net.SocketTimeoutException expected){}
        assertTrue(cancelled.get());
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<2000);
    }
}
