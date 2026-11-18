package com.termux.app.privateadb;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** One fixed frame in each direction, then EOF. No configurable paths or commands. */
final class MaintenanceWire {
    static final int REQUEST_MAGIC=0x544d5131, RESPONSE_MAGIC=0x544d5231;
    static final int MAX_REPLY_BYTES=4096;
    static final class Request {
        final String verb,payload;
        Request(String verb,String payload) { this.verb=verb;this.payload=payload; }
    }
    static void writeRequest(OutputStream output,String verb,String payload) throws IOException {
        MaintenanceProtocol.parse(verb,payload);
        DataOutputStream out=new DataOutputStream(output);
        out.writeInt(REQUEST_MAGIC);out.writeByte(code(verb));
        writeText(out,payload,MaintenanceProtocol.MAX_PAYLOAD_BYTES);out.flush();
    }
    static Request readRequest(InputStream input) throws IOException {
        DataInputStream in=new DataInputStream(input);
        if (in.readInt()!=REQUEST_MAGIC) throw new IOException("invalid frame");
        String verb=verb(in.readUnsignedByte());
        String payload=readText(in,MaintenanceProtocol.MAX_PAYLOAD_BYTES);
        if (in.read()!=-1) throw new IOException("trailing frame");
        MaintenanceProtocol.parse(verb,payload);
        return new Request(verb,payload);
    }
    static void writeResponse(OutputStream output,String payload) throws IOException {
        DataOutputStream out=new DataOutputStream(output);
        out.writeInt(RESPONSE_MAGIC);writeText(out,payload,MAX_REPLY_BYTES);out.flush();
    }
    static String readResponse(InputStream input) throws IOException {
        DataInputStream in=new DataInputStream(input);
        if(in.readInt()!=RESPONSE_MAGIC)throw new IOException("invalid frame");
        String payload=readText(in,MAX_REPLY_BYTES);
        if(in.read()!=-1)throw new IOException("trailing frame");
        return payload;
    }
    private static int code(String verb) {
        if("request".equals(verb))return 1;
        if("status".equals(verb))return 2;
        if("release".equals(verb))return 3;
        throw new IllegalArgumentException("invalid verb");
    }
    private static String verb(int code) throws IOException {
        if(code==1)return "request";
        if(code==2)return "status";
        if(code==3)return "release";
        throw new IOException("invalid verb");
    }
    private static void writeText(DataOutputStream out,String text,int max) throws IOException {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        if(bytes.length==0 || bytes.length>max)throw new IOException("invalid size");
        out.writeInt(bytes.length);out.write(bytes);
    }
    private static String readText(DataInputStream in,int max) throws IOException {
        int length=in.readInt();
        if(length<=0 || length>max)throw new IOException("invalid size");
        byte[] bytes=new byte[length];in.readFully(bytes);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private MaintenanceWire() {}
}
