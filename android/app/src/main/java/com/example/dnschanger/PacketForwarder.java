package com.example.dnschanger;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.example.dnschanger.util.PacketUtils;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PacketForwarder reads IP packets from the TUN interface and forwards them
 * to the real network. Supports UDP (DatagramChannel) and TCP (SocketChannel +
 * user-space TCP state tracking in TcpConnection).
 */
public class PacketForwarder implements Runnable {

    private static final String TAG = "PacketForwarder";
    private static final int BUFFER_SIZE = 32767;

    private final ParcelFileDescriptor vpnInterface;
    private final FileInputStream in;
    private final FileOutputStream out;
    private final Object tunLock = new Object();

    private final Thread thread;
    private volatile boolean running = true;

    // TCP connections keyed by "srcIP:srcPort→dstIP:dstPort"
    private final Map<String, TcpConnection> tcpConnections = new ConcurrentHashMap<>();
    // Thread pool for asynchronous UDP forwarding
    private final ExecutorService udpExecutor = Executors.newFixedThreadPool(4,
            r -> { Thread t = new Thread(r, "UdpForwarder"); t.setDaemon(true); return t; });

    public PacketForwarder(ParcelFileDescriptor vpnInterface) {
        this.vpnInterface = vpnInterface;
        this.in = new FileInputStream(vpnInterface.getFileDescriptor());
        this.out = new FileOutputStream(vpnInterface.getFileDescriptor());
        this.thread = new Thread(this, "PacketForwarder");
    }

    public void start() {
        thread.start();
    }

    public void stop() {
        running = false;
        try { if (vpnInterface != null) vpnInterface.close(); } catch (Exception ignored) {}
        udpExecutor.shutdownNow();
        for (TcpConnection conn : tcpConnections.values()) conn.closeConnection();
        tcpConnections.clear();
    }

    private static String key(byte[] srcIp, int srcPort, byte[] dstIp, int dstPort) {
        return PacketUtils.ipToString(srcIp) + ":" + srcPort +
               "→" + PacketUtils.ipToString(dstIp) + ":" + dstPort;
    }

    @Override
    public void run() {
        Log.d(TAG, "Forwarder started");
        byte[] buffer = new byte[BUFFER_SIZE];

        while (running) {
            try {
                int length = in.read(buffer);
                if (length <= 0) continue;
                if (length < 20 || PacketUtils.getIpHeaderLength(buffer) > length) continue;

                int protocol = PacketUtils.getIpProtocol(buffer);
                int ihl = PacketUtils.getIpHeaderLength(buffer);

                if (protocol == PacketUtils.IP_PROTO_UDP) {
                    handleUdp(buffer, length, ihl);
                } else if (protocol == PacketUtils.IP_PROTO_TCP) {
                    handleTcp(buffer, length, ihl);
                }
            } catch (Exception e) {
                if (running) Log.w(TAG, "Forwarder error", e);
            }
        }
        Log.d(TAG, "Forwarder stopped");
    }

    private void handleUdp(byte[] packet, int length, int ihl) {
        if (ihl + 8 > length) return;

        int srcPort = PacketUtils.getUdpSrcPort(packet, ihl);
        int dstPort = PacketUtils.getUdpDstPort(packet, ihl);
        byte[] srcIp = PacketUtils.getSrcIpBytes(packet);
        byte[] dstIp = PacketUtils.getDstIpBytes(packet);

        int payloadOffset = ihl + 8;
        int payloadLen = length - payloadOffset;
        byte[] payload = new byte[payloadLen];
        if (payloadLen > 0) System.arraycopy(packet, payloadOffset, payload, 0, payloadLen);

        udpExecutor.submit(() -> forwardUdp(srcIp, srcPort, dstIp, dstPort, payload));
    }

    private void forwardUdp(byte[] srcIp, int srcPort, byte[] dstIp, int dstPort, byte[] payload) {
        DatagramChannel channel = null;
        try {
            InetSocketAddress dest = new InetSocketAddress(
                    InetAddress.getByAddress(dstIp), dstPort);

            channel = DatagramChannel.open();
            channel.configureBlocking(true);
            channel.socket().setSoTimeout(3000);
            channel.connect(dest);

            if (payload.length > 0) channel.write(ByteBuffer.wrap(payload));

            ByteBuffer resp = ByteBuffer.allocate(1500);
            int read = channel.read(resp);
            if (read > 0) {
                resp.flip();
                byte[] data = new byte[read];
                resp.get(data);

                byte[] response = PacketUtils.buildUdpPacket(
                        dstIp, srcIp, dstPort, srcPort, data);
                synchronized (tunLock) {
                    try { out.write(response); } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "UDP error: " + srcPort + "→" + dstPort, e);
        } finally {
            if (channel != null) try { channel.close(); } catch (Exception ignored) {}
        }
    }

    private void handleTcp(byte[] packet, int length, int ihl) {
        if (ihl + 20 > length) return;

        int srcPort = PacketUtils.getTcpSrcPort(packet, ihl);
        int dstPort = PacketUtils.getTcpDstPort(packet, ihl);
        byte[] srcIp = PacketUtils.getSrcIpBytes(packet);
        byte[] dstIp = PacketUtils.getDstIpBytes(packet);

        int flags = PacketUtils.getTcpFlags(packet, ihl);
        int tcpSeq = PacketUtils.getTcpSeq(packet, ihl);
        int tcpAck = PacketUtils.getTcpAck(packet, ihl);

        int dataOffset = PacketUtils.getTcpDataOffset(packet, ihl) * 4;
        int payloadOffset = ihl + dataOffset;
        int payloadLen = length - payloadOffset;

        // Handle RST: cleanup
        if (PacketUtils.isTcpRst(flags)) {
            TcpConnection conn = tcpConnections.remove(key(srcIp, srcPort, dstIp, dstPort));
            if (conn != null) conn.closeConnection();
            return;
        }

        // Handle new connection SYN (no ACK)
        if (PacketUtils.isTcpSyn(flags) && !PacketUtils.isTcpAck(flags)) {
            String k = key(srcIp, srcPort, dstIp, dstPort);
            if (tcpConnections.containsKey(k)) return;

            byte[] opts = PacketUtils.getTcpOptions(packet, ihl);
            TcpConnection conn = new TcpConnection(srcIp, srcPort, dstIp, dstPort, out, tunLock);
            tcpConnections.put(k, conn);
            conn.handleSyn(tcpSeq, opts);
            return;
        }

        // Existing connection
        String k = key(srcIp, srcPort, dstIp, dstPort);
        TcpConnection conn = tcpConnections.get(k);
        if (conn == null) {
            // Unknown – send RST
            byte[] rst = PacketUtils.buildTcpRst(dstIp, srcIp, dstPort, srcPort, tcpAck);
            synchronized (tunLock) { try { out.write(rst); } catch (Exception ignored) {} }
            return;
        }

        if (PacketUtils.isTcpFin(flags)) {
            conn.handleFin(tcpSeq);
        }

        if (payloadLen > 0 || PacketUtils.isTcpAck(flags)) {
            conn.handleAckOrData(packet, ihl, flags, tcpSeq, payloadOffset, payloadLen);
        }

        if (conn.isClosed()) {
            tcpConnections.remove(k);
        }
    }
}
