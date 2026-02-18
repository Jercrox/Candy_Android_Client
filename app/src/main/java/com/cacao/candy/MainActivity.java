package com.cacao.candy;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;

public class MainActivity extends AppCompatActivity {

    private Button btnConnect, btnCopyLog, btnResizeLog;
    private TextView txtStatus, textViewLog, txtTitle, txtIpAddress, txtLogHeader;
    private ScrollView scrollViewLog;
    private EditText editPassword, editServer;
    private Spinner spinnerLanguage;
    private LinearLayout layoutTopControls;
    private String resolvedIp = null;

    private boolean isConnected = false;
    private static boolean activeStatus = false;
    private boolean isLogMaximized = false;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    private final ActivityResultLauncher<Intent> vpnLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                appendLog("SYSTEM: [VPN_PREPARE] Result=" + result.getResultCode());
                if (result.getResultCode() == Activity.RESULT_OK) startVpnService();
            });

    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String log = intent.getStringExtra("log");
            if (intent.hasExtra("logResId")) {
                int resId = intent.getIntExtra("logResId", 0);
                if (intent.hasExtra("logArg")) {
                    log = getString(resId, intent.getIntExtra("logArg", 0));
                } else {
                    log = getString(resId);
                }
            }
            if (log != null) appendLog(log);
            
            String status = intent.getStringExtra("status");
            if (status != null) {
                String localizedStatus = status;
                if (status.equals("Operativo")) localizedStatus = getString(R.string.operational);
                else if (status.equals("Desconectado")) localizedStatus = getString(R.string.disconnected);
                
                txtStatus.setText(getString(R.string.status_label) + localizedStatus);
                if (status.contains("Desconectado") || status.contains("Terminado")) {
                     if (txtIpAddress != null) txtIpAddress.setText(getString(R.string.ip_label) + "-");
                }
            }
            
            if (intent.hasExtra("connected")) {
                isConnected = intent.getBooleanExtra("connected", false);
                activeStatus = isConnected;
                updateUI();
                if (!isConnected) {
                    // Clear persistent session data on disconnect
                    getPreferences(MODE_PRIVATE).edit()
                        .remove("session_log")
                        .remove("session_ip")
                        .apply();
                }
            }
            
            if (intent.hasExtra("ip")) {
                String ip = intent.getStringExtra("ip");
                if (txtIpAddress != null) txtIpAddress.setText(getString(R.string.ip_label) + ip);
                // Save IP to persistent preferences
                getPreferences(MODE_PRIVATE).edit().putString("session_ip", ip).apply();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            StringWriter sw = new StringWriter();
            throwable.printStackTrace(new PrintWriter(sw));
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("crash", sw.toString());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        });

        loadLocale();
        setContentView(R.layout.activity_main);
        initViews();

        String crashReport = getIntent().getStringExtra("crash");
        if (crashReport != null) {
            textViewLog.setText("--- [CÓDIGO DE ERROR JAVA (STACKTRACE)] ---\n" + crashReport);
        } else {
            // Restore persistent log if available
            String savedLog = getPreferences(MODE_PRIVATE).getString("session_log", "");
            if (!savedLog.isEmpty()) {
                textViewLog.setText(savedLog);
                String savedIp = getPreferences(MODE_PRIVATE).getString("session_ip", "");
                if (!savedIp.isEmpty()) {
                    txtIpAddress.setText(getString(R.string.ip_label) + savedIp);
                }
                // When restoring, we assume it's connected if we have logs/ip, but we verify with service later
                this.isConnected = true; 
            } else {
                textViewLog.setText(""); 
            }
        }

        setupListeners();
        
        this.isConnected = activeStatus;
        updateUI();
        if (isConnected) {
             checkServiceStatus();
        }
    }

    private void checkServiceStatus() {
        Intent intent = new Intent("com.cacao.candy.STATUS_REQUEST");
        sendBroadcast(intent);
    }

    private void resolveAndConnect() {
        String urlString = editServer.getText().toString().trim();
        if (urlString.isEmpty()) return;
        
        btnConnect.setEnabled(false);
        btnConnect.setText(R.string.resolving);

        new Thread(() -> {
            try {
                String host = urlString;
                if (host.startsWith("ws://")) host = host.substring(5);
                if (host.startsWith("wss://")) host = host.substring(6);
                int slashIndex = host.indexOf("/");
                if (slashIndex != -1) host = host.substring(0, slashIndex);
                int colonIndex = host.indexOf(":");
                if (colonIndex != -1) host = host.substring(0, colonIndex);

                appendLog("DNS_TRACE: Resolviendo host '" + host + "'...");
                java.net.InetAddress[] addresses = java.net.InetAddress.getAllByName(host);
                if (addresses.length > 0) resolvedIp = addresses[0].getHostAddress();
                
                for (java.net.InetAddress addr : addresses) {
                    appendLog("DNS_TRACE: Host '" + host + "' apunta a IP: " + addr.getHostAddress());
                }
                
                runOnUiThread(() -> {
                    btnConnect.setEnabled(true);
                    prepareVpn();
                });
                
            } catch (Exception e) {
                appendLog("DNS_TRACE: Error de resolución: " + e.getMessage());
                runOnUiThread(() -> {
                     btnConnect.setEnabled(true);
                     prepareVpn();
                });
            }
        }).start();
    }

    private void runInitialTrace() {
        textViewLog.setText("--- CANDY VPN NATIVE CLONE v30 ---\n");
        appendLog("OS_INFO: Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        appendLog("OS_INFO: Arch=" + Build.CPU_ABI + " | Kernel=" + System.getProperty("os.version"));
        
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo net = cm.getActiveNetworkInfo();
            if (net != null) {
                appendLog("NET_INFO: " + net.getTypeName() + " (" + net.getDetailedState() + ")");
            }
        }

        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        String savedPass = prefs.getString("password", "");
        if (!savedPass.isEmpty()) {
            editPassword.setText(savedPass);
            appendLog("CONFIG: Contraseña de red cargada.");
        }
    }

    private void initViews() {
        btnConnect = findViewById(R.id.btnConnect);
        btnCopyLog = findViewById(R.id.btnCopyLog);
        btnResizeLog = findViewById(R.id.btnResizeLog);
        txtStatus = findViewById(R.id.txtStatus);
        txtIpAddress = findViewById(R.id.txtIpAddress);
        txtTitle = findViewById(R.id.txtTitle);
        txtLogHeader = findViewById(R.id.txtLogHeader);
        spinnerLanguage = findViewById(R.id.spinnerLanguage);
        textViewLog = findViewById(R.id.textViewLog);
        scrollViewLog = findViewById(R.id.scrollViewLog);
        editPassword = findViewById(R.id.editPassword);
        editServer = findViewById(R.id.editServer);
        layoutTopControls = findViewById(R.id.layoutTopControls);

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.languages, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLanguage.setAdapter(adapter);

        // Set spinner selection based on current locale
        String currentLang = getPreferences(MODE_PRIVATE).getString("My_Lang", "en");
        if (currentLang.equals("es")) spinnerLanguage.setSelection(1);
        else if (currentLang.equals("zh")) spinnerLanguage.setSelection(2);
        else spinnerLanguage.setSelection(0);

        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        String savedPass = prefs.getString("password", "");
        String savedServer = prefs.getString("server", "");
        
        if (!savedPass.isEmpty()) {
            editPassword.setText(savedPass);
        }
        if (!savedServer.isEmpty()) {
            editServer.setText(savedServer);
        }
    }

    private void setupListeners() {
        btnConnect.setOnClickListener(v -> {
            if (isConnected) {
                stopVpnService(); 
            } else {
                // Clear old session log when starting a new connection
                getPreferences(MODE_PRIVATE).edit().remove("session_log").remove("session_ip").apply();
                textViewLog.setText("");
                runInitialTrace();
                appendLog("ACTION: Iniciando secuencia de conexión...");
                resolveAndConnect();
            }
        });
        btnCopyLog.setOnClickListener(v -> {
            ClipboardManager cb = (ClipboardManager) getSystemService(ClipboardManager.class);
            cb.setPrimaryClip(ClipData.newPlainText("CandyTrace", textViewLog.getText()));
            Toast.makeText(this, "Informe copiado para desarrollo", Toast.LENGTH_SHORT).show();
        });
        btnResizeLog.setOnClickListener(v -> {
            isLogMaximized = !isLogMaximized;
            layoutTopControls.setVisibility(isLogMaximized ? View.GONE : View.VISIBLE);
            btnResizeLog.setText(isLogMaximized ? R.string.reduce : R.string.expand);
        });
        
        spinnerLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedLang = "en";
                if (position == 1) selectedLang = "es";
                else if (position == 2) selectedLang = "zh";
                
                String currentLang = getPreferences(MODE_PRIVATE).getString("My_Lang", "en");
                if (!selectedLang.equals(currentLang)) {
                    if (isConnected) {
                        // Reset selection if connected
                        if (currentLang.equals("es")) spinnerLanguage.setSelection(1);
                        else if (currentLang.equals("zh")) spinnerLanguage.setSelection(2);
                        else spinnerLanguage.setSelection(0);
                        Toast.makeText(MainActivity.this, R.string.msg_disconnect_to_change_lang, Toast.LENGTH_SHORT).show();
                    } else {
                        setLocale(selectedLang);
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void appendLog(String message) {
        runOnUiThread(() -> {
            if (textViewLog == null) return;
            String entry = "\n[" + dateFormat.format(new Date()) + "] " + message;
            textViewLog.append(entry);
            scrollViewLog.post(() -> scrollViewLog.fullScroll(View.FOCUS_DOWN));
            
            // Persist the log update
            String currentFullLog = textViewLog.getText().toString();
            getPreferences(MODE_PRIVATE).edit().putString("session_log", currentFullLog).apply();
        });
    }

    private void prepareVpn() {
        appendLog("CODE: [Prep] VpnService.prepare(this)");
        Intent intent = VpnService.prepare(this);
        if (intent != null) vpnLauncher.launch(intent); else startVpnService();
    }

    private void startVpnService() {
        String p = editPassword.getText().toString().trim();
        if (p.isEmpty()) {
            Toast.makeText(this, R.string.password_hint, Toast.LENGTH_SHORT).show();
            return;
        }
        String serverUrl = editServer.getText().toString().trim();
        if (serverUrl.isEmpty()) {
             Toast.makeText(this, R.string.server_hint, Toast.LENGTH_SHORT).show();
             return;
        }

        getPreferences(MODE_PRIVATE).edit()
            .putString("password", p)
            .putString("server", serverUrl)
            .apply();
        if (resolvedIp != null && !serverUrl.isEmpty()) {
            try {
                String protocol = serverUrl.contains("://") ? serverUrl.substring(0, serverUrl.indexOf("://") + 3) : "ws://";
                String hostAndRest = serverUrl.substring(protocol.length());
                String path = "";
                if (hostAndRest.contains("/")) {
                    path = hostAndRest.substring(hostAndRest.indexOf("/"));
                    hostAndRest = hostAndRest.substring(0, hostAndRest.indexOf("/"));
                }
                String port = "";
                if (hostAndRest.contains(":")) {
                    port = hostAndRest.substring(hostAndRest.indexOf(":"));
                }
                String finalUrl = protocol + resolvedIp + port + path;
                appendLog("DNS_TRACE: Conexión optimizada (URL con IP) -> " + finalUrl);
                
                Intent i = new Intent(this, CandyVpnService.class);
                i.putExtra("server", finalUrl);
                i.putExtra("password", p);
                startService(i);
            } catch (Exception e) {
                appendLog("DNS_TRACE: Error re-armando URL, usando original.");
                Intent i = new Intent(this, CandyVpnService.class);
                i.putExtra("server", serverUrl);
                i.putExtra("password", p);
                startService(i);
            }
        } else {
             Intent i = new Intent(this, CandyVpnService.class);
             i.putExtra("server", serverUrl);
             i.putExtra("password", p);
             startService(i);
        }

        appendLog("CODE: [Start] CandyVpnService.init()");
        isConnected = true;
        updateUI();
    }

    private void stopVpnService() {
        appendLog("CODE: [Stop] CandyVpnService.terminate()");
        Intent i = new Intent(this, CandyVpnService.class);
        i.setAction("STOP");
        startService(i);
        isConnected = false;
        updateUI();
    }

    private void updateUI() {
        btnConnect.setText(isConnected ? R.string.disconnect : R.string.connect);
        String localizedStatus = isConnected ? getString(R.string.operational) : getString(R.string.disconnected);
        txtStatus.setText(getString(R.string.status_label) + localizedStatus);
        txtLogHeader.setText(R.string.technical_log);
        btnResizeLog.setText(isLogMaximized ? R.string.reduce : R.string.expand);
        if (spinnerLanguage != null) spinnerLanguage.setEnabled(!isConnected);
    }

    private void setLocale(String lang) {
        Locale myLocale = new Locale(lang);
        Resources res = getResources();
        DisplayMetrics dm = res.getDisplayMetrics();
        Configuration conf = res.getConfiguration();
        conf.locale = myLocale;
        res.updateConfiguration(conf, dm);
        
        getPreferences(MODE_PRIVATE).edit().putString("My_Lang", lang).apply();
        recreate();
    }

    private void loadLocale() {
        String language = getPreferences(MODE_PRIVATE).getString("My_Lang", "en");
        Locale myLocale = new Locale(language);
        Resources res = getResources();
        DisplayMetrics dm = res.getDisplayMetrics();
        Configuration conf = res.getConfiguration();
        conf.locale = myLocale;
        res.updateConfiguration(conf, dm);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!isConnected) {
            recreate();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(logReceiver, new IntentFilter("com.cacao.candy.LOG_UPDATE"));
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(logReceiver);
    }
}
