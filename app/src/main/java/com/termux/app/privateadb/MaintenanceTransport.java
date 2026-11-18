package com.termux.app.privateadb;

import java.io.*;
import java.net.SocketTimeoutException;
import java.util.concurrent.*;

/** Small testable framing/UID boundary; it cannot create or acknowledge a lease. */
final class MaintenanceTransport {
    interface Connection extends Closeable {
        int peerUid() throws IOException;
        InputStream input() throws IOException;
        OutputStream output() throws IOException;
        void finishOutput() throws IOException;
    }
    interface Endpoint { Connection connect() throws IOException; void cancel(); }
    interface Dispatcher { String call(String verb,String payload); }
    static final class PeerRejected extends IOException {}
    static void serve(Connection connection,int uid,Dispatcher dispatcher) throws IOException {
        if(connection.peerUid()!=uid)throw new IOException("peer rejected");
        MaintenanceWire.Request request=MaintenanceWire.readRequest(connection.input());
        String response=dispatcher.call(request.verb,request.payload);
        MaintenanceWire.writeResponse(connection.output(),response);connection.finishOutput();
    }
    static String exchange(Endpoint endpoint,int uid,String verb,String payload,long timeoutMs) throws IOException {
        MaintenanceProtocol.parse(verb,payload);
        ExecutorService worker=Executors.newSingleThreadExecutor(r -> {
            Thread thread=new Thread(r,"PrivateAdbMaintenanceCli");thread.setDaemon(true);return thread;
        });
        Future<String> result=worker.submit(() -> {
            try(Connection connection=endpoint.connect()) {
                if(connection.peerUid()!=uid)throw new PeerRejected();
                MaintenanceWire.writeRequest(connection.output(),verb,payload);connection.finishOutput();
                return MaintenanceWire.readResponse(connection.input());
            }
        });
        try {return result.get(timeoutMs,TimeUnit.MILLISECONDS);}
        catch(TimeoutException e){throw new SocketTimeoutException("maintenance transport timeout");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("interrupted");}
        catch(ExecutionException e){
            if(e.getCause() instanceof IOException)throw (IOException)e.getCause();
            throw new IOException("maintenance transport failed");
        }
        finally {endpoint.cancel();result.cancel(true);worker.shutdownNow();}
    }
    private MaintenanceTransport() {}
}
