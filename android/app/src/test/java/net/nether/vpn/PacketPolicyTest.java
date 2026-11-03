package net.nether.vpn;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PacketPolicyTest {
    private static final byte[] LOCAL = {10, 77, 0, 3};
    private static final byte[] SERVER = {10, 77, 0, 1};

    private static byte[] packet(byte[] source, byte[] destination) {
        byte[] p = new byte[24];
        p[0] = 0x45;
        p[2] = 0;
        p[3] = 24;
        p[8] = 64;
        p[9] = 1;
        System.arraycopy(source, 0, p, 12, 4);
        System.arraycopy(destination, 0, p, 16, 4);
        return p;
    }

    @Test public void onlyAssignedIpv4MayLeaveDevice() {
        byte[] valid = packet(LOCAL, { 8, 8, 8, 8 });
        assertEquals(PacketPolicy.OK, PacketPolicy.outbound(valid, valid.length, LOCAL));
        byte[] spoof = packet({10, 77, 0, 4}, SERVER);
        assertEquals(PacketPolicy.FOREIGN_SOURCE, PacketPolicy.outbound(spoof, spoof.length, LOCAL));
    }

    @Test public void rejectsIpv6AndBadPacketsWithoutThrowing() {
        byte[] ipv6 = new byte[40];
        ipv6[0] = 0x60;
        assertEquals(PacketPolicy.IPV6_UNSUPPORTED, PacketPolicy.outbound(ipv6, ipv6.length, LOCAL));
        assertEquals(PacketPolicy.MALFORMED, PacketPolicy.outbound(new byte[]{0x45}, 1, LOCAL));
        assertEquals(PacketPolicy.MALFORMED, PacketPolicy.outbound(null, 0, LOCAL));
        byte[] truncated = packet(LOCAL, SERVER);
        assertEquals(PacketPolicy.MALFORMED, PacketPolicy.outbound(truncated, 4, LOCAL));
        byte[] wrongLength = packet(LOCAL, SERVER);
        wrongLength[3] = 23;
        assertEquals(PacketPolicy.MALFORMED, PacketPolicy.outbound(wrongLength, wrongLength.length, LOCAL));
    }

    @Test public void splitRequiresServerSourceAndFullAcceptsInternetSources() {
        byte[] internetReply = packet({8, 8, 8, 8}, LOCAL);
        assertEquals(PacketPolicy.OK, PacketPolicy.inbound(internetReply, internetReply.length,
                LOCAL, SERVER, true));
        assertEquals(PacketPolicy.FOREIGN_SOURCE, PacketPolicy.inbound(internetReply, internetReply.length,
                LOCAL, SERVER, false));
        byte[] peerReply = packet(SERVER, LOCAL);
        assertEquals(PacketPolicy.OK, PacketPolicy.inbound(peerReply, peerReply.length, LOCAL, SERVER, false));
        byte[] wrongDest = packet(SERVER, {10, 77, 0, 4});
        assertEquals(PacketPolicy.FOREIGN_DESTINATION, PacketPolicy.inbound(wrongDest, wrongDest.length,
                LOCAL, SERVER, true));
    }

    @Test public void invalidInputNeverAllowed() {
        byte[] p = packet(LOCAL, SERVER);
        assertNotEquals(PacketPolicy.OK, PacketPolicy.outbound(p, p.length + 1, LOCAL));
        assertNotEquals(PacketPolicy.OK, PacketPolicy.outbound(p, p.length, new byte[16]));
    }
}
