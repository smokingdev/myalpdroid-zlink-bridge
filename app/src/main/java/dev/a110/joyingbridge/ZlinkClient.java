package dev.a110.joyingbridge;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

/** A receive-only connection; never sends commands or registers through vendor libraries. */
final class ZlinkClient implements AutoCloseable {
    interface Listener {
        void status(String status);
        void raw(byte[] bytes, int length);
        void frame(long type, byte[] payload);
        void error(String message);
        void ended();
    }
    private final Socket socket = new Socket();
    private volatile boolean closed, alive = true;
    private final Thread thread;
    ZlinkClient(Listener listener) { this("127.0.0.1", 1555, listener); }
    ZlinkClient(String host, int port, Listener listener) {
        thread = new Thread(() -> receive(host, port, listener), "zlink-metadata"); thread.start();
    }
    boolean alive() { return alive; }
    private void receive(String host, int port, Listener listener) {
        ZlinkFrameDecoder decoder = new ZlinkFrameDecoder(listener::frame);
        boolean decode = true;
        try {
            if (closed) return;
            listener.status("Connecting to ZLink");
            socket.connect(new InetSocketAddress(host, port), 3000); socket.setSoTimeout(2000);
            listener.status("ZLink TCP connected; waiting for metadata");
            InputStream input = socket.getInputStream(); byte[] bytes = new byte[16384];
            while (!closed) {
                int n;
                try { n = input.read(bytes); }
                catch (SocketTimeoutException timeout) { continue; }
                if (n < 0) { if (!closed) listener.status("ZLink closed connection; retrying in 15 seconds"); break; }
                if (n == 0) continue;
                listener.raw(bytes, n);
                if (decode) {
                    try { decoder.feed(bytes, n); }
                    catch (ZlinkFrameDecoder.FormatException e) {
                        decode = false; listener.error(e.getMessage() + "; retaining raw bytes only");
                    }
                }
            }
        } catch (Exception e) { if (!closed) listener.status("ZLink connection failed: " + e.getMessage()); }
        finally {
            try { socket.close(); } catch (IOException ignored) { }
            if (!closed) listener.error("ZLink reader ended; partial frame: " + decoder.pending());
            if (!closed) listener.ended();
            alive = false;
        }
    }
    @Override public void close() {
        closed = true;
        try { socket.close(); } catch (IOException ignored) { }
        thread.interrupt();
    }
}
