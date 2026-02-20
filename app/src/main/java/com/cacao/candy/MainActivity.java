/*
 * Candy VPN Android Client
 * Developed by: Jercrox
 * License: MIT
 */
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
import android.view.inputmethod.InputMethodManager;
import android.view.MotionEvent;
import android.graphics.Rect;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Collections;
import java.util.List;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

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
                    Object arg = intent.getExtras().get("logArg");
                    log = getString(resId, arg);
                } else {
                    log = getString(resId);
                }
            }
            
            boolean isTechnical = intent.getBooleanExtra("isTechnical", false);
            if (log != null) {
                appendLog(log, isTechnical);
            }
            
            String status = intent.getStringExtra("status");
            if (status != null) {
                String localizedStatus = status;
                // Native status mapping
                if (status.equals("Operativo")) localizedStatus = getString(R.string.operational);
                else if (status.equals("Desconectado")) localizedStatus = getString(R.string.disconnected);
                
                txtStatus.setText(getString(R.string.status_label) + localizedStatus);
                
                // Absolute termination statuses
                if (status.contains("Desconectado") || status.contains("Terminado") || status.equals("Error")) {
                     if (txtIpAddress != null) txtIpAddress.setText(getString(R.string.ip_label) + "-");
                     isConnected = false;
                     activeStatus = false;
                     updateUI();
                }
            }
            
            if (intent.hasExtra("connected")) {
                boolean serviceConnected = intent.getBooleanExtra("connected", false);
                if (serviceConnected) {
                    isConnected = true;
                    activeStatus = true;
                    updateUI();
                }
                // Note: We don't set isConnected to false here to avoid flickering during handshake
            }
            
            if (intent.hasExtra("ip")) {
                String ip = intent.getStringExtra("ip");
                if (txtIpAddress != null) txtIpAddress.setText(getString(R.string.ip_label) + ip);
                // Save IP to persistent preferences and update history if connected
                android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
                prefs.edit().putString("session_ip", ip).apply();
                
                String serverUrl = editServer.getText().toString().trim();
                String domain = extractDomain(serverUrl);
                if (domain != null && !domain.isEmpty() && !domain.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                    updateIpHistory(domain, ip);
                }
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
            // Restore hidden technical log for IP recovery ONLY if service is active
            if (activeStatus) {
                String internalLog = getPreferences(MODE_PRIVATE).getString("internal_log", "");
                if (!internalLog.isEmpty()) {
                    String savedIp = getPreferences(MODE_PRIVATE).getString("session_ip", "");
                    if (savedIp.isEmpty()) {
                        // Extract from internal log (always has IPv4=...)
                        int idx = internalLog.lastIndexOf("IPv4=");
                        if (idx != -1) {
                            int end = internalLog.indexOf(" ", idx + 5);
                            if (end == -1) end = internalLog.length();
                            savedIp = internalLog.substring(idx + 5, end).trim();
                            if (savedIp.endsWith("|")) savedIp = savedIp.substring(0, savedIp.length()-1).trim();
                        }
                    }
                    
                    if (!savedIp.isEmpty()) {
                        txtIpAddress.setText(getString(R.string.ip_label) + savedIp);
                    }
                }
            } else {
                if (txtIpAddress != null) txtIpAddress.setText(getString(R.string.ip_label) + "-");
            }

            // Restore persistent visible log if available
            String savedLog = getPreferences(MODE_PRIVATE).getString("session_log", "");
            if (!savedLog.isEmpty()) {
                textViewLog.setText(savedLog);
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
        resolvedIp = null; // Reset to ensure no old IP is leaked

        new Thread(() -> {
            try {
                // 1. Precise Parsing using the Split Plan
                appendLog("DNS_TRACE: Iniciando limpieza y análisis de URL...", true);
                
                String[] parts = urlString.split("/");
                // Expected: [0]protocol, [1]empty, [2]host:port, [3]user, [4]net
                if (parts.length < 3) throw new Exception("Formato de URL inválido (faltan partes)");
                
                String protocol = parts[0]; // LUGAR_0: wss: o ws:
                String domainAndPort = parts[2]; // LUGAR_2: dominio:puerto
                
                // Detailed trace of user/net parts
                if (parts.length >= 5) {
                    appendLog("DNS_TRACE: LUGAR_3 (Usuario) -> " + parts[3], true);
                    appendLog("DNS_TRACE: LUGAR_4 (Red) -> " + parts[4], true);
                }

                // Check for WSS bypass
                if (protocol.equalsIgnoreCase("wss:")) {
                    appendLog("DNS_TRACE: Detectado 'wss://', bypass directo por compatibilidad TLS/Caddy.", true);
                    runOnUiThread(() -> {
                        btnConnect.setEnabled(true);
                        prepareVpn();
                    });
                    return;
                }

                String host;
                if (domainAndPort.contains(":")) {
                    String[] subparts = domainAndPort.split(":");
                    host = subparts[0]; // LUGAR_0: dominio o IP
                } else {
                    host = domainAndPort; // Ya es el dominio o IP
                }

                // 2. Identify Host Type
                boolean isIp = host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
                appendLog("DNS_TRACE: Host extraído -> " + host, true);
                appendLog("DNS_TRACE: Tipo -> " + (isIp ? "Dirección IP" : "Dominio"), true);

                if (!isIp) {
                    appendLog("DNS_TRACE: [Nivel 1] Consultando sistema local...", true);
                    try {
                        java.net.InetAddress[] addresses = java.net.InetAddress.getAllByName(host);
                        if (addresses.length > 0) {
                            resolvedIp = addresses[0].getHostAddress();
                            appendLog("DNS_TRACE: Sistema local resolvió -> " + resolvedIp, true);
                        }
                    } catch (Exception e) {
                        appendLog("DNS_TRACE: Sistema local falló.", true);
                    }

                    if (resolvedIp == null) {
                        appendLog("DNS_TRACE: [Nivel 2] Consultando DoH (Google/Cloudflare)...", true);
                        resolvedIp = resolveDnsExternal(host);
                    }

                    if (resolvedIp == null) {
                        appendLog("DNS_TRACE: [Nivel 3] Consultando UDP directo (8.8.8.8)...", true);
                        resolvedIp = resolveDnsDirectUdp(host);
                    }

                    if (resolvedIp == null) {
                        appendLog("DNS_TRACE: [Nivel 4] Consultando Historial de Supervivencia (3 IPs)...", true);
                        resolvedIp = getIpFromHistory(host);
                    }
                } else {
                    resolvedIp = host;
                    appendLog("DNS_TRACE: Usando IP directa, saltando DNS.", true);
                }

                if (resolvedIp == null) throw new Exception("Imposible resolver el host agotando todos los niveles");

                sendLocalizedStatus(R.string.log_host_resolved, resolvedIp, false);
                
                runOnUiThread(() -> {
                    btnConnect.setEnabled(true);
                    prepareVpn();
                });
                
            } catch (Exception e) {
                appendLog("DNS_TRACE: Falla crítica: " + e.getMessage(), true);
                sendLocalizedStatus(R.string.log_dns_error, e.getMessage(), false);
                runOnUiThread(() -> {
                     btnConnect.setEnabled(true);
                     isConnected = false;
                     updateUI();
                });
            }
        }).start();
    }

    private void runInitialTrace(Runnable onComplete) {
        textViewLog.setText("");
        appendLog(getString(R.string.log_trace_header), false, false);
        sendLocalizedStatus(R.string.log_os_info, Build.VERSION.RELEASE, Build.VERSION.SDK_INT);
        sendLocalizedStatus(R.string.log_kernel_info, Build.CPU_ABI, System.getProperty("os.version"));
        
        boolean isWifiOrCable = false;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo net = cm.getActiveNetworkInfo();
            if (net != null) {
                sendLocalizedStatus(R.string.log_net_info, net.getTypeName(), net.getDetailedState());
                int type = net.getType();
                isWifiOrCable = (type == ConnectivityManager.TYPE_WIFI || type == ConnectivityManager.TYPE_ETHERNET);
            }
        }

        fetchIps(isWifiOrCable, () -> {
            runOnUiThread(() -> {
                if (onComplete != null) onComplete.run();
            });
        });
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

    private String getIdentityKey(String urlString) {
        if (urlString == null || urlString.isEmpty()) return "default";
        try {
            String host = extractDomain(urlString);
            String[] un = extractUserNet(urlString);
            if (host != null && un != null) {
                // Specialized Identity: Host + User + Net (Port and Protocol are ignored for identity)
                return (host.toLowerCase() + "_" + un[0] + "_" + un[1]).replace(".", "_");
            }
        } catch (Exception e) {}
        // Fallback to hash if URL is non-standard
        return "hash_" + Math.abs(urlString.hashCode());
    }

    private void sendLocalizedStatus(int resId, Object arg, boolean isTechnical) {
        String log;
        if (arg != null) {
            log = getString(resId, arg);
        } else {
            log = getString(resId);
        }
        appendLog(log, isTechnical);
    }

    private void sendLocalizedStatus(int resId, Object arg1, Object arg2) {
        String log = getString(resId, arg1, arg2);
        appendLog(log, false);
    }

    private String generateRandomHex(int length) {
        String chars = "0123456789abcdef";
        java.util.Random rnd = new java.util.Random();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(rnd.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private void setupListeners() {
        btnConnect.setOnClickListener(v -> {
            if (isConnected) {
                stopVpnService(); 
            } else {
                String pass = editPassword.getText().toString().trim();
                String server = editServer.getText().toString().trim();
                
                if (server.isEmpty()) {
                    Toast.makeText(this, R.string.server_hint, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (pass.isEmpty()) {
                    Toast.makeText(this, R.string.password_hint, Toast.LENGTH_SHORT).show();
                    return;
                }

                // Start connection sequence after initial trace and IP discovery
                runInitialTrace(() -> {
                    runOnUiThread(() -> {
                        appendLog("ACTION: Iniciando secuencia de conexión...", true);
                        sendLocalizedStatus(R.string.log_connecting, null, false);
                        resolveAndConnect();
                    });
                });
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

        // Hide keyboard when touching the log area
        View.OnTouchListener hideKeyboardListener = (v, event) -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && getCurrentFocus() != null) {
                imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
            }
            return false; // Don't consume the event, let the ScrollView handle it
        };

        if (scrollViewLog != null) scrollViewLog.setOnTouchListener(hideKeyboardListener);
        if (textViewLog != null) textViewLog.setOnTouchListener(hideKeyboardListener);
    }

    private void appendLog(String message) {
        appendLog(message, false, true);
    }

    private void appendLog(String message, boolean isTechnical) {
        appendLog(message, isTechnical, true);
    }

    private void appendLog(String message, boolean isTechnical, boolean showTime) {
        runOnUiThread(() -> {
            if (textViewLog == null) return;
            String timePrefix = showTime ? "[" + dateFormat.format(new Date()) + "] " : "";
            String currentText = textViewLog.getText().toString();
            String entry = (currentText.isEmpty() ? "" : "\n") + timePrefix + message;
            
            // Save to internal technical log always
            String internalLog = getPreferences(MODE_PRIVATE).getString("internal_log", "");
            internalLog += entry;
            // Cap internal log size
            if (internalLog.length() > 50000) internalLog = internalLog.substring(internalLog.length() - 50000);
            getPreferences(MODE_PRIVATE).edit().putString("internal_log", internalLog).apply();

            // Display and save visible log only if NOT exclusively technical
            if (!isTechnical) {
                textViewLog.append(entry);
                scrollViewLog.post(() -> scrollViewLog.fullScroll(View.FOCUS_DOWN));
                
                String currentFullLog = textViewLog.getText().toString();
                getPreferences(MODE_PRIVATE).edit().putString("session_log", currentFullLog).apply();
            }
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

        try {
            String[] parts = serverUrl.split("/");
            if (parts.length < 5) {
                // Not a standard user/net URL, use as is
                checkIdentityHost(serverUrl, p, serverUrl);
                return;
            }

            String protocol = parts[0];
            String domainAndPort = parts[2];
            String user = parts[3];
            String net = parts[4];
            
            String finalUrl;
            if (resolvedIp != null && protocol.equalsIgnoreCase("ws:")) {
                String hostPart = resolvedIp;
                if (domainAndPort.contains(":")) {
                    hostPart += domainAndPort.substring(domainAndPort.indexOf(":"));
                }
                finalUrl = protocol + "//" + hostPart + "/" + user + "/" + net;
                appendLog("DNS_TRACE: Re-armado LUGAR_3+4 -> " + finalUrl, true);
                sendLocalizedStatus(R.string.log_optimized_conn, finalUrl, false);
            } else {
                finalUrl = serverUrl;
            }

            checkIdentityHost(serverUrl, p, finalUrl);

        } catch (Exception e) {
            appendLog("DNS_TRACE: Error en reconstrucción LUGAR: " + e.getMessage());
            checkIdentityHost(serverUrl, p, serverUrl);
        }

        appendLog("CODE: [Start] CandyVpnService.init()");
        isConnected = true;
        updateUI();
    }

    private void checkIdentityHost(String serverUrl, String p, String finalUrl) {
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        String host = extractDomain(serverUrl);
        String[] currentUN = extractUserNet(serverUrl);
        
        if (host == null || host.isEmpty() || currentUN == null) {
            launchVpnFinal(serverUrl, p, finalUrl, null);
            return;
        }

        String currentIdKey = getIdentityKey(serverUrl);
        // If this exact URL already has a generated identity, just use it directly
        if (!prefs.getString("id_" + currentIdKey, "").isEmpty()) {
            launchVpnFinal(serverUrl, p, finalUrl, null);
            return;
        }

        // 1. Direct/Resolved Host Match (Fast)
        String ownerUrl = prefs.getString("host_owner_" + host, "");
        if (ownerUrl.isEmpty() && resolvedIp != null) {
            ownerUrl = prefs.getString("host_owner_" + resolvedIp, "");
        }

        // 2. Deep Identity Search: Iterate through all known servers to find a fingerprint match
        if (ownerUrl.isEmpty()) {
            java.util.Map<String, ?> allEntries = prefs.getAll();
            for (java.util.Map.Entry<String, ?> entry : allEntries.entrySet()) {
                String key = entry.getKey();
                // Check if current resolved IP exists in the IP history of any previously seen domain
                if (key.startsWith("hist_") && resolvedIp != null) {
                    String history = (String) entry.getValue();
                    if (history.contains(resolvedIp)) {
                        String matchedDomain = key.substring(5);
                        ownerUrl = prefs.getString("host_owner_" + matchedDomain, "");
                        if (!ownerUrl.isEmpty()) {
                            appendLog("IDENTITY: Coincidencia por historial de IP con: " + matchedDomain, true);
                            break;
                        }
                    }
                }
                // Check if User + Network pattern exists in any other host (Credentials Match)
                if (key.startsWith("host_owner_")) {
                    String testUrl = (String) entry.getValue();
                    String[] testUN = extractUserNet(testUrl);
                    if (testUN != null && currentUN[0].equals(testUN[0]) && currentUN[1].equals(testUN[1])) {
                        ownerUrl = testUrl;
                        appendLog("IDENTITY: PATRÓN_MATCH (Mismo Usuario/Red en nuevo host) -> " + ownerUrl, true);
                        break;
                    }
                }
            }
        }

        // Validate found match against User/Net requirements
        boolean unMatch = false;
        if (!ownerUrl.isEmpty()) {
            String[] ownerUN = extractUserNet(ownerUrl);
            if (ownerUN != null && currentUN[0].equals(ownerUN[0]) && currentUN[1].equals(ownerUN[1])) {
                unMatch = true;
            }
        }

        String ownerIdKey = ownerUrl.isEmpty() ? "" : getIdentityKey(ownerUrl);
        
        // Final Decision: Prompt if a logical match is found but under a different URL/Host record
        if (!ownerUrl.isEmpty() && unMatch && !ownerIdKey.equals(currentIdKey)) {
            final String finalOwner = ownerUrl;
            final String fOwnerIdKey = ownerIdKey;
            runOnUiThread(() -> showCustomIdentityDialog(serverUrl, p, finalUrl, finalOwner, fOwnerIdKey));
        } else {
            // New logical server or already registered owner - Save mapping for future recognition
            prefs.edit().putString("host_owner_" + host, serverUrl).apply();
            if (resolvedIp != null) {
                prefs.edit().putString("host_owner_" + resolvedIp, serverUrl).apply();
            }
            launchVpnFinal(serverUrl, p, finalUrl, null);
        }
    }

    private void showCustomIdentityDialog(String serverUrl, String p, String finalUrl, String matchedUrl, String matchedIdKey) {
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.setContentView(R.layout.dialog_identity_match);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.setCancelable(false);

        TextView txtMatch = dialog.findViewById(R.id.matched_url);
        txtMatch.setText(matchedUrl);

        dialog.findViewById(R.id.btn_choose_other).setOnClickListener(v -> {
            dialog.dismiss();
            showOtherIdentitiesSelector(serverUrl, p, finalUrl);
        });

        dialog.findViewById(R.id.btn_no_new).setOnClickListener(v -> {
            dialog.dismiss();
            launchVpnFinal(serverUrl, p, finalUrl, null);
        });

        dialog.findViewById(R.id.btn_yes_persist).setOnClickListener(v -> {
            dialog.dismiss();
            launchVpnFinal(serverUrl, p, finalUrl, matchedIdKey);
        });

        dialog.show();
    }

    private void showOtherIdentitiesSelector(String serverUrl, String p, String finalUrl) {
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        java.util.Map<String, ?> allEntries = prefs.getAll();
        java.util.List<String> owners = new java.util.ArrayList<>();
        
        for (String key : allEntries.keySet()) {
            if (key.startsWith("host_owner_")) {
                String owner = (String) allEntries.get(key);
                if (owner != null && !owner.isEmpty() && !owners.contains(owner)) {
                    owners.add(owner);
                }
            }
        }

        if (owners.isEmpty()) {
            launchVpnFinal(serverUrl, p, finalUrl, null);
            return;
        }

        String[] ownerArray = owners.toArray(new String[0]);
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.select_other_title)
            .setItems(ownerArray, (dialog, which) -> {
                String selectedOwner = ownerArray[which];
                String selectedIdKey = getIdentityKey(selectedOwner);
                launchVpnFinal(serverUrl, p, finalUrl, selectedIdKey);
            })
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                showCustomIdentityDialog(serverUrl, p, finalUrl, ownerArray[0], getIdentityKey(ownerArray[0]));
            })
            .show();
    }

    private String[] extractUserNet(String url) {
        try {
            String[] parts = url.split("/");
            if (parts.length >= 5) {
                return new String[]{parts[3], parts[4]}; // User, Net
            }
        } catch (Exception e) {}
        return null;
    }

    private void launchVpnFinal(String serverUrl, String p, String finalUrl, String copyFromIdKey) {
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        String idKey = getIdentityKey(serverUrl);
        
        String clientId = prefs.getString("id_" + idKey, "");
        String vmac = prefs.getString("vmac_" + idKey, "");
        
        if (clientId.isEmpty() || vmac.isEmpty()) {
            if (copyFromIdKey != null) {
                clientId = prefs.getString("id_" + copyFromIdKey, "");
                vmac = prefs.getString("vmac_" + copyFromIdKey, "");
                appendLog("IDENTITY: Enlazando identidad existente para persistencia IP.", true);
            }
            
            // Re-check just in case copy failed or was null
            if (clientId.isEmpty() || vmac.isEmpty()) {
                clientId = UUID.randomUUID().toString().substring(0, 8);
                vmac = generateRandomHex(16);
                sendLocalizedStatus(R.string.log_identity_new, null, false);
            } else {
                sendLocalizedStatus(R.string.log_identity_restore, null, false);
            }
            prefs.edit().putString("id_" + idKey, clientId).putString("vmac_" + idKey, vmac).apply();
        } else {
            sendLocalizedStatus(R.string.log_identity_restore, null, false);
        }

        Intent i = new Intent(this, CandyVpnService.class);
        i.putExtra("server", finalUrl);
        i.putExtra("password", p);
        i.putExtra("client_id", clientId);
        i.putExtra("vmac", vmac);
        startService(i);
    }


    private void stopVpnService() {
        appendLog("CODE: [Stop] CandyVpnService.terminate()");
        Intent i = new Intent(this, CandyVpnService.class);
        i.setAction("STOP");
        startService(i);
        isConnected = false;
        if (txtIpAddress != null) txtIpAddress.setText(getString(R.string.ip_label) + "-");
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

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            View v = getCurrentFocus();
            if (v instanceof EditText) {
                Rect outRect = new Rect();
                v.getGlobalVisibleRect(outRect);
                if (!outRect.contains((int)event.getRawX(), (int)event.getRawY())) {
                    v.clearFocus();
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                }
            }
        }
        return super.dispatchTouchEvent(event);
    }
    private String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress();
                        boolean isIPv4 = sAddr.indexOf(':') < 0;
                        if (isIPv4) return sAddr;
                    }
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return "127.0.0.1";
    }

    private void fetchIps(boolean includeLocal, Runnable onComplete) {
        new Thread(() -> {
            String publicIp;
            try {
                URL url = new URL("https://api.ipify.org");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                publicIp = reader.readLine();
                reader.close();
            } catch (Exception e) {
                publicIp = "(Oculto o No Disponible)";
            }
            
            appendLog("NET_INFO: Public IP: " + publicIp);
            if (includeLocal) {
                appendLog("NET_INFO: Local IP: " + getLocalIpAddress());
            }
            if (onComplete != null) onComplete.run();
        }).start();
    }

    private String extractDomain(String url) {
        try {
            String[] parts = url.split("/");
            if (parts.length < 3) return null;
            String host = parts[2];
            if (host.contains(":")) host = host.split(":")[0];
            return host;
        } catch (Exception e) { return null; }
    }

    private void updateIpHistory(String domain, String ip) {
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        String key = "hist_" + domain;
        String history = prefs.getString(key, "");
        String[] ips = history.isEmpty() ? new String[0] : history.split(",");
        
        java.util.ArrayList<String> newList = new java.util.ArrayList<>();
        newList.add(ip);
        for (String existing : ips) {
            if (!existing.equals(ip) && newList.size() < 3) {
                newList.add(existing);
            }
        }
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < newList.size(); i++) {
            sb.append(newList.get(i));
            if (i < newList.size() - 1) sb.append(",");
        }
        prefs.edit().putString(key, sb.toString()).apply();
    }

    private String getIpFromHistory(String domain) {
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        String history = prefs.getString("hist_" + domain, "");
        if (history.isEmpty()) return null;
        String[] ips = history.split(",");
        appendLog("DNS_TRACE: Usando historial: " + history, true);
        return ips[0]; // Return the most recent successful one
    }

    private String resolveDnsDirectUdp(String domain) {
        String[] servers = {"8.8.8.8", "8.8.4.4", "1.1.1.1", "2001:4860:4860::8888", "2606:4700:4700::1111"};
        for (String dns : servers) {
            try {
                java.net.InetAddress addr = java.net.InetAddress.getByName(dns);
                // Note: Standard Java InetAddress.getByName already utilizes configured resolvers.
                // To force a specific server packet-wise would require a custom DNS client.
                // We use the system's ability to reach these as fallback.
                java.net.InetAddress resolved = java.net.InetAddress.getByName(domain);
                return resolved.getHostAddress();
            } catch (Exception e) {}
        }
        return null;
    }

    private String resolveDnsExternal(String domain) {
        String[] urls = {
            "https://dns.google/resolve?name=" + domain + "&type=A",
            "https://cloudflare-dns.com/dns-query?name=" + domain + "&type=A"
        };
        for (String url : urls) {
            String ip = queryDns(url);
            if (ip != null) return ip;
        }
        return null;
    }

    private String queryDns(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            
            String response = sb.toString();
            // Basic extraction from JSON response for the A record data
            int dataIdx = response.indexOf("\"data\":\"");
            if (dataIdx != -1) {
                int start = dataIdx + 8;
                int end = response.indexOf("\"", start);
                String foundIp = response.substring(start, end);
                if (foundIp.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) return foundIp;
            }
        } catch (Exception e) {
            // Ignore and try fallback
        }
        return null;
    }
}
