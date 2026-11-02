package net.nether.vpn;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Strict, single-peer NetherVPN profile. This is NOT a WireGuard configuration. */
public final class VpnProfile {
    public final String endpointHost;
    public final int endpointPort;
    public final String localIp;
    public final int localPrefix;
    public final String peerIp;
    public final int peerPrefix;
    public final int mtu;
    public final byte[] privateKey;
    public final byte[] serverPublicKey;

    private VpnProfile(String host, int port, String local, int prefix, String peer, int peerBits,
                       int mtu, byte[] secret, byte[] serverKey) {
        this.endpointHost = host; this.endpointPort = port;
        this.localIp = local; this.localPrefix = prefix;
        this.peerIp = peer; this.peerPrefix = peerBits; this.mtu = mtu;
        this.privateKey = secret; this.serverPublicKey = serverKey;
    }

    public InetSocketAddress endpoint() { return new InetSocketAddress(endpointHost, endpointPort); }

    public static VpnProfile parse(String data) throws Exception {
        if (data == null || data.length() > 65536) throw new IllegalArgumentException("Конфигурация отсутствует или слишком большая");
        Map<String, String> fields = new HashMap<>();
        String section = "";
        for (String raw : data.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim().toLowerCase(Locale.ROOT);
                if (!section.equals("interface") && !section.equals("peer")) throw new IllegalArgumentException("Неизвестный раздел: " + section);
                continue;
            }
            int pos = line.indexOf('=');
            if (pos < 1) throw new IllegalArgumentException("Ожидается Key = Value");
            String key = section + "." + line.substring(0, pos).trim().toLowerCase(Locale.ROOT);
            if (!Arrays.asList("interface.name", "interface.privatekey", "interface.address", "interface.mtu",
                    "peer.publickey", "peer.endpoint", "peer.allowedips").contains(key)) {
                throw new IllegalArgumentException("Неизвестный параметр: " + key);
            }
            if (fields.put(key, line.substring(pos + 1).trim()) != null) {
                throw new IllegalArgumentException("Повтор параметра: " + key);
            }
        }
        String address = required(fields, "interface.address");
        String peer = required(fields, "peer.allowedips");
        String[] localParts = address.split("/", -1);
        if (localParts.length != 2) throw new IllegalArgumentException("Address должен содержать маску");
        InetAddress localAddr = literalIp(localParts[0]);
        int prefix = Integer.parseInt(localParts[1]);
        if (prefix < 1 || prefix > (localAddr.getAddress().length == 4 ? 32 : 128)) throw new IllegalArgumentException("Неверный префикс Address");
        InetAddress peerAddr = literalIp(peer);
        if (peerAddr.getAddress().length != localAddr.getAddress().length) throw new IllegalArgumentException("IP версии адресов не совпадают");
        int peerBits = peerAddr.getAddress().length == 4 ? 32 : 128;
        if (localAddr.isAnyLocalAddress() || peerAddr.isAnyLocalAddress()) throw new IllegalArgumentException("Нулевые IP недопустимы");

        String endpoint = required(fields, "peer.endpoint");
        String host, port;
        if (endpoint.startsWith("[")) {
            int end = endpoint.indexOf(']');
            if (end < 0 || end + 1 >= endpoint.length() || endpoint.charAt(end + 1) != ':') throw new IllegalArgumentException("Неверный Endpoint");
            host = endpoint.substring(1, end); port = endpoint.substring(end + 2);
        } else {
            int end = endpoint.lastIndexOf(':');
            if (end < 1 || endpoint.indexOf(':') != end) throw new IllegalArgumentException("Используйте host:port");
            host = endpoint.substring(0, end); port = endpoint.substring(end + 1);
        }
        int portNumber = Integer.parseInt(port);
        if (portNumber < 1 || portNumber > 65535 || host.trim().isEmpty()) throw new IllegalArgumentException("Неверный сервер или порт");
        int mtu = Integer.parseInt(fields.getOrDefault("interface.mtu", "1280"));
        if (mtu < 1280 || mtu > 9000) throw new IllegalArgumentException("MTU вне диапазона 1280..9000");
        return new VpnProfile(host, portNumber, localAddr.getHostAddress(), prefix,
                peerAddr.getHostAddress(), peerBits, mtu,
                decodeHex(required(fields, "interface.privatekey")),
                decodeHex(required(fields, "peer.publickey")));
    }

    private static String required(Map<String, String> fields, String key) {
        String val = fields.get(key);
        if (val == null || val.isEmpty()) throw new IllegalArgumentException("Отсутствует " + key);
        return val;
    }

    private static InetAddress literalIp(String s) throws Exception {
        if (!s.matches("[0-9.]+") && !s.matches("[0-9a-fA-F:]+")) throw new IllegalArgumentException("Ожидался числовой IP: " + s);
        return InetAddress.getByName(s);
    }

    private static byte[] decodeHex(String s) {
        if (!s.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("X25519 ключ должен содержать 64 HEX-символа");
        byte[] result = new byte[32];
        for (int i = 0; i < 32; i++) result[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return result;
    }
}
