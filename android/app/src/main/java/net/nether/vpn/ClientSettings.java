package net.nether.vpn;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.InetAddress;

/**
 * Local routing choices. Not part of the portable, server-generated identity
 * profile. Android owns these preferences; the server never sees them.
 */
public final class ClientSettings {
    private static final String PREFS = "nether_local_options";
    public final boolean fullTunnel;
    public final String dns;
    public final boolean bindMobileUdp;
    public final boolean advertiseUnderlying;

    private ClientSettings(boolean fullTunnel, String dns, boolean bindMobileUdp, boolean advertiseUnderlying) {
        this.fullTunnel = fullTunnel;
        this.dns = dns;
        this.bindMobileUdp = bindMobileUdp;
        this.advertiseUnderlying = advertiseUnderlying;
    }

    public static ClientSettings load(Context context, VpnProfile legacyProfile) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // One-time migration for existing 0.3 profiles containing these fields.
        // Newly issued portable profiles contain no app-specific preferences.
        boolean full = prefs.contains("configured")
                ? prefs.getBoolean("full", false)
                : legacyProfile.fullTunnel;
        String dns = prefs.contains("configured")
                ? prefs.getString("dns", "1.1.1.1")
                : legacyProfile.dns;
        return new ClientSettings(full, dns, prefs.getBoolean("bind_mobile_udp", true), prefs.getBoolean("advertise_underlying", true));
    }

    public static ClientSettings save(Context context, boolean full, String rawDns, boolean bindMobileUdp, boolean advertiseUnderlying)
            throws Exception {
        String dns = validateDNS(rawDns);
        boolean saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("full", full)
                .putBoolean("bind_mobile_udp", bindMobileUdp)
                .putBoolean("advertise_underlying", advertiseUnderlying)
                .putString("dns", dns)
                .putBoolean("configured", true)
                .commit();
        if (!saved) throw new IllegalStateException("Не удалось сохранить настройки");
        return new ClientSettings(full, dns, bindMobileUdp, advertiseUnderlying);
    }

    public static String validateDNS(String raw) throws Exception {
        String value = raw.trim();
        if (!value.matches("[0-9.]+")) {
            throw new IllegalArgumentException("DNS должен быть числовым IPv4");
        }
        InetAddress address = InetAddress.getByName(value);
        if (address.getAddress().length != 4 || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            throw new IllegalArgumentException("Недопустимый адрес DNS");
        }
        return address.getHostAddress();
    }
}
