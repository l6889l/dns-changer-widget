package com.example.dnschanger.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Utility class for parsing and constructing IP/TCP/UDP packets.
 */
public class PacketUtils {

    public static final int IP_PROTO_TCP = 6;
    public static final int IP_PROTO_UDP = 17;

    public static final int TCP_FIN = 0x01;
    public static final int TCP_SYN = 0x02;
    public static final int TCP_RST = 0x04;
    public static final int TCP_PSH = 0x08;
    public static final int TCP_ACK = 0x10;

    // --- IP Header Parsing ---

    public static int getIpHeaderLength(byte[] packet) {
        return (packet[0] & 0x0F) * 4;
    }

    public static int getIpProtocol(byte[] packet) {
        return packet[9] & 0xFF;
    }

    public static byte[] getSrcIpBytes(byte[] packet) {
        byte[] ip = new byte[4];
        System.arraycopy(packet, 12, ip, 0, 4);
        return ip;
    }

    public static byte[] getDstIpBytes(byte[] packet) {
        byte[] ip = new byte[4];
        System.arraycopy(packet, 16, ip, 0, 4);
        return ip;
    }

    public static String ipToString(byte[] ip) {
        try {
            return InetAddress.getByAddress(ip).getHostAddress();
        } catch (UnknownHostException e) {
            return "0.0.0.0";
        }
    }

    // --- UDP Header Parsing ---

    public static int getUdpSrcPort(byte[] packet, int ihl) {
        return ((packet[ihl] & 0xFF) << 8) | (packet[ihl + 1] & 0xFF);
    }

    public static int getUdpDstPort(byte[] packet, int ihl) {
        return ((packet[ihl + 2] & 0xFF) << 8) | (packet[ihl + 3] & 0xFF);
    }

    // --- TCP Header Parsing ---

    public static int getTcpSrcPort(byte[] packet, int ihl) {
        return ((packet[ihl] & 0xFF) << 8) | (packet[ihl + 1] & 0xFF);
    }

    public static int getTcpDstPort(byte[] packet, int ihl) {
        return ((packet[ihl + 2] & 0xFF) << 8) | (packet[ihl + 3] & 0xFF);
    }

    public static int getTcpDataOffset(byte[] packet, int ihl) {
        return (packet[ihl + 12] & 0xF0) >> 4;
    }

    public static int getTcpFlags(byte[] packet, int ihl) {
        return packet[ihl + 13] & 0xFF;
    }

    public static int getTcpSeq(byte[] packet, int ihl) {
        return (packet[ihl + 4] & 0xFF) << 24 |
               (packet[ihl + 5] & 0xFF) << 16 |
               (packet[ihl + 6] & 0xFF) << 8 |
               (packet[ihl + 7] & 0xFF);
    }

    public static int getTcpAck(byte[] packet, int ihl) {
        return (packet[ihl + 8] & 0xFF) << 24 |
               (packet[ihl + 9] & 0xFF) << 16 |
               (packet[ihl + 10] & 0xFF) << 8 |
               (packet[ihl + 11] & 0xFF);
    }

    public static int getTcpWindowSize(byte[] packet, int ihl) {
        return ((packet[ihl + 14] & 0xFF) << 8) | (packet[ihl + 15] & 0xFF);
    }

    public static byte[] getTcpOptions(byte[] packet, int ihl) {
        int dataOffset = getTcpDataOffset(packet, ihl);
        int optionsLen = dataOffset * 4 - 20;
        if (optionsLen <= 0) return new byte[0];
        byte[] options = new byte[optionsLen];
        System.arraycopy(packet, ihl + 20, options, 0, optionsLen);
        return options;
    }

    public static boolean isTcpSyn(int f) { return (f & TCP_SYN) != 0; }
    public static boolean isTcpAck(int f) { return (f & TCP_ACK) != 0; }
    public static boolean isTcpFin(int f) { return (f & TCP_FIN) != 0; }
    public static boolean isTcpRst(int f) { return (f & TCP_RST) != 0; }
    public static boolean isTcpPsh(int f) { return (f & TCP_PSH) != 0; }

    // --- Checksum ---

    public static int checksum(byte[] data, int offset, int length) {
        long sum = 0;
        int end = offset + length;
        for (int i = offset; i < end; i += 2) {
            if (i + 1 < end) {
                sum += ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            } else {
                sum += (data[i] & 0xFF) << 8;
            }
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (int) (~sum & 0xFFFF);
    }

    // --- Packet Construction ---

    /** Build a TCP packet with options (full version). */
    public static byte[] buildTcpPacket(
            byte[] srcIp, byte[] dstIp,
            int srcPort, int dstPort,
            int seq, int ack,
            int flags, int windowSize,
            int dataOffsetWords,
            byte[] options,
            byte[] payload) {

        int optionsLen = (options != null) ? options.length : 0;
        int payloadLen = (payload != null) ? payload.length : 0;
        int tcpHeaderLen = dataOffsetWords * 4;
        int ipTotalLen = 20 + tcpHeaderLen + payloadLen;

        ByteBuffer buf = ByteBuffer.allocate(ipTotalLen).order(ByteOrder.BIG_ENDIAN);

        // IP Header (20 bytes)
        buf.put((byte) 0x45);
        buf.put((byte) 0x00);
        buf.putShort((short) ipTotalLen);
        buf.putShort((short) 0x0000);
        buf.putShort((short) 0x4000);
        buf.put((byte) 64);
        buf.put((byte) IP_PROTO_TCP);
        buf.putShort((short) 0x0000); // IP checksum placeholder
        buf.put(srcIp);
        buf.put(dstIp);

        // TCP Header
        buf.putShort((short) srcPort);
        buf.putShort((short) dstPort);
        buf.putInt(seq);
        buf.putInt(ack);
        buf.putShort((short) ((dataOffsetWords << 12) | (flags & 0x3F)));
        buf.putShort((short) windowSize);
        buf.putShort((short) 0x0000); // TCP checksum placeholder
        buf.putShort((short) 0x0000); // Urgent pointer

        if (optionsLen > 0) buf.put(options);
        if (payloadLen > 0) buf.put(payload);

        byte[] packet = buf.array();

        // IP checksum
        int ipCks = checksum(packet, 0, 20);
        packet[10] = (byte) ((ipCks >> 8) & 0xFF);
        packet[11] = (byte) (ipCks & 0xFF);

        // TCP checksum (pseudo-header)
        int tcpLen = tcpHeaderLen + payloadLen;
        int tcpCks = tcpPseudoChecksum(packet, 12, 16, IP_PROTO_TCP, tcpLen, 20);
        packet[20 + 16] = (byte) ((tcpCks >> 8) & 0xFF);
        packet[20 + 17] = (byte) (tcpCks & 0xFF);

        return packet;
    }

    /** Build a TCP packet without options (simple version). */
    public static byte[] buildTcpPacket(
            byte[] srcIp, byte[] dstIp,
            int srcPort, int dstPort,
            int seq, int ack,
            int flags, int windowSize,
            byte[] payload) {
        return buildTcpPacket(srcIp, dstIp, srcPort, dstPort,
                seq, ack, flags, windowSize, 5, null, payload);
    }

    /** Build an IP+UDP packet. */
    public static byte[] buildUdpPacket(
            byte[] srcIp, byte[] dstIp,
            int srcPort, int dstPort,
            byte[] payload) {

        int payloadLen = (payload != null) ? payload.length : 0;
        int udpLen = 8 + payloadLen;
        int ipTotalLen = 20 + udpLen;

        ByteBuffer buf = ByteBuffer.allocate(ipTotalLen).order(ByteOrder.BIG_ENDIAN);

        buf.put((byte) 0x45);
        buf.put((byte) 0x00);
        buf.putShort((short) ipTotalLen);
        buf.putShort((short) 0x0000);
        buf.putShort((short) 0x4000);
        buf.put((byte) 64);
        buf.put((byte) IP_PROTO_UDP);
        buf.putShort((short) 0x0000);
        buf.put(srcIp);
        buf.put(dstIp);

        buf.putShort((short) srcPort);
        buf.putShort((short) dstPort);
        buf.putShort((short) udpLen);
        buf.putShort((short) 0x0000);
        if (payloadLen > 0) buf.put(payload);

        byte[] packet = buf.array();

        int ipCks = checksum(packet, 0, 20);
        packet[10] = (byte) ((ipCks >> 8) & 0xFF);
        packet[11] = (byte) (ipCks & 0xFF);

        return packet;
    }

    /** Build a TCP RST packet. */
    public static byte[] buildTcpRst(byte[] srcIp, byte[] dstIp,
                                     int srcPort, int dstPort,
                                     int seq) {
        return buildTcpPacket(srcIp, dstIp, srcPort, dstPort,
                seq, 0, TCP_RST | TCP_ACK, 65535, 5, null, null);
    }

    private static int tcpPseudoChecksum(byte[] packet, int srcIpOff, int dstIpOff,
                                         int protocol, int tcpLen, int tcpOff) {
        ByteBuffer pseudo = ByteBuffer.allocate(12 + tcpLen).order(ByteOrder.BIG_ENDIAN);
        pseudo.put(packet, srcIpOff, 4);
        pseudo.put(packet, dstIpOff, 4);
        pseudo.put((byte) 0);
        pseudo.put((byte) protocol);
        pseudo.putShort((short) tcpLen);
        pseudo.put(packet, tcpOff, tcpLen);
        byte[] bytes = pseudo.array();
        return checksum(bytes, 0, bytes.length);
    }

    public static int randomIsn() {
        return (int) (System.nanoTime() & 0x7FFFFFFF);
    }
}
