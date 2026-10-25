package com.jake.duolauncher.bridge;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.*;
import java.util.HashSet;
import java.util.Set;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

/** Shell-owned, local-only helper for the two documented device-state commands.
 * A private 256-bit pairing token authenticates both ends before any command.
 * Fixed CONFIG, COVER_STANDBY and LOCK_CLOCK operations configure bounded angles and
 * request the device's own TENT routing. Disconnect/STOP release the override. */
public final class CoverBridge {
    private static final int PORT = 48632;
    private static byte[] secret;
    private static String proof(String text) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return java.util.Base64.getEncoder().encodeToString(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }
    private static volatile boolean active;
    private static volatile String stateId;
    private static volatile String activeStateId;
    private static volatile String tentStateId;
    private static volatile boolean standby;
    private static AndroidHingeReader hingeReader;
    private static int minimumAngle = 10, maximumAngle = 80;
    private static boolean lockedClock;
    private static android.app.KeyguardManager keyguard;
    static boolean angleAllowed(float angle, int minimum, int maximum) {
        return Float.isFinite(angle) && angle >= minimum && angle <= maximum;
    }
    static int[] parseAngles(String line) {
        if (line == null || !line.matches("CONFIG:[0-9]{1,3}:[0-9]{1,3}")) return null;
        String[] parts = line.split(":");
        int low = Integer.parseInt(parts[1]), high = Integer.parseInt(parts[2]);
        return low >= 5 && high <= 170 && high - low >= 5 ? new int[]{low, high} : null;
    }
    static boolean keyguardFromDump(String dump) {
        return Pattern.compile("(?m)^\\s*showing=true\\s*$").matcher(dump).find();
    }
    private static boolean keyguardLocked() throws Exception {
        if (keyguard != null) return keyguard.isKeyguardLocked();
        return keyguardFromDump(command("/system/bin/dumpsys", "window", "policy"));
    }
    private static final Set<String> openStates = new HashSet<>();
    private static final Set<String> tentStates = new HashSet<>();
    private static boolean physicalPanelOpen() throws Exception {
        String dump = command("/system/bin/dumpsys", "device_state");
        Matcher base = Pattern.compile("mBaseState=Optional\\[DeviceState\\{identifier=(\\d+),").matcher(dump);
        return base.find() && openStates.contains(base.group(1));
    }
    private static boolean physicalTentCapable() throws Exception {
        String dump = command("/system/bin/dumpsys", "device_state");
        Matcher base = Pattern.compile("mBaseState=Optional\\[DeviceState\\{identifier=(\\d+),").matcher(dump);
        return base.find() && (openStates.contains(base.group(1)) || tentStates.contains(base.group(1)));
    }
    private static void requestTent() throws Exception {
        if (tentStateId == null || !physicalTentCapable() || !physicalStandbyAngle())
            throw new IOException("Physical tent posture unavailable");
        activeStateId = tentStateId;
        active = true; // Reset even if the command succeeds but its response is lost.
        command("/system/bin/cmd", "device_state", "state", tentStateId);
    }
    private static boolean physicalStandbyAngle() throws Exception {
        float measured = hingeReader == null ? Float.NaN : hingeReader.degrees;
        if (Float.isFinite(measured)) return angleAllowed(measured, minimumAngle, maximumAngle);
        // device_state retains the last coarse vendor posture event, which can be
        // 86 degrees while the public hinge sensor has since reached 51 degrees.
        // Use the same direct on-change stream as the foreground app.
        String dump = command("/system/bin/dumpsys", "sensorservice");
        float degrees = hingeAngleFromDump(dump);
        return angleAllowed(degrees, minimumAngle, maximumAngle);
    }

    /** Public sensor subscription avoids running dumpsys every protocol heartbeat. */
    private static final class AndroidHingeReader implements android.hardware.SensorEventListener {
        private final android.os.HandlerThread thread = new android.os.HandlerThread("18DuoCoverHinge");
        private android.hardware.SensorManager sensors;
        volatile float degrees = Float.NaN;
        private long at = -1;
        void start() throws Exception {
            if (android.os.Looper.myLooper() == null) android.os.Looper.prepare();
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object instance = activityThread.getMethod("systemMain").invoke(null);
            android.content.Context context = (android.content.Context) activityThread
                .getMethod("getSystemContext").invoke(instance);
            try { keyguard = context.getSystemService(android.app.KeyguardManager.class); }
            catch (RuntimeException ignored) { keyguard = null; }
            sensors = context.getSystemService(android.hardware.SensorManager.class);
            android.hardware.Sensor sensor = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_HINGE_ANGLE);
            if (sensor == null) throw new IOException("No public hinge sensor");
            thread.start();
            if (!sensors.registerListener(this, sensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL,
                    new android.os.Handler(thread.getLooper()))) throw new IOException("Hinge subscription rejected");
            System.out.println("Public hinge subscription active");
        }
        void stop() {
            if (sensors != null) sensors.unregisterListener(this);
            thread.quitSafely();
        }
        @Override public void onSensorChanged(android.hardware.SensorEvent event) {
            if (event.timestamp <= at || event.values.length == 0) return;
            float value = event.values[0];
            if (Float.isFinite(value) && value >= 0 && value <= 180) { at = event.timestamp; degrees = value; }
        }
        @Override public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) {}
    }
    static float hingeAngleFromDump(String dump) {
        Matcher events = Pattern.compile("(?m)^hinge_angle (?:Wakeup|Non-wakeup): last \\d+ events\\n((?:\\t[^\\n]*(?:\\n|$))+)" ).matcher(dump);
        float degrees = Float.NaN;
        double newest = -1;
        while (events.find()) {
            Matcher angle = Pattern.compile("\\(ts=([0-9.]+), wall=[^)]+\\) ([0-9.]+),").matcher(events.group(1));
            while (angle.find()) {
                double at = Double.parseDouble(angle.group(1));
                float value = Float.parseFloat(angle.group(2));
                if (Float.isFinite(value) && value >= 0 && value <= 180 && at > newest) {
                    degrees = value; newest = at;
                }
            }
        }
        return degrees;
    }
    private static synchronized String command(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        // Drain concurrently: sensorservice is larger than the pipe buffer.
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
            try { return process.getInputStream().readAllBytes(); }
            catch (IOException error) { throw new java.io.UncheckedIOException(error); }
        });
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("System command timed out");
        }
        byte[] out = output.get(5, TimeUnit.SECONDS);
        if (process.exitValue() != 0) throw new IOException("System rejected request");
        return new String(out, StandardCharsets.UTF_8);
    }
    private static synchronized void reset() {
        if (!active) return;
        try {
            // A state selected by another requester is no longer ours to reset.
            // The committed state can briefly lag behind the pending override.
            String dump = command("/system/bin/dumpsys", "device_state");
            Matcher override = Pattern.compile("mOverrideState=Optional\\[DeviceState\\{identifier=(\\d+),").matcher(dump);
            boolean owned = override.find() ? override.group(1).equals(activeStateId)
                : command("/system/bin/cmd", "device_state", "print-state").trim().equals(activeStateId);
            if (owned) {
                command("/system/bin/cmd", "device_state", "state", "reset");
                System.out.println("Display override released");
            }
            active = false;
            activeStateId = null;
        }
        catch (Exception error) { System.err.println("release failed: " + error.getClass().getSimpleName()); }
    }
    public static void main(String[] args) throws Exception {
        secret = java.nio.file.Files.readString(java.nio.file.Path.of("/data/local/tmp/18Duo-cover-token")).trim().getBytes(StandardCharsets.UTF_8);
        if (secret.length != 64) throw new IOException("Missing local pairing token");
        String packages = command("/system/bin/cmd", "package", "list", "packages", "-U", "com.jake.duolauncher.core");
        Matcher uid = Pattern.compile("package:com\\.jake\\.duolauncher\\.core uid:(\\d+)(?:\\s|$)").matcher(packages);
        if (!uid.find()) throw new IOException("Core is not installed");
        String states = command("/system/bin/cmd", "device_state", "print-states");
        Matcher presentation = Pattern.compile("identifier=(\\d+), name='OPENED_PRESENTATION'").matcher(states);
        if (presentation.find()) stateId = presentation.group(1);
        Matcher opened = Pattern.compile("identifier=(\\d+), name='(?:OPENED|HALF_OPENED|OPENED_REVERSE)'").matcher(states);
        while (opened.find()) openStates.add(opened.group(1));
        Matcher tent = Pattern.compile("identifier=(\\d+), name='TENT'").matcher(states);
        while (tent.find()) { tentStateId = tent.group(1); tentStates.add(tentStateId); }
        if (tentStateId == null && stateId == null) throw new IOException("No supported display state");
        Runtime.getRuntime().addShutdownHook(new Thread(CoverBridge::reset));
        hingeReader = new AndroidHingeReader();
        try { hingeReader.start(); }
        catch (Exception error) {
            hingeReader.stop(); hingeReader = null;
            System.err.println("Public hinge subscription unavailable: " + error.getClass().getSimpleName() + "; using bounded snapshot fallback");
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { if (hingeReader != null) hingeReader.stop(); }));
        try (ServerSocket server = new ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1"))) {
            System.out.println("Ready; loopback pairing required; state=" + stateId);
            while (true) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(6000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    PrintWriter output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                    String challenge = input.readLine();
                    if (challenge == null || !challenge.matches("[a-f0-9]{64}")) continue;
                    byte[] fresh = new byte[32]; new SecureRandom().nextBytes(fresh);
                    StringBuilder serverNonce = new StringBuilder();
                    for (byte b : fresh) serverNonce.append(String.format("%02x", b & 0xff));
                    String combined = challenge + ":" + serverNonce;
                    output.println(serverNonce + ":" + proof("server:" + combined));
                    String auth = input.readLine();
                    if (auth == null || !MessageDigest.isEqual(proof("client:" + combined).getBytes(StandardCharsets.UTF_8),
                        auth.getBytes(StandardCharsets.UTF_8))) continue;
                    minimumAngle = 10; maximumAngle = 80; lockedClock = false;
                    output.println("READY");
                    String line;
                    while ((line = input.readLine()) != null) {
                        try {
                            if (line.startsWith("CONFIG:")) {
                                int[] angles = parseAngles(line);
                                if (angles == null) { output.println("ERROR:Invalid angles"); continue; }
                                minimumAngle = angles[0]; maximumAngle = angles[1];
                                output.println("READY"); continue;
                            }
                            switch (line) {
                                case "START":
                                    if (stateId == null) { output.println("ERROR:Presentation unavailable"); break; }
                                    if (keyguardLocked()) { output.println("ERROR:Unlock first"); break; }
                                    String current = command("/system/bin/cmd", "device_state", "print-state").trim();
                                    if ((!openStates.contains(current) && !(active && current.equals(stateId))) || !physicalPanelOpen()) {
                                        output.println("ERROR:Open the inner display first"); break;
                                    }
                                    if (active) { output.println("ACTIVE"); break; }
                                    standby = false; lockedClock = false;
                                    activeStateId = stateId;
                                    active = true; // Release even if a command applies then fails while returning.
                                    command("/system/bin/cmd", "device_state", "state", stateId);
                                    System.out.println("Photo display requested");
                                    output.println("ACTIVE"); break;
                                case "LOCK_CLOCK":
                                    if (!keyguardLocked() || !physicalStandbyAngle()) {
                                        output.println("ERROR:Locked standby required"); break;
                                    }
                                    // The lock-screen window can still use the active panel if the vendor
                                    // refuses a state override while the keyguard is up.
                                    if (tentStateId != null && !active) {
                                        try { requestTent(); }
                                        catch (Exception error) {
                                            reset();
                                            System.err.println("Tent routing unavailable; using the active lock-screen panel");
                                        }
                                    }
                                    standby = active; lockedClock = active;
                                    command("/system/bin/am", "start", "--user", "current", "-n",
                                        "com.jake.duolauncher.core/com.jake.duolauncher.LockedStandbyActivity");
                                    System.out.println("Locked clock launched; tent display=" + active);
                                    output.println("CLOCK"); break;
                                case "COVER_STANDBY":
                                    if (keyguardLocked()) { output.println("ERROR:Unlock first"); break; }
                                    if (active) { output.println(activeStateId.equals(tentStateId) ? "ACTIVE" : "ERROR:Display busy"); break; }
                                    requestTent();
                                    standby = true; lockedClock = false;
                                    System.out.println("Cover-only tent display requested; hinge=" +
                                        (hingeReader == null ? "snapshot" : hingeReader.degrees));
                                    output.println("ACTIVE"); break;
                                case "STOP": reset(); output.println(active ? "ERROR:Release failed" : "READY"); break;
                                case "PING":
                                    if (active && (!command("/system/bin/cmd", "device_state", "print-state").trim().equals(activeStateId) ||
                                            !(standby ? physicalStandbyAngle() : physicalPanelOpen()) ||
                                            (lockedClock && !keyguardLocked()) ||
                                            (standby && !lockedClock && keyguardLocked()))) reset();
                                    output.println(active ? "ACTIVE" : "READY"); break;
                                default: output.println("ERROR:Unsupported operation");
                            }
                        } catch (Exception error) { reset(); output.println("ERROR:System rejected operation"); }
                    }
                } catch (IOException error) { /* closed/timeout: release below */ }
                finally { reset(); }
                // Retry a failed release before accepting another owner.
                while (active) { Thread.sleep(1000); reset(); }
            }
        } finally { reset(); }
    }
}
