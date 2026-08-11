package com.example.dnschanger;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private EditText dnsPrimaryEdit;
    private EditText dnsSecondaryEdit;
    private Switch toggleSwitch;
    private TextView statusText;
    private ProgressBar progressBar;
    private Button presetGoogle;
    private Button presetCloudflare;
    private Button presetOpenDns;
    private Button grantPermissionBtn;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("dns_prefs", MODE_PRIVATE);

        dnsPrimaryEdit = findViewById(R.id.dns_primary);
        dnsSecondaryEdit = findViewById(R.id.dns_secondary);
        toggleSwitch = findViewById(R.id.toggle_switch);
        statusText = findViewById(R.id.status_text);
        progressBar = findViewById(R.id.progress);
        presetGoogle = findViewById(R.id.preset_google);
        presetCloudflare = findViewById(R.id.preset_cloudflare);
        presetOpenDns = findViewById(R.id.preset_opendns);
        grantPermissionBtn = findViewById(R.id.grant_permission);

        // Load saved DNS values. Fields stay empty (only hints visible) until the user types.
        String dns1 = prefs.getString("dns_primary", "");
        String dns2 = prefs.getString("dns_secondary", "");
        if (!dns1.isEmpty()) dnsPrimaryEdit.setText(dns1);
        if (!dns2.isEmpty()) dnsSecondaryEdit.setText(dns2);

        boolean enabled = prefs.getBoolean("dns_enabled", false);
        toggleSwitch.setChecked(enabled);

        toggleSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            if (isChecked) {
                saveDns();
                checkPermissionAndStart();
            } else {
                stopVpn();
            }
        });

        presetGoogle.setOnClickListener(v -> {
            dnsPrimaryEdit.setText("8.8.8.8");
            dnsSecondaryEdit.setText("8.8.4.4");
        });
        presetCloudflare.setOnClickListener(v -> {
            dnsPrimaryEdit.setText("1.1.1.1");
            dnsSecondaryEdit.setText("1.0.0.1");
        });
        presetOpenDns.setOnClickListener(v -> {
            dnsPrimaryEdit.setText("208.67.222.222");
            dnsSecondaryEdit.setText("208.67.220.220");
        });
        grantPermissionBtn.setOnClickListener(v -> checkPermissionAndStart());

        updateUi(enabled);
    }

    private void saveDns() {
        String dns1 = dnsPrimaryEdit.getText().toString().trim();
        String dns2 = dnsSecondaryEdit.getText().toString().trim();
        prefs.edit()
                .putString("dns_primary", dns1)
                .putString("dns_secondary", dns2)
                .apply();
    }

    private void checkPermissionAndStart() {
        saveDns();
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            vpnPermissionLauncher.launch(intent);
        } else {
            startVpn();
        }
    }

    private final ActivityResultLauncher<Intent> vpnPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK) {
                            startVpn();
                        } else {
                            toggleSwitch.setChecked(false);
                            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show();
                        }
                    });

    private void startVpn() {
        progressBar.setVisibility(View.VISIBLE);
        statusText.setText("Starting VPN...");
        Intent svc = new Intent(this, DnsVpnService.class);
        svc.setAction(DnsVpnService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
    }

    private void stopVpn() {
        Intent svc = new Intent(this, DnsVpnService.class);
        svc.setAction(DnsVpnService.ACTION_STOP);
        startService(svc);
    }

    private void updateUi(boolean enabled) {
        if (enabled) {
            statusText.setText("Status: ON - DNS is changed");
        } else {
            statusText.setText("Status: OFF - DNS is normal");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean enabled = prefs.getBoolean("dns_enabled", false);
        toggleSwitch.setChecked(enabled);
        updateUi(enabled);
    }
}
