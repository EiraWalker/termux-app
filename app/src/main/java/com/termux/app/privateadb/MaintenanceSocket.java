package com.termux.app.privateadb;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fixed abstract AF_UNIX endpoint, owned exclusively by the normal guardian Service. */
final class MaintenanceSocket implements Closeable {
    private static final String NAME="com.termux.privateadb.maintenance.";
    static final class Unavailable extends IOException {}
    static final int IO_TIMEOUT_MS=3000, SERVER_DEADLINE_MS=5000, CLIENT_DEADLINE_MS=6000;
    private final LocalServerSocket server;
    private final LocalSocket owner;
    private final Context context;
    private final int uid;
    private final AtomicBoolean closed=new AtomicBoolean();
    private final ExecutorService listener=Executors.newSingleThreadExecutor(r -> daemon(r,"PrivateAdbMaintenanceSocket"));
    private final ScheduledExecutorService timeout=Executors.newSingleThreadScheduledExecutor(r -> daemon(r,"PrivateAdbMaintenanceTimeout"));
    private volatile LocalSocket active;
    private static Thread daemon(Runnable runnable,String name){Thread t=new Thread(runnable,name);t.setDaemon(true);return t;}
    static String endpointName() { return NAME+Process.myUid(); }
    private static boolean appUid(int uid){int appId=uid%100000;return appId>=10000 && appId<=19999;}
    MaintenanceSocket(Context context) throws IOException {
        uid=Process.myUid();
        if(!appUid(uid) || context.getApplicationInfo().uid!=uid)throw new IOException("owner rejected");
        this.context=context;
        // Own the FD even if bind/listen fails; a name conflict never takes over/unlinks.
        owner=new LocalSocket();
        try {
            owner.bind(new LocalSocketAddress(endpointName(),LocalSocketAddress.Namespace.ABSTRACT));
            server=new LocalServerSocket(owner.getFileDescriptor());
        } catch(IOException | RuntimeException failed) {closeClient(owner);throw failed;}
        listener.execute(this::listen);
    }
    private void listen() {
        try {
            while(!closed.get()) {
                LocalSocket client=server.accept();
                ScheduledFuture<?> deadline=null;
                try {
                    if(closed.get())break;
                    active=client;
                    deadline=timeout.schedule(() -> closeClient(client),SERVER_DEADLINE_MS,TimeUnit.MILLISECONDS);
                    client.setSoTimeout(IO_TIMEOUT_MS);
                    MaintenanceTransport.serve(new Peer(client),uid,(verb,payload) -> MaintenanceCoordinator.call(context,verb,payload));
                } catch(IOException | IllegalArgumentException ignored) {
                    // Never log caller input, token, peer credentials or framework exceptions.
                } finally {if(deadline!=null)deadline.cancel(false);active=null;closeClient(client);}
            }
        } catch(IOException | RuntimeException ignored) { /* Fail closed; no replacement endpoint. */ }
        finally {close();}
    }
    static String exchange(String verb,String payload) throws IOException {
        int uid=Process.myUid();
        if(!appUid(uid))throw new Unavailable();
        LocalSocket socket=new LocalSocket();
        MaintenanceTransport.Endpoint endpoint=new MaintenanceTransport.Endpoint(){
            public MaintenanceTransport.Connection connect() throws IOException {
                // Android 15's connect(address,timeout) throws UnsupportedOperationException.
                // The outer bounded task covers connect as well as read/write/EOF.
                try{socket.connect(new LocalSocketAddress(endpointName(),LocalSocketAddress.Namespace.ABSTRACT));}
                catch(IOException failure){throw new Unavailable();}
                socket.setSoTimeout(IO_TIMEOUT_MS);return new Peer(socket);
            }
            public void cancel(){closeClient(socket);}
        };
        return MaintenanceTransport.exchange(endpoint,uid,verb,payload,CLIENT_DEADLINE_MS);
    }
    private static final class Peer implements MaintenanceTransport.Connection {
        private final LocalSocket socket;
        Peer(LocalSocket socket){this.socket=socket;}
        public int peerUid() throws IOException {
            android.net.Credentials credentials=socket.getPeerCredentials();
            if(credentials==null)throw new IOException("peer unavailable");
            return credentials.getUid();
        }
        public InputStream input() throws IOException{return socket.getInputStream();}
        public OutputStream output() throws IOException{return socket.getOutputStream();}
        public void finishOutput() throws IOException{socket.shutdownOutput();}
        public void close(){closeClient(socket);}
    }
    private static void closeClient(LocalSocket socket){
        if(socket==null)return;
        try{socket.shutdownInput();}catch(IOException | RuntimeException ignored){}
        try{socket.shutdownOutput();}catch(IOException | RuntimeException ignored){}
        try{socket.close();}catch(IOException | RuntimeException ignored){}
    }
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;
        closeClient(active);
        // shutdown wakes an outstanding accept; close alone need not interrupt Linux accept.
        try{Os.shutdown(server.getFileDescriptor(),OsConstants.SHUT_RDWR);}catch(Exception ignored){}
        try{server.close();}catch(IOException ignored){}
        closeClient(owner); // LocalServerSocket(fd) deliberately does not own that supplied FD.
        timeout.shutdownNow();listener.shutdownNow();
    }
}
