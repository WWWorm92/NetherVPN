package net.nether.vpn;

/**
 * Pure Java, platform-independent packet policy. Mirrors the Linux client's
 * fail-closed source checks without making a bad packet restart the VPN.
 *
 * Unknown/malformed IP, IPv6, spoofed client source and unauthenticated
 * destinations are DROP events, never transport errors.
 */
public final class PacketPolicy {
    public static final int OK = 0;
    public static final int MALFORMED = 1;
    public static final int IPV6_UNSUPPORTED = 2;
    public static final int FOREIGN_SOURCE = 3;
    public static final int FOREIGN_DESTINATION = 4;

    private PacketPolicy() {}

    public static int outbound(byte[] packet, int length, byte[] assignedIp) {
        int format = ipv4Format(packet, length);
        if (format != OK) return format;
        return matches(packet, 12, assignedIp) ? OK : FOREIGN_SOURCE;
    }

    public static int inbound(byte[] packet, int length, byte[] assignedIp,
                              byte[] serverIp, boolean fullTunnel) {
        int format = ipv4Format(packet, length);
        if (format != OK) return format;
        if (!matches(packet, 16, assignedIp)) return FOREIGN_DESTINATION;
        if (!fullTunnel && !matches(packet, 12, serverIp)) return FOREIGN_SOURCE;
        return OK;
    }

    public static int ipv4Format(byte[] packet, int length) {
        if (packet == null || length < 1 || length > packet.length)
            return MALFORMED;
        int version = (packet[0] & 0xff) >>> 4;
        if (version == 6) return IPV6_UNSUPPORTED;
        if (version != 4 || length < 20) return MALFORMED;
        int header = (packet[0] & 15) * 4;
        int total = ((packet[2] & 255) << 8) | (packet[3] & 255);
        return header >= 20 && header <= length && total == length ? OK : MALFORMED;
    }

    private static boolean matches(byte[] packet, int offset, byte[] ip) {
        if (ip == null || ip.length != 4 || packet.length < offset + 4)
            return false;
        for (int i = 0; i < 4; i++) {
            if (packet[offset + i] != ip[i]) return false;
        }
        return true;
    }

    public static String reason(int code) {
        switch (code) {
            case IPV6_UNSUPPORTED: return "IPv6 не поддерживается";
            case FOREIGN_SOURCE: return "Неверный исходящий IP";
            case FOREIGN_DESTINATION: return "Чужой адрес назначения";
            case MALFORMED: return "Некорректный IP-пакет";
            default: return "";
        }
    }
}
