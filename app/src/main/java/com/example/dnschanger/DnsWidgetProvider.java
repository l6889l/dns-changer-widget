package com.example.dnschanger;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.widget.RemoteViews;

public class DnsWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_TOGGLE = "com.example.dnschanger.WIDGET_TOGGLE";

    public static void updateWidgets(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(
                new ComponentName(context, DnsWidgetProvider.class));
        for (int id : ids) {
            updateWidget(context, manager, id);
        }
    }

    private static void updateWidget(Context context, AppWidgetManager manager, int widgetId) {
        SharedPreferences prefs = context.getSharedPreferences("dns_prefs", Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("dns_enabled", false);
        String dns1 = prefs.getString("dns_primary", "1.1.1.1");

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_layout);

        if (enabled) {
            views.setImageViewResource(R.id.widget_icon, R.drawable.ic_widget_on);
            views.setTextViewText(R.id.widget_text, "DNS ON");
            views.setTextColor(R.id.widget_text, 0xFF4CAF50);
            views.setTextViewText(R.id.widget_dns, dns1);
        } else {
            views.setImageViewResource(R.id.widget_icon, R.drawable.ic_widget_off);
            views.setTextViewText(R.id.widget_text, "DNS OFF");
            views.setTextColor(R.id.widget_text, 0xFF9E9E9E);
            views.setTextViewText(R.id.widget_dns, "Tap to start");
        }

        Intent toggleIntent = new Intent(context, DnsWidgetProvider.class);
        toggleIntent.setAction(ACTION_TOGGLE);
        PendingIntent pi = PendingIntent.getBroadcast(context, widgetId, toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));
        views.setOnClickPendingIntent(R.id.widget_root, pi);

        manager.updateAppWidget(widgetId, views);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            updateWidget(context, manager, id);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_TOGGLE.equals(intent.getAction())) {
            SharedPreferences prefs = context.getSharedPreferences("dns_prefs", Context.MODE_PRIVATE);
            boolean enabled = prefs.getBoolean("dns_enabled", false);
            Intent svc = new Intent(context, DnsVpnService.class);
            svc.setAction(enabled ? DnsVpnService.ACTION_STOP : DnsVpnService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        }
    }
}
