package com.termux.app.privateadb;

import java.io.InputStream;
import java.io.PrintStream;
import org.json.JSONObject;

/** app_process entry: public LocalSocket only, never a second coordinator or app bootstrap. */
@androidx.annotation.Keep
public final class MaintenanceCli {
    public static void main(String[] args) { System.exit(run(args,System.in,System.out)); }
    static int run(String[] args,InputStream input,PrintStream output) {
        final String payload;
        try {
            if(args==null || args.length!=1 || !("request".equals(args[0]) || "status".equals(args[0]) || "release".equals(args[0])))
                return error(output,2,"invalid_request");
            payload=MaintenanceProtocol.readPayload(input);MaintenanceProtocol.parse(args[0],payload);
        } catch(java.io.IOException | IllegalArgumentException e){return error(output,2,"invalid_request");}
        try {
            String result=MaintenanceSocket.exchange(args[0],payload);
            JSONObject json=new JSONObject(result);
            if(!Integer.valueOf(1).equals(json.get("version")) ||
                !((json.opt("phase") instanceof String) || (json.opt("error") instanceof String)))
                return error(output,1,"failed");
            output.print(json.toString()+"\n");return 0;
        } catch(MaintenanceSocket.Unavailable | MaintenanceTransport.PeerRejected e){return error(output,3,"unsupported");}
        catch(java.io.IOException | org.json.JSONException | RuntimeException | LinkageError e){return error(output,1,"failed");}
    }
    private static int error(PrintStream output,int code,String error){
        output.print("{\"version\":1,\"error\":\""+error+"\"}\n");return code;
    }
    private MaintenanceCli() {}
}
