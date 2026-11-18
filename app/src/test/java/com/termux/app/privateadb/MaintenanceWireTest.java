package com.termux.app.privateadb;
import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;

public class MaintenanceWireTest {
    @Test public void overlengthNegativeAndTrailingFramesRejectBeforeAllocation() throws Exception {
        for(int length:new int[]{-1,0,2049,Integer.MAX_VALUE}) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();DataOutputStream data=new DataOutputStream(out);
            data.writeInt(MaintenanceWire.REQUEST_MAGIC);data.writeByte(2);data.writeInt(length);
            try{MaintenanceWire.readRequest(new ByteArrayInputStream(out.toByteArray()));fail("must reject size");}
            catch(IOException expected){}
        }
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        MaintenanceWire.writeRequest(out,"status","{\"version\":1,\"token\":\""+MaintenanceLeaseTest.HASH+"\"}");out.write(0);
        try{MaintenanceWire.readRequest(new ByteArrayInputStream(out.toByteArray()));fail("must reject trailing frame");}
        catch(IOException expected){}
        byte[] invalid=out.toByteArray();invalid[4]=99;
        try{MaintenanceWire.readRequest(new ByteArrayInputStream(invalid));fail("must reject unknown verb");}
        catch(IOException expected){}
    }
    @Test public void invalidUtf8AndMalformedJsonCannotEnterDispatcher() throws Exception {
        for(byte[] bytes:new byte[][]{new byte[]{(byte)0xc0,(byte)0xaf},"{\"version\":1,\"token\":\"bad\"}".getBytes("UTF-8")}) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();DataOutputStream data=new DataOutputStream(out);
            data.writeInt(MaintenanceWire.REQUEST_MAGIC);data.writeByte(2);data.writeInt(bytes.length);data.write(bytes);
            try{MaintenanceWire.readRequest(new ByteArrayInputStream(out.toByteArray()));fail("must reject");}
            catch(IOException | IllegalArgumentException expected){}
        }
    }
    @Test public void requestAndReplyHaveFixedBoundedFramesAndStrictJson() throws Exception {
        String token=MaintenanceLeaseTest.HASH;
        String payload="{\"version\":1,\"token\":\""+token+"\"}";
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        MaintenanceWire.writeRequest(out,"status",payload);
        MaintenanceWire.Request request=MaintenanceWire.readRequest(new ByteArrayInputStream(out.toByteArray()));
        assertEquals("status",request.verb);assertEquals(payload,request.payload);
        out.reset();MaintenanceWire.writeResponse(out,"{\"version\":1,\"phase\":\"pending\"}");
        assertEquals("{\"version\":1,\"phase\":\"pending\"}",MaintenanceWire.readResponse(new ByteArrayInputStream(out.toByteArray())));
    }
}
