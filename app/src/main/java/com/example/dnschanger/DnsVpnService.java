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

/**
 * DNS Changer VPN Service.
 * Creates a VpnService tunnel with custom DNS servers and forwards
 * all traffic through a user-space packet forwarder.
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
        String dns1 = prefs.getString("dns_primary", "1.1.1.1");
        String dns2 = prefs.getString("dns_secondary", "");

        try {
            Builder builder = new Builder();
            builder.setSession("DNS Changer")
                    .setMtu(1500)
                    .addAddress("10.10.10.2", 32)
                    .addDnsServer(dns1);
            if (!dns2.isEmpty()) builder.addDnsServer(dns2);
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

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopVpn();
    }

    @Override
    public void onRevoke() {
        stopVpn();
    }


    private static int pendingFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
