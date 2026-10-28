package dev.a110.joyingbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import com.car.ipc.ICallback;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

public final class BridgeService extends Service {
    
    static final String STOP = "dev.a110.joyingbridge.STOP";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(128));
    private final AtomicLong dropped = new AtomicLong();
    private JoyingClient client;
    private MediaPublisher publisher;
    private android.content.BroadcastReceiver receiver;
    private final Runnable publish = () -> {
        if (publisher != null) publisher.publish(BridgeRuntime.state.media());
    };
    private void schedulePublish() {
        main.removeCallbacks(publish);
        main.postDelayed(publish, 120);
    }
    private volatile boolean running;
    private String stopReason = "Stopped";
    private String lastConnectionStatus;

    private final AtomicLong sourceEpoch = new AtomicLong();
    private volatile boolean directSelected;
    private volatile ZlinkClient directClient;
    private ZlinkState directState;
    private long nextDirectAttempt, directConnection;

    private void sourceTask(long epoch, Runnable action) {
        if (running && sourceEpoch.get() == epoch) enqueue(() -> {
            if (running && sourceEpoch.get() == epoch) action.run();
        });
    }
    private ICallback.Stub joyingCallback(long epoch) {
        return new ICallback.Stub() {
            @Override public void update(Bundle data) {
                if (data == null) return;
                Bundle copy = new Bundle(data);
                sourceTask(epoch, () -> {
                    CaptureLog.callback(copy);
                    try { BridgeRuntime.state.accept(copy); schedulePublish(); }
                    catch (RuntimeException error) { CaptureLog.record("event_error", error.toString()); }
                });
            }
        };
    }
    private void selectSource(boolean direct) {
        directSelected = direct;
        long epoch = sourceEpoch.incrementAndGet();
        // Pending callbacks belong to the old source. Free their queue slots so the
        // control operation cannot be dropped under a burst of direct metadata.
        worker.getQueue().clear();
        // Close the direct socket promptly so switching back cannot leave a second reader active.
        ZlinkClient old = directClient;
        if (old != null) old.close();
        sourceTask(epoch, () -> {
            if (client != null) { client.close(); client = null; }
            if (directClient != null) directClient.close();
            directClient = null;
            directState = new ZlinkState(BridgeRuntime.state, android.os.SystemClock::elapsedRealtime);
            nextDirectAttempt = 0;
            BridgeRuntime.state.source(direct);
            BridgeRuntime.directSource = direct;
            BridgeRuntime.directBytes = BridgeRuntime.directFrames = 0;
            BridgeRuntime.directDetails = "";
            lastConnectionStatus = null;
            CaptureLog.record("source_changed", direct ? "ZLink direct selected" : "Joying selected");
            connectionStatus(direct ? "Connecting to ZLink direct" : "Connecting to Joying");
            schedulePublish();
            connectSource(epoch);
        });
    }
    private void connectSource(long epoch) {
        if (!running || sourceEpoch.get() != epoch) return;
        if (directSelected) {
            if (directClient != null && directClient.alive()) return;
            long now = android.os.SystemClock.elapsedRealtime();
            if (now < nextDirectAttempt) return;
            if (directClient != null) directClient.close();
            nextDirectAttempt = now + 15_000;
            long connection = ++directConnection;
            directClient = new ZlinkClient(new ZlinkClient.Listener() {
                private long offset;
                @Override public void status(String value) { sourceTask(epoch, () -> connectionStatus(value)); }
                @Override public void raw(byte[] bytes, int length) {
                    if (!running || sourceEpoch.get() != epoch) return;
                    CaptureLog.zlinkRaw(connection, offset, bytes, length); offset += length;
                    BridgeRuntime.directBytes += length;
                }
                @Override public void frame(long type, byte[] payload) {
                    if (!running || sourceEpoch.get() != epoch) return;
                    CaptureLog.zlinkFrame(connection, type, payload);
                    sourceTask(epoch, () -> {
                        BridgeRuntime.directFrames++;
                        try {
                            directState.accept(type, payload);
                            BridgeRuntime.directDetails = directState.describe();
                            connectionStatus("Receiving ZLink metadata");
                            CaptureLog.record("zlink_decoded", ZlinkMetadata.name(type) + "; "
                                    + directState.describe() + "\n" + BridgeRuntime.state.describe());
                            schedulePublish();
                        } catch (Exception e) { CaptureLog.record("zlink_decode_error", "type=" + type + "; " + e); }
                    });
                }
                @Override public void error(String message) {
                    sourceTask(epoch, () -> {
                        CaptureLog.record("zlink_reader", message);
                        BridgeRuntime.state.lostSource(); directState.reset(); schedulePublish();
                        connectionStatus("ZLink reader error; see diagnostics");
                    });
                }
                @Override public void ended() {
                    sourceTask(epoch, () -> {
                        BridgeRuntime.state.lostSource(); directState.reset();
                        nextDirectAttempt = android.os.SystemClock.elapsedRealtime() + 15_000;
                        schedulePublish();
                    });
                }
            });
            return;
        }
        if (client != null && client.alive()) return;
        if (client != null) { BridgeRuntime.state.lostSource(); schedulePublish(); }
        try {
            if (client == null) client = new JoyingClient(getContentResolver(), joyingCallback(epoch),
                    () -> sourceTask(epoch, () -> {
                        client.close(); BridgeRuntime.state.lostSource(); schedulePublish();
                        connectionStatus("Joying service disconnected; retrying");
                    }));
            client.connect(); connectionStatus("Registration sent; waiting for Joying callbacks");
        } catch (Exception e) { connectionStatus("Subscription failed: " + e.getClass().getSimpleName() + ": " + e.getMessage()); }
    }

    private void enqueue(Runnable task) {
        if (!running) return;
        try { worker.execute(task); }
        catch (RejectedExecutionException e) { dropped.incrementAndGet(); }
    }

    private final Runnable check = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long epoch = sourceEpoch.get();
            sourceTask(epoch, () -> {
                long count = dropped.getAndSet(0);
                if (count > 0) CaptureLog.record("dropped", "Worker queue full; dropped " + count + " events/tasks");
                connectSource(epoch);
            });
            main.postDelayed(this, 5000);
        }
    };

    private void connectionStatus(String value) {
        if (!value.equals(lastConnectionStatus)) {
            CaptureLog.status(value);
            BridgeRuntime.sourceStatus = value;
            lastConnectionStatus = value;
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            getSharedPreferences("bridge", 0).edit().putBoolean("enabled", false).apply();
            stopSelf(); return START_NOT_STICKY;
        }
        if (!getSharedPreferences("bridge", 0).getBoolean("enabled", false)) { stopSelf(); return START_NOT_STICKY; }
        if (running) {
            boolean direct = getSharedPreferences("bridge", 0).getBoolean("directZlink", false);
            if (direct != directSelected) selectSource(direct);
            schedulePublish(); return START_STICKY;
        }
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("capture", "Cluster bridge",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, BridgeService.class).setAction(STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "capture")
                .setSmallIcon(R.drawable.ic_probe).setContentTitle("ZLink Cluster Bridge")
                .setContentText("Publishing CarPlay metadata to MyAlpDroid")
                .setContentIntent(open).setOngoing(true).addAction(0, "Stop", stop).build();
        if (android.os.Build.VERSION.SDK_INT >= 34)
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else if (android.os.Build.VERSION.SDK_INT >= 29)
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(1, notification);
        try { CaptureLog.begin(this); }
        catch (Exception e) { CaptureLog.status("Cannot start log: " + e); stopSelf(); return START_NOT_STICKY; }
        BridgeRuntime.state.start();
        BridgeRuntime.state.inferPlaying(getSharedPreferences("bridge", 0).getBoolean("inferPlaying", true));
        publisher = new MediaPublisher(this);
        receiver = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context context, Intent event) {
                String status = event.getStringExtra("status");
                long epoch = sourceEpoch.get();
                sourceTask(epoch, () -> {
                    if (directSelected && directState != null) directState.status(status);
                    BridgeRuntime.state.status(status);
                    CaptureLog.record("zlink_status", String.valueOf(status));
                    schedulePublish();
                });
            }
        };
        registerZlinkReceiver();
        running = true;
        selectSource(getSharedPreferences("bridge", 0).getBoolean("directZlink", false));
        main.post(check);
        getSharedPreferences("bridge", 0).edit().remove("startupError").apply();
        return START_STICKY;
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerZlinkReceiver() {
        android.content.IntentFilter filter = new android.content.IntentFilter("com.zjinnova.zlink");
        // Explicit export flag on API 33+; the older overload is required on earlier releases.
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_EXPORTED);
        else registerReceiver(receiver, filter);
    }

    private void requestStop(String reason) {
        main.post(() -> { if (running) { stopReason = reason; stopSelf(); } });
    }

    @Override public void onDestroy() {
        running = false; sourceEpoch.incrementAndGet();
        if (directClient != null) directClient.close();
        BridgeRuntime.sourceStatus = "Stopped";
        BridgeRuntime.state.stop();
        if (receiver != null) unregisterReceiver(receiver);
        if (publisher != null) { publisher.publish(BridgeRuntime.state.media()); publisher.close(); }
        main.removeCallbacksAndMessages(null);
        // Stop accepting callbacks, then drain already-received events before unregistering.
        worker.getQueue().clear();
        worker.execute(() -> {
            if (client != null) client.close();
            if (directClient != null) directClient.close();
            CaptureLog.status(stopReason);
            CaptureLog.stopped();
        });
        worker.shutdown();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public void onTimeout(int startId, int fgsType) { requestStop("Android foreground-service timeout"); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
