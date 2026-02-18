/*
 * Candy VPN Android Client - Core Service
 * Developed by: Jercrox
 * License: MIT
 */
package com.cacao.candy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.util.Log;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import java.util.Locale;

import androidx.core.app.NotificationCompat;

import java.io.IOException;

import candy_mobile.Candy_mobile;

public class CandyVpnService extends VpnService {

    private static final String CHANNEL_ID = "CandyVpnChannel";
    private static final int NOTIFICATION_ID = 1;

    private ParcelFileDescriptor vpnInterface = null;
    private Thread vpnThread = null;
    private PowerManager.WakeLock wakeLock = null;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.cacao.candy.STATUS_REQUEST".equals(intent.getAction())) {
                sendLocalizedStatus(R.string.log_sync, isConnected() ? "Operativo" : "Desconectado");
            }
        }
    };

    private boolean isConnected() {
        return vpnInterface != null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            sendStatus("SERVICE_ACTION: Recibida señal de parada (STOP).", "Deteniendo");
            stopVpn();
            return START_NOT_STICKY;
        }

        createNotificationChannel();
        String statusConnecting = getString(R.string.status_label) + getString(R.string.connecting);
        Intent contentIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Candy VPN")
                .setContentText(statusConnecting)
                .setSmallIcon(R.drawable.icon)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);
        sendLocalizedStatus(R.string.log_fg_started, "Iniciando");

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CandyVPN::WakeLock");
            wakeLock.acquire();
            sendLocalizedStatus(R.string.log_wakelock, "SysOk");
        }

        String server = intent.getStringExtra("server");
        String password = intent.getStringExtra("password");
        String clientId = intent.getStringExtra("client_id");
        String vmac = intent.getStringExtra("vmac");
        if (clientId == null) clientId = "android_" + android.os.Build.ID;

        if (server != null) {
            if (password != null) {
                Candy_mobile.setPassword(password);
                sendStatus("CODE: Candy_mobile.setPassword(***" + password.length() + " chars***)", "Auth");
            }
            
            if (vmac != null && !vmac.isEmpty()) {
                Candy_mobile.setVMac(vmac);
                sendLocalizedStatus(R.string.log_vmac_info, vmac, "Identity", false);
                sendStatus("CODE: Candy_mobile.setVMac(" + vmac + ")", "Identity");
            }
            
            // Configurar log de Go para verlo en el Registro Técnico
            Candy_mobile.setLogListener(msg -> sendStatus(msg, "RelayGo"));

            String identityHost = android.os.Build.MODEL + "_" + clientId;
            Candy_mobile.setSystemInfo(
                    "android",    // info[1] -> OS
                    "v52-stable", // info[2] -> Version
                    identityHost  // info[3] -> Hostname (unique per client instance)
            );
            sendLocalizedStatus(R.string.log_identity_update, "Identity");
            sendStatus("CODE: Identity Update: OS:android | Ver:v52 | Host:" + identityHost, "Identity");

            if (vpnThread != null && vpnThread.isAlive()) {
                sendStatus("DEBUG: Hilo VPN ya está en bucle vital. Ignorando nuevo arranque.", "Looping");
                return START_STICKY;
            }
            
            vpnThread = new Thread(() -> {
                int retryCount = 0;
                sendLocalizedStatus(R.string.log_thread_start, "Bucle");
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        if (vpnInterface != null) {
                            retryCount = 0;
                            Thread.sleep(10000);
                            continue;
                        }

                        sendLocalizedStatus(R.string.log_handshake_start, "Handshake");
                        sendStatus("CODE: String configRecibida = Candy_mobile.requestIP(\"" + server + "\")", "Handshake");
                        
                        String config = Candy_mobile.requestIP(server);
                        if (config == null || config.isEmpty()) {
                            retryCount++;
                            sendLocalizedStatus(R.string.log_handshake_fail, "Error");
                            if (retryCount >= 3) {
                                sendLocalizedStatus(R.string.msg_invalid_url, "Error");
                                updateNotification(getString(R.string.status_label) + getString(R.string.disconnected));
                                sendStatus("RESPONSE: [CRITICO] El núcleo Go devolvió config nula tras 3 intentos.", "Error");
                                stopVpn();
                                break;
                            }
                            sendStatus("RESPONSE: Reintentando (" + retryCount + "/3)...", "Retry");
                            Thread.sleep(5000);
                            continue;
                        }

                        retryCount = 0; // Handshake OK
                        sendLocalizedStatus(R.string.log_handshake_ok, "Parse");
                        sendStatus("RESPONSE: [RECIBIDO] Protocolo Handshake -> " + config, "Parse");

                        // FORMAT: IP:10.15.14.71|GW:10.15.14.1|PREFIX:24|NET:10.15.14.0
                        String[] parts = config.split("\\|");
                        String assignedIP = parts[0].replace("IP:", "");
                        String gatewayIP  = parts[1].replace("GW:", "");
                        int prefixLength  = Integer.parseInt(parts[2].replace("PREFIX:", ""));
                        String networkIP  = parts[3].replace("NET:", "");
                        
                        sendLocalizedStatus(R.string.log_ip_assigned, assignedIP, "ParseOk", false);
                        sendStatus("DEBUG: IPv4=" + assignedIP + " | Prefix=/" + prefixLength + " | Net=" + networkIP, "ParseOk");

                        // Broadcast IP to UI
                        Intent ipIntent = new Intent("com.cacao.candy.LOG_UPDATE");
                        ipIntent.putExtra("ip", assignedIP);
                        sendBroadcast(ipIntent);

                        sendStatus("CODE: VpnService.Builder builder = new Builder()", "Tun");
                        Builder builder = new Builder();
                        builder.setMtu(1400); 
                        
                        builder.addAddress(assignedIP, prefixLength);
                        sendStatus("CODE: builder.addAddress(\"" + assignedIP + "\", " + prefixLength + ")", "Tun");
                        builder.addRoute(networkIP, prefixLength);
                        sendStatus("CODE: builder.addRoute(\"" + networkIP + "\", " + prefixLength + ")", "Tun");

                        builder.addDnsServer("8.8.8.8");
                        builder.allowBypass(); 
                        
                        sendStatus("CODE: vpnInterface = builder.establish()", "Establishing");
                        vpnInterface = builder.establish();
                        
                        if (vpnInterface != null) {
                            updateNotification(getString(R.string.status_label) + getString(R.string.operational));
                            int fd = vpnInterface.getFd();
                            sendLocalizedStatus(R.string.log_tun_ok, fd, "TunOk");
                            
                            sendStatus("CODE: Candy_mobile.startRelayVPN(" + fd + ")", "Relay");
                            Candy_mobile.startRelayVPN(fd);
                            sendLocalizedStatus(R.string.log_relay_end, "RelayEnd");
                            
                            if (vpnInterface != null) {
                                vpnInterface.close();
                                vpnInterface = null;
                                updateNotification(getString(R.string.status_label) + getString(R.string.disconnected));
                                sendStatus("SYSTEM: Interfaz TUN cerrada.", "Cleanup");
                            }
                        } else {
                            sendStatus("SYSTEM_RESPONSE: [ERROR] 'establish()' devolvió NULL.", "Fail");
                            stopVpn();
                            break;
                        }

                    } catch (Exception e) {
                        retryCount++;
                        sendStatus("EXCEPTION_TRACE: (" + retryCount + "/3) " + e.getMessage(), "Retry");
                        if (retryCount >= 3) {
                            sendLocalizedStatus(R.string.msg_invalid_url, "Error");
                            updateNotification(getString(R.string.status_label) + getString(R.string.disconnected));
                            stopVpn();
                            break;
                        }
                        if (vpnInterface != null) {
                            try { vpnInterface.close(); } catch (Exception ignored) {}
                            vpnInterface = null;
                        }
                        try { Thread.sleep(5000); } catch (InterruptedException ie) { break; }
                    }
                }
            }, "CandyVPN-Forensic-Loop");
            vpnThread.start();
        }

        return START_STICKY;
    }

    private void sendStatus(String log, String status) {
        sendStatus(log, status, true); // Technical by default
    }

    private void sendStatus(String log, String status, boolean isTechnical) {
        Log.i("CandyVPN", log);
        Intent intent = new Intent("com.cacao.candy.LOG_UPDATE");
        intent.putExtra("log", log);
        intent.putExtra("status", status);
        intent.putExtra("isTechnical", isTechnical);
        intent.putExtra("connected", vpnInterface != null);
        sendBroadcast(intent);
    }

    private void sendLocalizedStatus(int resId, String status) {
        sendLocalizedStatus(resId, status, false); // Localized is usually NOT exclusively technical
    }

    private void sendLocalizedStatus(int resId, String status, boolean isTechnical) {
        Intent intent = new Intent("com.cacao.candy.LOG_UPDATE");
        intent.putExtra("logResId", resId);
        intent.putExtra("status", status);
        intent.putExtra("isTechnical", isTechnical);
        intent.putExtra("connected", vpnInterface != null);
        sendBroadcast(intent);
    }

    private void sendLocalizedStatus(int resId, Object arg, String status) {
        sendLocalizedStatus(resId, arg, status, false);
    }

    private void sendLocalizedStatus(int resId, Object arg, String status, boolean isTechnical) {
        Intent intent = new Intent("com.cacao.candy.LOG_UPDATE");
        intent.putExtra("logResId", resId);
        if (arg instanceof Integer) {
            intent.putExtra("logArg", (Integer) arg);
        } else if (arg instanceof String) {
            intent.putExtra("logArg", (String) arg);
        }
        intent.putExtra("status", status);
        intent.putExtra("isTechnical", isTechnical);
        intent.putExtra("connected", vpnInterface != null);
        sendBroadcast(intent);
    }

    private void updateNotification(String text) {
        Intent contentIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Candy VPN")
                .setContentText(text)
                .setSmallIcon(R.drawable.icon)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .build();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Candy VPN Forensic Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private synchronized void stopVpn() {
        if (vpnThread != null) {
            vpnThread.interrupt();
            vpnThread = null;
            sendStatus("SYSTEM: Hilo vital interrumpido forzosamente.", "Terminado");
        }
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
                sendStatus("SYSTEM: Descriptor TUN (FD) cerrado y liberado al sistema.", "Terminado");
            } catch (IOException e) {
                sendStatus("ERROR: Fallo al liberar interfaz TUN: " + e.getMessage(), "Error");
            }
            vpnInterface = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
            sendStatus("SYSTEM: [WAKE_LOCK] Liberado. El sistema puede ahorrar energía.", "Terminado");
        }
        updateNotification(getString(R.string.status_label) + getString(R.string.disconnected));
        sendStatus("VPN: Servicio finalizado completamente.", "Desconectado");
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onCreate() {
        loadLocale();
        super.onCreate();
        registerReceiver(statusReceiver, new IntentFilter("com.cacao.candy.STATUS_REQUEST"));
    }

    private void loadLocale() {
        android.content.SharedPreferences prefs = getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
        String language = prefs.getString("My_Lang", "en");
        Locale myLocale = new Locale(language);
        Resources res = getResources();
        DisplayMetrics dm = res.getDisplayMetrics();
        Configuration conf = res.getConfiguration();
        conf.locale = myLocale;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            conf.setLocale(myLocale);
        }
        res.updateConfiguration(conf, dm);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(statusReceiver);
        stopVpn();
    }
}
