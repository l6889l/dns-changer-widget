package com.example.dnschanger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * DNS Changer VPN Service.
 * Creates a VpnService tunnel with custom DNS servers and forwards
 * all traffic through a user-space packet forwarder.
 * DNS servers can be entered as IP addresses or hostnames (e.g. dns.example.com);
 * hostnames are resolved to IPs before the tunnel is established.
 */
public class DnsVpnService extends VpnService {

    private static final String TAG = "DnsVpnService";
    public static final String ACTION_TOGGLE = "com.example.dnschanger.TOGGLE";
    public static final String ACTION_START = "com.example.dnschanger.START";
    public static final String ACTION_STOP = "com.example.dnschanger.STOP";
    public static final String EXTRA_FROM_WIDGET = "from_widget";

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "dns_changer_channel";

    private PacketForwarder forwarder;
    private ParcelFileDescriptor vpnInterface;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopVpn();
        } else if (ACTION_START.equals(action)) {
            startVpn();
        } else if (ACTION_TOGGLE.equals(action)) {
            if (isRunning()) {
                stopVpn();
            } else {
                startVpn();
            }
        }
        return START_NOT_STICKY;
    }

    private void startVpn() {
        if (isRunning()) return;

        SharedPreferences prefs = getSharedPreferences("dns_prefs", MODE_PRIVATE);
        String dns1Raw = prefs.getString("dns_primary", "1.1.1.1");
        String dns2Raw = prefs.getString("dns_secondary", "");

        // Resolve hostnames to IPs BEFORE the tunnel is up (uses the normal network DNS)
        String dns1 = resolveDnsToIp(dns1Raw);
        String dns2 = resolveDnsToIp(dns2Raw);

        if (dns1 == null) {
            Log.e(TAG, "Cannot resolve primary DNS '" + dns1Raw + "', falling back to 1.1.1.1");
            dns1 = "1.1.1.1";
        }

        try {
            Builder builder = new Builder();
            builder.setSession("DNS Changer Widget")
                    .setMtu(1500)
                    .addAddress("10.10.10.2", 32)
                    .addDnsServer(dns1);
            if (dns2 != null && !dns2.isEmpty()) {
                builder.addDnsServer(dns2);
            }
            builder.addRoute("0.0.0.0", 0)
                    .addRoute("::", 0);

            vpnInterface = builder.establish();

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface");
                return;
            }

            startForeground(NOTIFICATION_ID, createNotification("DNS Changer active", "VPN is running with custom DNS"));

            forwarder = new PacketForwarder(vpnInterface);
            forwarder.start();

            prefs.edit().putBoolean("dns_enabled", true).apply();

            // Notify widget
            DnsWidgetProvider.updateWidgets(this);

        } catch (Exception e) {
            Log.e(TAG, "Error starting VPN", e);
            stopVpn();
        }
    }

    private void stopVpn() {
        if (forwarder != null) {
            forwarder.stop();
            forwarder = null;
        }
        try {
            if (vpnInterface != null) {
                vpnInterface.close();
                vpnInterface = null;
            }
        } catch (Exception ignored) {}

        SharedPreferences prefs = getSharedPreferences("dns_prefs", MODE_PRIVATE);
        prefs.edit().putBoolean("dns_enabled", false).apply();

        stopForeground(true);
        stopSelf();

        DnsWidgetProvider.updateWidgets(this);
    }

    public boolean isRunning() {
        return vpnInterface != null && forwarder != null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "DNS Changer", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("DNS Changer VPN service");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification createNotification(String title, String text) {
        Intent toggleIntent = new Intent(this, DnsVpnService.class);
        toggleIntent.setAction(ACTION_TOGGLE);
        PendingIntent toggle = PendingIntent.getService(this, 0, toggleIntent,
                pendingFlags());

        Intent stopIntent = new Intent(this, DnsVpnService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 1, stopIntent,
                pendingFlags());

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(this, 0, openIntent,
                pendingFlags());

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_dns)
                .setContentIntent(open)
                .addAction(R.drawable.ic_dns, "Stop", stop)
                .addAction(R.drawable.ic_dns, "Toggle", toggle)
                .setOngoing(true)
                .build();
    }

    /**
     * Convert a user-entered DNS server setting (IP literal or hostname) into an IP address.
     * Hostnames are resolved via the system DNS before the tunnel is established,
     * so the result can be passed to Builder.addDnsServer(String).
     *
     * @param setting the stored setting, e.g. "1.1.1.1" or "dns.example.com"
     * @return an IP address string, or null if the value is empty or cannot be resolved
     */
    private static String resolveDnsToIp(String setting) {
        if (setting == null) return null;
        String s = normalizeDnsHost(setting.trim());
        if (s.isEmpty()) return null;

        // Already an IP literal — use as-is
        if (isIpLiteral(s)) return s;

        // Hostname — resolve to an IP address
        try {
            InetAddress[] addresses = InetAddress.getAllByName(s);
            // Prefer IPv4 (most compatible with VPN DNS on Android)
            for (InetAddress addr : addresses) {
                if (addr instanceof Inet4Address) {
                    return addr.getHostAddress();
                }
            }
            if (addresses.length > 0) {
                return addresses[0].getHostAddress();
            }
        } catch (UnknownHostException e) {
            Log.w(TAG, "Cannot resolve DNS host: " + s, e);
        }
        return null;
    }

    /** Strip URL scheme and path, e.g. "https://dns.example.com/" -> "dns.example.com". */
    private static String normalizeDnsHost(String s) {
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        return s;
    }

    /** True if the string is an IPv4 or IPv6 address literal. */
    private static boolean isIpLiteral(String s) {
        // IPv6 (contains ':' and is not a URL)
        if (s.indexOf(':') >= 0 && !s.contains("://")) return true;
        // IPv4 — exactly 4 octets 0-255 separated by dots
        return isIpv4(s);
    }

    /** Manual IPv4 check (no regex). */
    private static boolean isIpv4(String s) {
        int dots = 0;
        int octet = 0;
        int digits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.') {
                if (digits == 0 || octet > 255) return false;
                dots++;
                octet = 0;
                digits = 0;
            } else if (c >= '0' && c <= '9') {
                octet = octet * 10 + (c - '0');
                digits++;
                if (digits > 3 || octet > 255) return false;
            } else {
                return false;
            }
        }
        return dots == 3 && digits > 0 && octet <= 255;
    }

    private static int pendingFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopVpn();
    }

    @Override
    public void onRevoke() {
        stopVpn();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
