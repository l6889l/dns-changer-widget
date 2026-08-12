package com.example.dnschanger;

import com.example.dnschanger.util.PacketUtils;

import java.io.FileOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles a single TCP connection through the VPN tunnel.
 * Implements a user-space TCP proxy with sequence number translation.
 */
class TcpConnection {

    // 4-tuple identifying this connection
    final byte[] clientIp;
    final byte[] serverIp;
    final int clientPort;
    final int serverPort;

    private final FileOutputStream tunOut;
    private final Object tunLock;

    private SocketChannel serverChannel;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private enum State { SYN_RCVD, ESTABLISHED, CLOSED }
    private volatile State state = State.SYN_RCVD;

    // Sequence number tracking
    private int clientIsn = 0;          // ISN from client's SYN
    private int serverIsn = 0;           // ISN we chose for our SYN-ACK
    private int clientBytesReceived = 0; // Payload bytes received from client
    private int serverBytesSent = 0;     // Payload bytes sent to client

    TcpConnection(byte[] clientIp, int clientPort, byte[] serverIp, int serverPort,
                  FileOutputStream tunOut, Object tunLock) {
        this.clientIp = clientIp;
        this.clientPort = clientPort;
        this.serverIp = serverIp;
        this.serverPort = serverPort;
        this.tunOut = tunOut;
        this.tunLock = tunLock;
    }

    /**
     * Handle a SYN packet from the client.
     * Creates a socket to the destination, sends SYN-ACK, starts reader thread.
     */
    void handleSyn(int seq, byte[] clientOptions) {
        this.clientIsn = seq;
        this.serverIsn = PacketUtils.randomIsn();

        try {
            InetSocketAddress dest = new InetSocketAddress(
                    InetAddress.getByAddress(serverIp), serverPort);

            serverChannel = SocketChannel.open();
            serverChannel.configureBlocking(true);
            serverChannel.socket().connect(dest, 8000); // 8s timeout

            // Build TCP options for SYN-ACK: MSS + WSCALE + SACK_PERM
            byte[] opts = buildSynOptions();
            int dataOffsetWords = 5 + (opts.length / 4);

            // SYN-ACK packet (src=server, dst=client — reversed from client's SYN)
            byte[] synAck = PacketUtils.buildTcpPacket(
                    serverIp, clientIp,
                    serverPort, clientPort,
                    serverIsn,          // seq = our ISN
                    clientIsn + 1,      // ack = client ISN + 1
                    PacketUtils.TCP_SYN | PacketUtils.TCP_ACK,
                    65535,
                    dataOffsetWords,
                    opts,
                    null
            );
            writeToTun(synAck);
            state = State.SYN_RCVD;

            // Start reader thread to forward server→client data
            Thread reader = new Thread(new ServerReader(), "Tcp-" + serverPort + "-rd");
            reader.setDaemon(true);
            reader.start();

        } catch (Exception e) {
            sendRst(clientIsn + 1);
            closed.set(true);
        }
    }

    /** Handle an ACK or data packet from the client (already past SYN). */
    void handleAckOrData(byte[] packet, int ihl, int flags,
                         int tcpSeq, int payloadOffset, int payloadLen) {
        if (closed.get()) return;

        try {
            if (state == State.SYN_RCVD && PacketUtils.isTcpAck(flags)) {
                state = State.ESTABLISHED;
            }

            if (payloadLen > 0 && state == State.ESTABLISHED && serverChannel != null) {
                // Extract payload and forward to real server
                ByteBuffer buf = ByteBuffer.wrap(packet, payloadOffset, payloadLen);
                int written = serverChannel.write(buf);
                if (written > 0) {
                    clientBytesReceived += written;
                }

                // Send ACK to client acknowledging received data
                int ackSeq = serverIsn + 1 + serverBytesSent;
                int ackNum = clientIsn + 1 + clientBytesReceived;
                byte[] ack = PacketUtils.buildTcpPacket(
                        serverIp, clientIp,
                        serverPort, clientPort,
                        ackSeq, ackNum,
                        PacketUtils.TCP_ACK,
                        65535, 5, null, null
                );
                writeToTun(ack);
            }
        } catch (Exception e) {
            // Connection may have been closed
        }
    }

    /** Handle a FIN from the client (half-close). */
    void handleFin(int seq) {
        if (closed.get()) return;
        try {
            if (serverChannel != null) {
                serverChannel.shutdownOutput();
            }
            int ackSeq = serverIsn + 1 + serverBytesSent;
            byte[] ack = PacketUtils.buildTcpPacket(
                    serverIp, clientIp,
                    serverPort, clientPort,
                    ackSeq, seq + 1,
                    PacketUtils.TCP_ACK,
                    65535, 5, null, null
            );
            writeToTun(ack);
        } catch (Exception ignored) { }
    }

    /** Handle a RST from the client. */
    void handleRst() {
        closed.set(true);
        try { if (serverChannel != null) serverChannel.close(); } catch (Exception ignored) {}
    }

    /** Send RST to the client. */
    void sendRst(int seq) {
        try {
            byte[] rst = PacketUtils.buildTcpRst(serverIp, clientIp,
                    serverPort, clientPort, seq);
            writeToTun(rst);
        } catch (Exception ignored) {}
    }

    void closeConnection() {
        if (closed.compareAndSet(false, true)) {
            try { if (serverChannel != null) serverChannel.close(); } catch (Exception ignored) {}
        }
    }

    boolean isClosed() {
        return closed.get();
    }

    private void writeToTun(byte[] data) {
        synchronized (tunLock) {
            try { tunOut.write(data); } catch (Exception ignored) {}
        }
    }

    /** Build TCP options for SYN-ACK: NOP, MSS, WSCALE, SACK_PERM, padding. */
    private byte[] buildSynOptions() {
        byte[] opts = new byte[12];
        int p = 0;
        opts[p++] = 0x01;                    // NOP
        opts[p++] = 0x02; opts[p++] = 0x04;   // MSS
        opts[p++] = 0x05; opts[p++] = 0x78;   // 1400
        opts[p++] = 0x03; opts[p++] = 0x03;   // WSCALE
        opts[p++] = 0x07;                      // shift=7
        opts[p++] = 0x04; opts[p++] = 0x02;   // SACK permitted
        opts[p++] = 0x01;                      // padding
        return opts;
    }

    /**
     * Reader thread: reads data from the real server via SocketChannel
     * and writes response packets back to the TUN for the client.
     */
    private class ServerReader implements Runnable {
        @Override
        public void run() {
            ByteBuffer buf = ByteBuffer.allocate(4096);
            try {
                while (!closed.get() && serverChannel != null && serverChannel.isConnected()) {
                    buf.clear();
                    int read;
                    try {
                        read = serverChannel.read(buf);
                    } catch (Exception e) {
                        break;
                    }

                    if (read > 0) {
                        buf.flip();
                        byte[] data = new byte[read];
                        buf.get(data);

                        serverBytesSent += read;

                        byte[] tcpPacket = PacketUtils.buildTcpPacket(
                                serverIp, clientIp,
                                serverPort, clientPort,
                                serverIsn + 1 + (serverBytesSent - read), // seq of first byte
                                clientIsn + 1 + clientBytesReceived,        // ack
                                PacketUtils.TCP_ACK | PacketUtils.TCP_PSH,
                                65535, 5, null, data
                        );
                        writeToTun(tcpPacket);
                    } else if (read == -1) {
                        break; // Server closed connection
                    } else {
                        Thread.sleep(10);
                    }
                }
            } catch (Exception e) {
                // Connection error
            } finally {
                if (!closed.get()) {
                    closed.set(true);
                    try {
                        // Send FIN to client
                        int ackSeq = serverIsn + 1 + serverBytesSent;
                        byte[] fin = PacketUtils.buildTcpPacket(
                                serverIp, clientIp,
                                serverPort, clientPort,
                                ackSeq,
                                clientIsn + 1 + clientBytesReceived,
                                PacketUtils.TCP_ACK | PacketUtils.TCP_FIN,
                                65535, 5, null, null
                        );
                        writeToTun(fin);
                    } catch (Exception ignored) {}
                }
            }
        }
    }
}
