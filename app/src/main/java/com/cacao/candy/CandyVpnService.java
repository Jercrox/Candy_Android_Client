package com.cacao.candy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.util.Log;

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
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Candy VPN Forensic Core")
                .setContentText("Handshake Sincronizado Activo")
                .setSmallIcon(R.drawable.icon)
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
        if (clientId == null) clientId = "android_" + android.os.Build.ID;

        if (server != null) {
            if (password != null) {
                Candy_mobile.setPassword(password);
                sendStatus("CODE: Candy_mobile.setPassword(***" + password.length() + " chars***)", "Auth");
            }
            
            // Configurar log de Go para verlo en el Registro Técnico
            Candy_mobile.setLogListener(msg -> sendStatus(msg, "RelayGo"));

            String identityHost = android.os.Build.MODEL + "_" + clientId;
            Candy_mobile.setSystemInfo(
                    "android",    // info[1] -> OS
                    "v52-stable", // info[2] -> Version
                    identityHost  // info[3] -> Hostname (unique per client instance)
            );
            sendStatus("CODE: Identity Update: OS:android | Ver:v52 | Host:" + identityHost, "Identity");

            if (vpnThread != null && vpnThread.isAlive()) {
                sendStatus("DEBUG: Hilo VPN ya está en bucle vital. Ignorando nuevo arranque.", "Looping");
                return START_STICKY;
            }
            
            vpnThread = new Thread(() -> {
                sendLocalizedStatus(R.string.log_thread_start, "Bucle");
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        if (vpnInterface != null) {
                            Thread.sleep(10000);
                            continue;
                        }

                        sendStatus("CODE: String configRecibida = Candy_mobile.requestIP(\"" + server + "\")", "Handshake");
                        
                        String config = Candy_mobile.requestIP(server);
                        if (config == null || config.isEmpty()) {
                            sendStatus("RESPONSE: [CRITICO] El núcleo Go devolvió config nula. Reintentando...", "Error");
                            throw new Exception("Configuración vacía del núcleo");
                        }
                        sendStatus("RESPONSE: [RECIBIDO] Protocolo Handshake -> " + config, "Parse");

                        // FORMAT: IP:10.15.14.71|GW:10.15.14.1|PREFIX:24|NET:10.15.14.0
                        String[] parts = config.split("\\|");
                        String assignedIP = parts[0].replace("IP:", "");
                        String gatewayIP  = parts[1].replace("GW:", "");
                        int prefixLength  = Integer.parseInt(parts[2].replace("PREFIX:", ""));
                        String networkIP  = parts[3].replace("NET:", "");
                        
                        sendStatus("DEBUG: IPv4=" + assignedIP + " | Prefix=/" + prefixLength + " | Net=" + networkIP, "ParseOk");

                        // Broadcast IP to UI
                        Intent ipIntent = new Intent("com.cacao.candy.LOG_UPDATE");
                        ipIntent.putExtra("ip", assignedIP);
                        sendBroadcast(ipIntent);

                        sendStatus("CODE: VpnService.Builder builder = new Builder()", "Tun");
                        Builder builder = new Builder();
                        
                        builder.setMtu(1400); 
                        
                        // Interface Address with dynamic Prefix
                        builder.addAddress(assignedIP, prefixLength);
                        sendStatus("CODE: builder.addAddress(\"" + assignedIP + "\", " + prefixLength + ")", "Tun");
                        
                        // DYNAMIC ROUTE: Route traffic for the subnet assigned by server through the VPN
                        // This handles 10.15.14.0/24 or 10.0.0.0/8 depending on server config.
                        builder.addRoute(networkIP, prefixLength);
                        sendStatus("CODE: builder.addRoute(\"" + networkIP + "\", " + prefixLength + ")", "Tun");

                        // OPTIONAL: Standard comprehensive routes if user wants broader access
                        // builder.addRoute("10.0.0.0", 8);
                        // builder.addRoute("172.16.0.0", 12);
                        // builder.addRoute("192.168.0.0", 16);

                        builder.addDnsServer("8.8.8.8");
                        builder.allowBypass(); 
                        
                        sendStatus("CODE: vpnInterface = builder.establish()", "Establishing");
                        vpnInterface = builder.establish();
                        
                        if (vpnInterface != null) {
                            int fd = vpnInterface.getFd();
                            sendLocalizedStatus(R.string.log_tun_ok, fd, "TunOk");
                            
                            sendStatus("CODE: Candy_mobile.startRelayVPN(" + fd + ")", "Relay");
                            sendStatus("DEBUG: Cedido control del Descriptor de Archivo al motor C++/Go.", "Handover");
                            Candy_mobile.startRelayVPN(fd);
                            sendLocalizedStatus(R.string.log_relay_end, "RelayEnd");
                            
                            if (vpnInterface != null) {
                                vpnInterface.close();
                                vpnInterface = null;
                                sendStatus("SYSTEM: Interfaz TUN cerrada para permitir reconexión limpia.", "Cleanup");
                            }
                        } else {
                            sendStatus("SYSTEM_RESPONSE: [ERROR] 'establish()' devolvió NULL. Probablemente el usuario denegó o hay otro VPN activo.", "Fail");
                        }

                    } catch (Exception e) {
                        sendStatus("EXCEPTION_TRACE: " + e.getClass().getName() + ": " + e.getMessage(), "Retry");
                        if (vpnInterface != null) {
                            try { vpnInterface.close(); } catch (Exception ignored) {}
                            vpnInterface = null;
                        }
                    }
                    try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
                }
            }, "CandyVPN-Forensic-Loop");
            vpnThread.start();
        }

        return START_STICKY;
    }

    private void sendStatus(String log, String status) {
        Log.i("CandyVPN", log);
        Intent intent = new Intent("com.cacao.candy.LOG_UPDATE");
        intent.putExtra("log", log);
        intent.putExtra("status", status);
        intent.putExtra("connected", vpnInterface != null);
        sendBroadcast(intent);
    }

    private void sendLocalizedStatus(int resId, String status) {
        Intent intent = new Intent("com.cacao.candy.LOG_UPDATE");
        intent.putExtra("logResId", resId);
        intent.putExtra("status", status);
        intent.putExtra("connected", vpnInterface != null);
        sendBroadcast(intent);
    }

    private void sendLocalizedStatus(int resId, int arg, String status) {
        Intent intent = new Intent("com.cacao.candy.LOG_UPDATE");
        intent.putExtra("logResId", resId);
        intent.putExtra("logArg", arg);
        intent.putExtra("status", status);
        intent.putExtra("connected", vpnInterface != null);
        sendBroadcast(intent);
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
        sendStatus("VPN: Servicio finalizado completamente.", "Desconectado");
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        registerReceiver(statusReceiver, new IntentFilter("com.cacao.candy.STATUS_REQUEST"));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(statusReceiver);
        stopVpn();
    }
}
