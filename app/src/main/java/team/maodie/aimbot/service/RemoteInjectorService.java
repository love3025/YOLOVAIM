package team.maodie.aimbot.service;

import android.annotation.SuppressLint;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.LockSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import team.maodie.aimbot.IRemoteInjector;

public class RemoteInjectorService extends IRemoteInjector.Stub {
    private static final String TAG = "RemoteInjector";
    private static final int BG_ID = 10;
    private static final int INJECT_MODE = 2;  // INJECT_INPUT_EVENT_MODE_ASYNC
    private static final int SIM_ID_OFFSET = 100;    // single simulated pointer ID (matches reference)
    private static final int MAX_SLOTS = 16;
    private static final int VIRTUAL_DEVICE_ID = 0;

    private Method injectMethod;
    private Object inputManager;
    private static int uinputFd = -1;
    private static int dev_abs_max_x = 21199;
    private static int dev_abs_max_y = 29999;
    private static int screen_w = 2120;
    private static int screen_h = 3000;

    public volatile boolean available = false;
    public static final int INPUT_METHOD_UINPUT = 0;
    public static final int INPUT_METHOD_INPUT_MANAGER = 1;
    private int inputMethod = INPUT_METHOD_UINPUT;
    long bgDownTime;
    private int lastTapId = 6;
    private int drawingPointerId = -1;
    private boolean pointerDown = false;
    private static final int TRIGGER_PTR_ID = 20;
    private boolean triggerPointerDown = false;
    private long triggerDownTime = 0;
    private IBinder.DeathRecipient clientDeathRecipient;
    private int realTouchDeviceId = 0;

    // InputManager state
    private boolean imGrabbed = false;
    private boolean pointerDownIM = false;  // single simulated pointer down state (matches reference)
    private long lastSimActiveMs = 0;
    private int displayRotation = 0;  // 0, 1, 2, 3

    // Merge inject loop
    private Thread injectThread;
    private Thread evdevReadThread;
    private volatile boolean injectRunning = false;
    private volatile boolean dirty = false;
    private volatile CountDownLatch mergeFlushLatch;

    // Grace period: block aim injection after trigger tap
    private volatile long triggerBusyUntilMs = 0;

    // Single simulated pointer state (matches reference: only ONE simulated pointer)
    private volatile float simX = 0;
    private volatile float simY = 0;

    // Lock for simulated pointer state
    private final Object simLock = new Object();
    // Lock for physical pointer map
    private final Object physLock = new Object();

    // Merged pointer tracking (matches reference: LinkedHashMap for stable ordering)
    private final LinkedHashMap<Integer, float[]> physicalPointers = new LinkedHashMap<>(16);
    private final LinkedHashMap<Integer, float[]> simulatedPointers = new LinkedHashMap<>(16);
    private final LinkedHashMap<Integer, float[]> currentMerged = new LinkedHashMap<>(16);
    private final LinkedHashMap<Integer, float[]> prevMerged = new LinkedHashMap<>(16);
    private final LinkedHashMap<Integer, float[]> workingMap = new LinkedHashMap<>(16);
    private long mergedDownTime = 0;
    private int injectCount = 0;
    private int mergeLogCount = 0;

    // Stable ID mapping: internal ID -> sequential MotionEvent pointer index
    private final java.util.HashMap<Integer, Integer> stableIdMap = new java.util.HashMap<>(16);

    // Pre-allocated MotionEvent arrays (reused every frame, like reference)
    private final MotionEvent.PointerProperties[] preProps = new MotionEvent.PointerProperties[MAX_SLOTS];
    private final MotionEvent.PointerCoords[] preCoords = new MotionEvent.PointerCoords[MAX_SLOTS];

    public static RemoteInjectorService instance;

    public void setResolution(int sw, int sh, int dw, int dh) {
        if (inputMethod == INPUT_METHOD_INPUT_MANAGER) {
            // Native layer: always portrait dimensions + rotation
            int portraitW = Math.min(sw, sh);
            int portraitH = Math.max(sw, sh);
            int rotation = sw > sh ? 1 : 0;
            screen_w = portraitW;
            screen_h = portraitH;
            displayRotation = rotation;
            nativeInputmgrSetScreenParams(portraitW, portraitH, rotation);
            Log.d(TAG, "setResolution(IM): " + sw + "x" + sh + " portrait=" + portraitW + "x" + portraitH + " rot=" + rotation);
        } else {
            screen_w = sw; screen_h = sh;
            setScreenResolution(sw, sh);
        }
    }

    public void setOrientationConfig(boolean landscapeStart) {
        if (inputMethod == INPUT_METHOD_INPUT_MANAGER) {
            // Will be fully configured by setResolution which follows immediately
            Log.d(TAG, "setOrientationConfig(IM): landscapeStart=" + landscapeStart + " (deferring to setResolution)");
        } else {
            setLandscapeStart(landscapeStart ? 1 : 0);
        }
    }

    // Query actual display rotation from system (matches reference: queryRotation)
    private static final Pattern DISPLAY_HEADER_RE = Pattern.compile("Display:\\s+mDisplayId=(\\d+)");
    private static final Pattern DISPLAY_ROTATION_RE = Pattern.compile("(?m)^\\s*DisplayRotation\\b[\\s\\S]*?^\\s*(?<!Display)mRotation=(?:ROTATION_)?(\\d)\\b");

    private int queryRotation() {
        try {
            java.lang.Process proc = Runtime.getRuntime().exec(new String[]{"dumpsys", "window", "displays"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            proc.waitFor();
            reader.close();
            return parseRotation(sb.toString(), 0);
        } catch (Exception e) {
            Log.w(TAG, "queryRotation failed: " + e.getMessage());
            return 0;
        }
    }

    private int parseRotation(String output, int displayId) {
        StringBuilder section = new StringBuilder();
        boolean inTarget = false;
        for (String line : output.split("\n")) {
            Matcher m = DISPLAY_HEADER_RE.matcher(line);
            if (m.find()) {
                int id = Integer.parseInt(m.group(1));
                if (inTarget && id != displayId) break;
                inTarget = (id == displayId);
            }
            if (inTarget) {
                section.append(line).append('\n');
            }
        }
        if (section.length() == 0) return 0;
        Matcher rm = DISPLAY_ROTATION_RE.matcher(section);
        if (rm.find()) {
            return Integer.parseInt(rm.group(1));
        }
        return 0;
    }

    public void startGeteventListener() {
        if (inputMethod == INPUT_METHOD_INPUT_MANAGER) {
            Log.d(TAG, "startGeteventListener: evdev readers already running");
        } else {
            startGeteventListenerNative();
        }
    }

    public void stopGeteventListener() {
        if (inputMethod == INPUT_METHOD_INPUT_MANAGER) {
            Log.d(TAG, "stopGeteventListener: handled by nativeInputmgrClose");
        } else {
            stopGeteventListenerNative();
        }
    }

    private void ensureArraysInitialized() {
        if (preProps[0] != null) return;
        for (int i = 0; i < MAX_SLOTS; i++) {
            preProps[i] = new MotionEvent.PointerProperties();
            preProps[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            preCoords[i] = new MotionEvent.PointerCoords();
            preCoords[i].size = 1.0f;
        }
    }

    public void onCreate() {
        instance = this;
        ensureArraysInitialized();
        Log.d(TAG, "RemoteInjectorService onCreate, pid=" + Process.myPid());
    }

    private int openUinput() { return openUinputNative(); }

    private void closeUinput() {
        if (uinputFd >= 0) { closeUinputNative(); uinputFd = -1; }
    }

    @Override
    public void setInputMethod(int method) {
        inputMethod = method;
        Log.d(TAG, "setInputMethod: " + (method == INPUT_METHOD_UINPUT ? "Uinput" : "InputManager"));
    }

    @SuppressLint("PrivateApi")
    public boolean init() {
        if (available) return true;
        Log.d(TAG, "init: starting, pid=" + Process.myPid() + " method=" + (inputMethod == INPUT_METHOD_UINPUT ? "Uinput" : "InputManager"));

        if (inputMethod == INPUT_METHOD_UINPUT) {
            try {
                uinputFd = openUinputNative();
                Log.d(TAG, "init: openUinputNative returned fd=" + uinputFd);
                if (uinputFd >= 0) {
                    bgDownTime = SystemClock.uptimeMillis();
                    available = true;
                    Log.d(TAG, "init: RemoteInjectorService ready with uinput, pid=" + Process.myPid());
                    return true;
                }
            } catch (Exception e) {
                Log.e(TAG, "init: uinput exception: " + e.getMessage());
            }
        }

        // InputManager mode
        try {
            killOtherTouchMergers();
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            ensureArraysInitialized();
            Object serviceManager = Class.forName("android.os.ServiceManager");
            Method getService = Class.forName("android.os.ServiceManager").getMethod("getService", String.class);
            IBinder inputBinder = (IBinder) getService.invoke(null, "input");

            Object inputManagerProxy = Class.forName("android.hardware.input.IInputManager$Stub")
                .getMethod("asInterface", IBinder.class)
                .invoke(null, inputBinder);

            Method injectInputEvent = Class.forName("android.hardware.input.IInputManager")
                .getMethod("injectInputEvent", InputEvent.class, int.class);

            inputManager = inputManagerProxy;
            injectMethod = injectInputEvent;
            bgDownTime = SystemClock.uptimeMillis();

            // Init evdev reader for physical touch tracking
            boolean evdevOk = nativeInputmgrInit(screen_w, screen_h);
            if (evdevOk) {
                Log.d(TAG, "init: evdev ok, starting evdev read loop");
                // Start evdev read loop BEFORE any grab — matches reference startTouchMonitor()
                // This ensures physical pointers are tracked before the first simulated touch
                startEvdevReadLoop();
                startInjectLoop();
            } else {
                Log.w(TAG, "init: evdev failed, merging disabled");
                // Start inject loop even without evdev (simulated-only mode, matches reference)
                startInjectLoop();
            }

            available = true;
            Log.d(TAG, "RemoteInjector ready via injectInputEvent, pid=" + Process.myPid());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "init: injectInputEvent failed: " + e.getMessage());
        }

        return false;
    }

    // ===================================================================
    //  InputManager: Single simulated pointer + Merge + Inject
    //  Matches reference TouchMergerUserService exactly:
    //  - injectMode=2, deviceId=0, source=SOURCE_TOUCHSCREEN
    //  - Single simulated pointer (SIM_ID_OFFSET=100)
    //  - injectDown replaces previous pointer (injectUp first if needed)
    //  - Pre-allocated PointerProperties/Coords arrays
    //  - Stable ID mapping for consistent pointer indices
    //  - workingMap pattern for correct action sequencing
    // ===================================================================

    // --- Single simulated pointer control (matches reference injectDown/injectMove/injectUp) ---

    /**
     * Inject simulated pointer down. If a pointer is already active, lifts it first.
     * Matches reference: injectDown(float x, float y)
     */
    private void injectDown(float x, float y) {
        lastSimActiveMs = SystemClock.elapsedRealtime();
        // Matches reference: grab on first simulated touch (threads already started in init())
        if (!imGrabbed && !nativeInputmgrIsGrabbed()) {
            realTouchDeviceId = findRealTouchDeviceId();
            nativeInputmgrGrab();
            imGrabbed = true;
            Log.d(TAG, "injectDown: GRAB applied, realDevId=" + realTouchDeviceId
                + " nativeGrabbed=" + nativeInputmgrIsGrabbed());
            cancelPreGrabHardwarePointers();
        }
        if (!imGrabbed && nativeInputmgrIsGrabbed()) {
            imGrabbed = true;
        }
        // Matches reference: if pointerDown, call injectUp() first (sends UP via merge+inject)
        if (pointerDownIM) {
            injectUp();
        }
        int simId = SIM_ID_OFFSET;
        synchronized (simLock) {
            simulatedPointers.put(simId, new float[]{x, y, 1.0f});
        }
        signalInject();
        pointerDownIM = true;
    }

    /**
     * Move simulated pointer. If not down, calls injectDown.
     * Matches reference: injectMove(float x, float y)
     */
    private void injectMove(float x, float y) {
        lastSimActiveMs = SystemClock.elapsedRealtime();
        // Grace period: skip aim injection right after trigger tap
        if (SystemClock.uptimeMillis() < triggerBusyUntilMs) {
            return;
        }
        // Matches reference: if not pointerDown, call injectDown
        if (!pointerDownIM) {
            injectDown(x, y);
            return;
        }
        int simId = SIM_ID_OFFSET;
        synchronized (simLock) {
            if (simulatedPointers.containsKey(Integer.valueOf(simId))) {
                simulatedPointers.put(Integer.valueOf(simId), new float[]{x, y, 1.0f});
            }
        }
        signalInject();
    }

    /**
     * Lift simulated pointer.
     * Matches reference: injectUp()
     */
    private void injectUp() {
        lastSimActiveMs = SystemClock.elapsedRealtime();
        if (pointerDownIM) {
            int simId = SIM_ID_OFFSET;
            synchronized (simLock) {
                simulatedPointers.remove(Integer.valueOf(simId));
            }
            signalInject();
            pointerDownIM = false;
        }
    }

    private int allocateMotionId(int internalId) {
        Integer existing = stableIdMap.get(internalId);
        if (existing != null) return existing;
        for (int candidate = 0; candidate < MAX_SLOTS; candidate++) {
            if (!stableIdMap.values().contains(candidate)) {
                stableIdMap.put(internalId, candidate);
                return candidate;
            }
        }
        return 0;
    }

    private void releaseMotionId(int internalId) {
        stableIdMap.remove(internalId);
    }

    private int[] orderedPointerIds(LinkedHashMap<Integer, float[]> map) {
        Integer[] ids = map.keySet().toArray(new Integer[0]);
        java.util.Arrays.sort(ids, (a, b) -> {
            int sa = stableIdMap.containsKey(a) ? stableIdMap.get(a) : Integer.MAX_VALUE;
            int sb = stableIdMap.containsKey(b) ? stableIdMap.get(b) : Integer.MAX_VALUE;
            if (sa != sb) return Integer.compare(sa, sb);
            return Integer.compare(a, b);
        });
        int[] result = new int[ids.length];
        for (int i = 0; i < ids.length; i++) result[i] = ids[i];
        return result;
    }

    private void ensureGrabbed() {
        if (!imGrabbed) {
            realTouchDeviceId = findRealTouchDeviceId();
            nativeInputmgrGrab();
            imGrabbed = true;
            Log.d(TAG, "ensureGrabbed: GRAB applied, realDevId=" + realTouchDeviceId);
            cancelPreGrabHardwarePointers();
        }
    }

    private void resetPointerState() {
        synchronized (physLock) {
            physicalPointers.clear();
        }
        synchronized (simLock) {
            simulatedPointers.clear();
            pointerDownIM = false;
        }
        currentMerged.clear();
        prevMerged.clear();
        workingMap.clear();
        stableIdMap.clear();
        dirty = false;
        displayRotation = 0;
        triggerBusyUntilMs = 0;
        mergeFlushLatch = null;
    }

    /**
     * Cancel pre-grab hardware pointers — matches reference exactly.
     * Only cancels on realTouchDeviceId with actual physical pointer positions.
     */
    private void cancelPreGrabHardwarePointers() {
        if (realTouchDeviceId == 0) {
            Log.w(TAG, "cancelPreGrab: realTouchDeviceId=0, SKIP");
            return;
        }
        // Always send CANCEL on real device — even with 1 pointer (dummy)
        // This clears InputDispatcher's pointer tracking for the real device.
        // EVIOCGRAB blocks evdev reads so physicalPointers may be empty,
        // but InputDispatcher still has stale pointers from before the grab.
        int count;
        float posX = 0, posY = 0;
        synchronized (physLock) {
            count = physicalPointers.size();
            if (count > 0) {
                float[] first = physicalPointers.values().iterator().next();
                posX = first[0];
                posY = first[1];
            }
        }
        if (count == 0) {
            count = 1;  // Send dummy CANCEL with 1 pointer at (0,0)
        }
        MotionEvent.PointerProperties[] cProps = new MotionEvent.PointerProperties[count];
        for (int i = 0; i < count; i++) {
            cProps[i] = new MotionEvent.PointerProperties();
            cProps[i].id = i;
            cProps[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
        }
        MotionEvent.PointerCoords[] cCoords = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            cCoords[i] = new MotionEvent.PointerCoords();
            cCoords[i].size = 1.0f;
            cCoords[i].pressure = 1.0f;
        }
        // If we have real positions, use them; otherwise dummy at (0,0)
        if (count == 1) {
            cCoords[0].x = posX;
            cCoords[0].y = posY;
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, count,
            cProps, cCoords, 0, 0, 1f, 1f,
            realTouchDeviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            boolean ok = (boolean) injectMethod.invoke(inputManager, cancel, INJECT_MODE);
            Log.i(TAG, "cancelPreGrab: CANCEL sent ok=" + ok + " count=" + count + " deviceId=" + realTouchDeviceId);
        } catch (Exception e) {
            Log.e(TAG, "cancelPreGrab: CANCEL FAILED: " + e.getMessage());
        } finally {
            cancel.recycle();
        }
    }

    /**
     * Kill other processes that might hold a stale EVIOCGRAB on the touch device.
     * Matches reference: killOtherTouchMergers()
     */
    private void killOtherTouchMergers() {
        int myPid = Process.myPid();
        int killed = 0;
        try {
            File procDir = new File("/proc");
            File[] entries = procDir.listFiles();
            if (entries == null) return;
            for (File entry : entries) {
                String name = entry.getName();
                int pid;
                try {
                    pid = Integer.parseInt(name);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (pid == myPid) continue;
                try {
                    File cmdlineFile = new File(entry, "cmdline");
                    BufferedReader reader = new BufferedReader(new FileReader(cmdlineFile));
                    String cmdline = reader.readLine();
                    reader.close();
                    if (cmdline != null && cmdline.contains("inputmgr")) {
                        try {
                            Process.killProcess(pid);
                            killed++;
                        } catch (Exception e) {
                        }
                    }
                } catch (Exception e) {
                }
            }
            Log.d(TAG, "killOtherTouchMergers: myPid=" + myPid + " killed=" + killed);
        } catch (Exception e) {
            Log.w(TAG, "killOtherTouchMergers: failed (killed=" + killed + "): " + e.getMessage());
        }
    }

    private int findRealTouchDeviceId() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice dev = InputDevice.getDevice(id);
            if (dev != null && dev.supportsSource(InputDevice.SOURCE_TOUCHSCREEN) && id != 0) {
                return id;
            }
        }
        return 0;
    }

    /**
     * Wake the inject loop.
     */
    private void signalInject() {
        dirty = true;
        LockSupport.unpark(injectThread);
    }

    /**
     * Flush: wait until the inject loop has processed the current dirty state.
     * Used to ensure ACTION_UP is injected before a subsequent ACTION_DOWN.
     */
    private void flushAndWait() {
        if (!dirty && mergeFlushLatch == null) return; // nothing to flush
        CountDownLatch latch = new CountDownLatch(1);
        mergeFlushLatch = latch;
        dirty = true;
        LockSupport.unpark(injectThread);
        try {
            latch.await(50, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Start the merge inject loop thread.
     * Matches reference: injectLoop with dirty flag and parkNanos(1ms).
     */
    private void startInjectLoop() {
        if (injectRunning) return;
        injectRunning = true;
        injectThread = new Thread(() -> {
            Log.d(TAG, "Inject loop started");
            while (injectRunning) {
                if (!dirty) {
                    LockSupport.parkNanos(1_000_000L); // 1ms
                }
                if (dirty) {
                    dirty = false;
                    doMergeAndInject();
                    // Signal any thread waiting for this merge to complete
                    CountDownLatch latch = mergeFlushLatch;
                    if (latch != null) {
                        mergeFlushLatch = null;
                        latch.countDown();
                    }
                }
            }
            Log.d(TAG, "Inject loop stopped");
        }, "inputmgr-inject");
        injectThread.setDaemon(true);
        injectThread.start();
    }

    /**
     * Evdev read loop — blocking poll on evdev fd.
     */
    private void startEvdevReadLoop() {
        evdevReadThread = new Thread(() -> {
            int evdevLogCount = 0;
            int pollTimeoutCount = 0;
            Log.d(TAG, "Evdev read loop started");
            while (injectRunning) {
                try {
                    int result = nativeInputmgrPollAndUpdate(100);
                    if (result < 0) {
                        Log.e(TAG, "evdevReadLoop: poll returned error");
                        break;
                    }
                    if (result == 0) {
                        pollTimeoutCount++;
                        if (evdevLogCount < 5 && pollTimeoutCount % 50 == 0) {
                            Log.d(TAG, "evdevRead: timeout x" + pollTimeoutCount
                                + " grabbed=" + imGrabbed + " nativeGrabbed=" + nativeInputmgrIsGrabbed());
                        }
                        continue;
                    }
                    pollTimeoutCount = 0;

                    int[] data = nativeReadPointers();
                    int count = (data != null) ? data.length / 4 : 0;

                    synchronized (physLock) {
                        physicalPointers.clear();
                        for (int i = 0; i < count; i++) {
                            int id = data[i * 4];
                            float x = data[i * 4 + 1];
                            float y = data[i * 4 + 2];
                            boolean down = data[i * 4 + 3] != 0;
                            if (down) {
                                physicalPointers.put(id, new float[]{x, y, 1.0f});
                            }
                        }
                    }
                    if (evdevLogCount < 30) {
                        Log.d(TAG, "evdevRead: " + count + " pointers, physMap=" + physicalPointers.size()
                            + " grabbed=" + imGrabbed);
                        evdevLogCount++;
                    }
                    signalInject();
                } catch (Exception e) {
                    Log.e(TAG, "evdevReadLoop error: " + e.getMessage());
                }
            }
            Log.d(TAG, "Evdev read loop stopped");
        }, "evdev-read");
        evdevReadThread.setDaemon(true);
        evdevReadThread.start();
    }

    private void stopInjectLoop() {
        injectRunning = false;
        if (injectThread != null) {
            injectThread.interrupt();
            try { injectThread.join(1000); } catch (InterruptedException e) {}
            injectThread = null;
        }
        if (evdevReadThread != null) {
            evdevReadThread.interrupt();
            try { evdevReadThread.join(1000); } catch (InterruptedException e) {}
            evdevReadThread = null;
        }
    }

    /**
     * Core merge and inject logic — matches reference TouchMergerUserService exactly.
     * Uses workingMap pattern: maintains state across frames, adds/removes pointers incrementally.
     */
    private void doMergeAndInject() {
        if (injectMethod == null || inputManager == null) return;
        try {
            currentMerged.clear();

            // Snapshot pointers — matches reference lock ordering exactly
            if (imGrabbed) {
                synchronized (physLock) {
                    currentMerged.putAll(physicalPointers);
                }
                synchronized (simLock) {
                    currentMerged.putAll(simulatedPointers);
                }
            } else {
                synchronized (simLock) {
                    currentMerged.putAll(simulatedPointers);
                }
            }

            if (mergeLogCount < 30) {
                Log.d(TAG, "merge: cur=" + currentMerged.size() + " prev=" + prevMerged.size()
                    + " sim=" + simulatedPointers.size() + " phys=" + physicalPointers.size()
                    + " grabbed=" + imGrabbed);
                mergeLogCount++;
            }

            if (currentMerged.isEmpty() && prevMerged.isEmpty()) return;

            long now = SystemClock.uptimeMillis();

            // Detect changes
            List<Integer> newIds = new ArrayList<>();
            List<Integer> removedIds = new ArrayList<>();
            boolean hasMoved = false;

            for (int id : currentMerged.keySet()) {
                if (!prevMerged.containsKey(id)) newIds.add(id);
            }
            for (int id : prevMerged.keySet()) {
                if (!currentMerged.containsKey(id)) removedIds.add(id);
            }
            // Matches reference: exact float equality comparison
            for (Map.Entry<Integer, float[]> entry : currentMerged.entrySet()) {
                float[] prev = prevMerged.get(entry.getKey());
                if (prev != null) {
                    float[] curr = entry.getValue();
                    if (prev[0] != curr[0] || prev[1] != curr[1]) {
                        hasMoved = true;
                        break;
                    }
                }
            }

            // Allocate motion IDs for new pointers
            for (int id : newIds) {
                allocateMotionId(id);
            }

            if (prevMerged.isEmpty() && !currentMerged.isEmpty()) {
                // Case A: All-new press
                mergedDownTime = now;
                injectCount = 0;
                int[] ordered = orderedPointerIds(currentMerged);
                workingMap.clear();
                // First pointer: ACTION_DOWN
                workingMap.put(ordered[0], currentMerged.get(ordered[0]));
                injectMergedTouch(MotionEvent.ACTION_DOWN, workingMap, mergedDownTime, now);
                // Subsequent pointers: ACTION_POINTER_DOWN
                for (int i = 1; i < ordered.length; i++) {
                    workingMap.put(ordered[i], currentMerged.get(ordered[i]));
                    int action = (i << MotionEvent.ACTION_POINTER_INDEX_SHIFT) | MotionEvent.ACTION_POINTER_DOWN;
                    injectMergedTouch(action, workingMap, mergedDownTime, now);
                }
            } else if (!prevMerged.isEmpty() && currentMerged.isEmpty()) {
                // Case B: All pointers up
                workingMap.clear();
                workingMap.putAll(prevMerged);
                int[] ordered = orderedPointerIds(workingMap);
                for (int i = ordered.length - 1; i >= 0; i--) {
                    int id = ordered[i];
                    int idx = pointerIndexInWorkingMap(id);
                    if (workingMap.size() == 1) {
                        injectMergedTouch(MotionEvent.ACTION_UP, workingMap, mergedDownTime, now);
                    } else {
                        int action = (idx << MotionEvent.ACTION_POINTER_INDEX_SHIFT) | MotionEvent.ACTION_POINTER_UP;
                        injectMergedTouch(action, workingMap, mergedDownTime, now);
                    }
                    workingMap.remove(id);
                    releaseMotionId(id);
                }
            } else {
                // Case C: Mixed changes
                workingMap.clear();
                workingMap.putAll(prevMerged);

                // Step 1: Process removed pointers (UP)
                for (int id : removedIds) {
                    int idx = pointerIndexInWorkingMap(id);
                    if (idx < 0) continue;
                    if (workingMap.size() == 1) {
                        injectMergedTouch(MotionEvent.ACTION_UP, workingMap, mergedDownTime, now);
                    } else {
                        int action = (idx << MotionEvent.ACTION_POINTER_INDEX_SHIFT) | MotionEvent.ACTION_POINTER_UP;
                        injectMergedTouch(action, workingMap, mergedDownTime, now);
                    }
                    workingMap.remove(id);
                    releaseMotionId(id);
                }

                // Step 2: Update existing pointer coordinates
                for (int id : currentMerged.keySet()) {
                    if (prevMerged.containsKey(id)) {
                        workingMap.put(id, currentMerged.get(id));
                    }
                }

                // Step 3: Process new pointers (DOWN)
                for (int id : newIds) {
                    workingMap.put(id, currentMerged.get(id));
                    int idx = pointerIndexInWorkingMap(id);
                    if (workingMap.size() == 1) {
                        mergedDownTime = now;
                        injectCount = 0;
                        injectMergedTouch(MotionEvent.ACTION_DOWN, workingMap, mergedDownTime, now);
                    } else {
                        int action = (idx << MotionEvent.ACTION_POINTER_INDEX_SHIFT) | MotionEvent.ACTION_POINTER_DOWN;
                        injectMergedTouch(action, workingMap, mergedDownTime, now);
                    }
                }

                // Step 4: If any pointer moved, send ACTION_MOVE
                if (hasMoved && !workingMap.isEmpty()) {
                    injectMergedTouch(MotionEvent.ACTION_MOVE, workingMap, mergedDownTime, now);
                }
            }

            // Save current state
            prevMerged.clear();
            prevMerged.putAll(currentMerged);
        } catch (Exception e) {
            Log.e(TAG, "doMergeAndInject: " + e.getMessage(), e);
        }
    }

    /**
     * Find the pointer index of an internal ID within the workingMap.
     */
    private int pointerIndexInWorkingMap(int internalId) {
        int[] ordered = orderedPointerIds(workingMap);
        for (int i = 0; i < ordered.length; i++) {
            if (ordered[i] == internalId) return i;
        }
        return -1;
    }

    /**
     * Inject a merged multi-pointer touch event.
     * Matches reference: deviceId=0, source=SOURCE_TOUCHSCREEN, mode=2.
     *
     * Coordinates are in logical display space (landscape for landscape games).
     * InputManager.injectInputEvent applies system display rotation, so we must
     * inverse-rotate to physical display coordinates before injection.
     */
    private void injectMergedTouch(int action, LinkedHashMap<Integer, float[]> pointers, long downTime, long eventTime) {
        if (injectMethod == null || inputManager == null || pointers.isEmpty()) return;
        try {
            int count = pointers.size();
            int[] ordered = orderedPointerIds(pointers);

            for (int i = 0; i < count; i++) {
                int id = ordered[i];
                float[] ptr = pointers.get(id);
                preProps[i].id = stableIdMap.get(id);
                preCoords[i].x = ptr[0];
                preCoords[i].y = ptr[1];
                preCoords[i].pressure = ptr[2];
                preCoords[i].size = 1.0f;
            }

            MotionEvent ev = MotionEvent.obtain(downTime, eventTime, action, count,
                preProps, preCoords, 0, 0, 1f, 1f,
                VIRTUAL_DEVICE_ID, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            boolean ok = (boolean) injectMethod.invoke(inputManager, ev, INJECT_MODE);
            ev.recycle();
            injectCount++;
            if (injectCount <= 30) {
                Log.d(TAG, "inject " + actionToString(action) + " count=" + count + " ok=" + ok);
            }
        } catch (Exception e) {
            Log.e(TAG, "injectMergedTouch error: " + e.getMessage(), e);
        }
    }

    /**
     * Inverse of rotateToDisplay: logical display coords -> physical display coords.
     * InputManager.injectInputEvent applies system rotation, so we must pre-invert.
     * screen_w/screen_h are the game's current dimensions (landscape: 3000x2120).
     */
    private float[] inverseRotateForInject(float lx, float ly) {
        switch (displayRotation) {
            case 1:
                // Game sends landscape coords (0..3000, 0..2120)
                // System rotation=1 rotates portrait->landscape
                // So we inverse: landscape->portrait
                return new float[]{screen_w - ly, lx};
            case 2:
                return new float[]{screen_w - lx, screen_h - ly};
            case 3:
                return new float[]{ly, screen_h - lx};
            default:
                return new float[]{lx, ly};
        }
    }

    private static String actionToString(int action) {
        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN: return "DOWN";
            case MotionEvent.ACTION_UP: return "UP";
            case MotionEvent.ACTION_MOVE: return "MOVE";
            case MotionEvent.ACTION_POINTER_DOWN: return "PTR_DOWN[" + (action >> MotionEvent.ACTION_POINTER_INDEX_SHIFT) + "]";
            case MotionEvent.ACTION_POINTER_UP: return "PTR_UP[" + (action >> MotionEvent.ACTION_POINTER_INDEX_SHIFT) + "]";
            case MotionEvent.ACTION_CANCEL: return "CANCEL";
            default: return "ACTION_" + action;
        }
    }

    // ===================================================================
    //  Touch operations — all use single simulated pointer (matches reference)
    // ===================================================================

    public void keepAlive() {
        if (!available) return;
        if (uinputFd >= 0) {
            uinputSendMove(uinputFd, 5, 5, BG_ID);
        }
    }

    public void tap(int x, int y) throws RemoteException {
        if (!available) return;
        try {
            if (uinputFd >= 0) {
                int tapId = 7 + ((int)(Math.random() * 3));
                if (tapId == lastTapId) tapId = (tapId + 1) % 10 + 7;
                lastTapId = tapId;
                uinputSendDown(uinputFd, x, y, tapId);
                try { Thread.sleep(8); } catch (InterruptedException e) {}
                uinputSendUp(uinputFd, tapId);
            } else if (inputMethod == INPUT_METHOD_INPUT_MANAGER) {
                injectDown(x, y);
                try { Thread.sleep(8); } catch (InterruptedException e) {}
                injectUp();
            }
        } catch (Exception e) {
            throw new RemoteException(e.getMessage());
        }
    }

    public void swipe(int x1, int y1, int x2, int y2, int durationMs) throws RemoteException {
        if (!available) return;
        try {
            if (uinputFd >= 0) {
                int tapId = 7 + ((int)(Math.random() * 3));
                if (tapId == lastTapId) tapId = (tapId + 1) % 10 + 7;
                lastTapId = tapId;
                if (!pointerDown && x1 == x2 && y1 == y2) {
                    drawingPointerId = tapId;
                    uinputSendDown(uinputFd, x1, y1, drawingPointerId);
                    pointerDown = true;
                } else if (pointerDown) {
                    uinputSendMove(uinputFd, x2, y2, drawingPointerId);
                } else {
                    uinputSendDown(uinputFd, x1, y1, tapId);
                    try { Thread.sleep(durationMs); } catch (InterruptedException e) {}
                    uinputSendMove(uinputFd, x2, y2, tapId);
                    uinputSendUp(uinputFd, tapId);
                }
            } else if (inputMethod == INPUT_METHOD_INPUT_MANAGER) {
                // Matches reference: injectDown replaces previous pointer if any
                injectDown(x1, y1);
                if (x1 != x2 || y1 != y2) {
                    try { Thread.sleep(durationMs); } catch (InterruptedException e) {}
                    injectMove(x2, y2);
                    injectUp();
                }
                // If x1==x2 && y1==y2: keep pointer down (aim drag pattern)
            }
        } catch (Exception e) {
            throw new RemoteException(e.getMessage());
        }
    }

    public void moveTo(int x, int y) throws RemoteException {
        if (!available) return;
        try {
            if (uinputFd >= 0) {
                if (!pointerDown) return;
                uinputSendMove(uinputFd, x, y, drawingPointerId);
            } else if (inputMethod == INPUT_METHOD_INPUT_MANAGER) {
                injectMove(x, y);
            }
        } catch (Exception e) {
            throw new RemoteException(e.getMessage());
        }
    }

    public void lift() throws RemoteException {
        if (!available) return;
        try {
            if (uinputFd >= 0) {
                if (!pointerDown) return;
                uinputSendUp(uinputFd, drawingPointerId);
                pointerDown = false;
                drawingPointerId = -1;
            } else if (inputMethod == INPUT_METHOD_INPUT_MANAGER) {
                injectUp();
            }
        } catch (Exception e) {
            throw new RemoteException(e.getMessage());
        }
    }

    public void aimAt(int targetX, int targetY, int centerX, int centerY, float speed, int screenW, int screenH) throws RemoteException {
        if (!available) return;
        double dx = targetX - centerX;
        double dy = targetY - centerY;
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist < 1) return;
        int duration = Math.min(50, (int) (dist / speed));
        swipe(centerX, centerY, centerX + (int)dx, centerY + (int)dy, duration);
    }

    @Override
    public void triggerDown(int x, int y) throws RemoteException {
        if (!available) return;
        if (uinputFd >= 0) {
            uinputTriggerDown(x, y);
        } else if (inputMethod == INPUT_METHOD_INPUT_MANAGER) {
            // Matches reference: lift aim finger first, flush to ensure UP is injected,
            // then place fire pointer
            if (pointerDownIM) {
                injectUp();
                flushAndWait();
            }
            int simId = SIM_ID_OFFSET;
            synchronized (simLock) {
                simulatedPointers.put(simId, new float[]{x, y, 1.0f});
            }
            signalInject();
            pointerDownIM = true;
            Log.d(TAG, "triggerDown(IM) at (" + x + "," + y + ")");
        }
    }

    @Override
    public void triggerUp() throws RemoteException {
        if (!available) return;
        if (uinputFd >= 0) {
            uinputTriggerUp();
        } else if (inputMethod == INPUT_METHOD_INPUT_MANAGER) {
            injectUp();
            Log.d(TAG, "triggerUp(IM)");
        }
    }

    @Override
    public void triggerTap(int x, int y, int durationMs) throws RemoteException {
        if (!available) return;
        Log.d(TAG, "triggerTap at (" + x + "," + y + ") dur=" + durationMs);
        triggerDown(x, y);
        try { Thread.sleep(durationMs); } catch (InterruptedException e) {}
        triggerUp();
        // Grace period: block aim injection briefly after trigger completes
        // Prevents aim finger from immediately re-engaging at old position
        triggerBusyUntilMs = SystemClock.uptimeMillis() + 80;
    }

    // ===================================================================
    //  Zone methods
    // ===================================================================

    @Override
    public void setTriggerZone(int left, int top, int right, int bottom) throws RemoteException {
        if (inputMethod == INPUT_METHOD_INPUT_MANAGER) {
            nativeInputmgrSetTriggerZone(left, top, right, bottom);
        } else {
            nativeSetTriggerZone(left, top, right, bottom);
        }
    }

    @Override
    public boolean isFingerInTriggerZone() throws RemoteException {
        return inputMethod == INPUT_METHOD_INPUT_MANAGER
            ? nativeInputmgrIsFingerInTriggerZone()
            : nativeIsFingerInTriggerZone();
    }

    @Override
    public void setFireZone(int left, int top, int right, int bottom) throws RemoteException {
        if (inputMethod == INPUT_METHOD_INPUT_MANAGER) {
            nativeInputmgrSetFireZone(left, top, right, bottom);
        } else {
            nativeSetFireZone(left, top, right, bottom);
        }
    }

    @Override
    public boolean isFingerInFireZone() throws RemoteException {
        return inputMethod == INPUT_METHOD_INPUT_MANAGER
            ? nativeInputmgrIsFingerInFireZone()
            : nativeIsFingerInFireZone();
    }

    @Override
    public void setJoystickZone(int left, int top, int right, int bottom) throws RemoteException {
        if (inputMethod == INPUT_METHOD_INPUT_MANAGER) {
            nativeInputmgrSetJoystickZone(left, top, right, bottom);
        } else {
            nativeSetJoystickZone(left, top, right, bottom);
        }
    }

    @Override
    public boolean isFingerInJoystickZone() throws RemoteException {
        return inputMethod == INPUT_METHOD_INPUT_MANAGER
            ? nativeInputmgrIsFingerInJoystickZone()
            : nativeIsFingerInJoystickZone();
    }

    @Override
    public boolean liftJoystickFinger() throws RemoteException {
        return inputMethod == INPUT_METHOD_INPUT_MANAGER
            ? nativeInputmgrLiftJoystickFinger()
            : nativeLiftJoystickFinger();
    }

    // ===================================================================
    //  Lifecycle
    // ===================================================================

    public void linkToDeath(IBinder token) {
        if (token == null) return;
        try {
            clientDeathRecipient = () -> {
                Log.w(TAG, "Client process died, cleaning up...");
                destroy();
            };
            token.linkToDeath(clientDeathRecipient, 0);
            Log.d(TAG, "linkToDeath registered");
        } catch (RemoteException e) {
            Log.e(TAG, "linkToDeath failed: " + e.getMessage());
        }
    }

    public void destroy() {
        Log.d(TAG, "destroy called");
        available = false;
        clientDeathRecipient = null;

        if (inputMethod == INPUT_METHOD_INPUT_MANAGER) {
            drainInjectedPointers();
            stopInjectLoop();
            if (imGrabbed) {
                nativeInputmgrUngrab();
                imGrabbed = false;
            }
            nativeInputmgrClose();
        } else {
            closeUinput();
        }

        inputManager = null;
        injectMethod = null;
        resetPointerState();
    }

    /**
     * Send UP events for all held simulated pointers, then clear state.
     * Matches reference: drainInjectedPointers(long now, String reason)
     */
    private void drainInjectedPointers() {
        if (injectMethod == null || inputManager == null) return;
        try {
            // Clear simulated state
            synchronized (simLock) {
                simulatedPointers.clear();
                pointerDownIM = false;
            }
            // Force final merge+inject to send UP events
            doMergeAndInject();
            // Safety: drain any remaining
            if (!prevMerged.isEmpty()) {
                long now = SystemClock.uptimeMillis();
                workingMap.clear();
                workingMap.putAll(prevMerged);
                int[] ordered = orderedPointerIds(workingMap);
                for (int i = ordered.length - 1; i >= 0; i--) {
                    int id = ordered[i];
                    int idx = pointerIndexInWorkingMap(id);
                    if (workingMap.size() == 1) {
                        injectMergedTouch(MotionEvent.ACTION_UP, workingMap, mergedDownTime, now);
                    } else {
                        int action = (idx << MotionEvent.ACTION_POINTER_INDEX_SHIFT) | MotionEvent.ACTION_POINTER_UP;
                        injectMergedTouch(action, workingMap, mergedDownTime, now);
                    }
                    workingMap.remove(id);
                    releaseMotionId(id);
                }
                prevMerged.clear();
            }
            Log.d(TAG, "drainInjectedPointers: done");
        } catch (Exception e) {
            Log.e(TAG, "drainInjectedPointers error: " + e.getMessage());
        }
    }

    public void blockPhysicalTouch() { Log.d(TAG, "blockPhysicalTouch: handled by native reader"); }
    public void unblockPhysicalTouch() {}

    @Override
    public boolean isAvailable() { return available; }

    // ===================================================================
    //  Native methods — uinput
    // ===================================================================

    private static native int openUinputNative();
    private static native void closeUinputNative();
    private static native void uinputSendDown(int fd, int x, int y, int pointerId);
    private static native void uinputSendMove(int fd, int x, int y, int pointerId);
    private static native void uinputSendUp(int fd, int pointerId);
    private static native void uinputTriggerDown(int x, int y);
    private static native void uinputTriggerUp();
    private static native void nativeSetTriggerZone(int left, int top, int right, int bottom);
    private static native boolean nativeIsFingerInTriggerZone();
    private static native void nativeSetFireZone(int left, int top, int right, int bottom);
    private static native boolean nativeIsFingerInFireZone();
    private static native void nativeSetJoystickZone(int left, int top, int right, int bottom);
    private static native boolean nativeIsFingerInJoystickZone();
    private static native boolean nativeLiftJoystickFinger();
    private static native void setDeviceResolution(int devW, int devH);
    private static native void setScreenResolution(int screenW, int screenH);
    private static native void setLandscapeStart(int isLandscape);
    private static native void startGeteventListenerNative();
    private static native void stopGeteventListenerNative();

    // ===================================================================
    //  Native methods — InputManager evdev
    // ===================================================================

    private static native boolean nativeInputmgrInit(int screenW, int screenH);
    private static native void nativeInputmgrClose();
    private static native void nativeInputmgrGrab();
    private static native void nativeInputmgrUngrab();
    private static native boolean nativeInputmgrIsGrabbed();
    private static native int[] nativeReadPointers();
    private static native int nativeInputmgrPollAndUpdate(int timeoutMs);
    private static native int nativeInputmgrGetDeviceId();
    private static native int nativeInputmgrGetMaxX();
    private static native int nativeInputmgrGetMaxY();
    private static native boolean nativeInputmgrHasSlotSupport();
    private static native void nativeInputmgrSetScreenParams(int w, int h, int rotation);
    private static native void nativeInputmgrSetTriggerZone(int l, int t, int r, int b);
    private static native void nativeInputmgrSetFireZone(int l, int t, int r, int b);
    private static native void nativeInputmgrSetJoystickZone(int l, int t, int r, int b);
    private static native boolean nativeInputmgrIsFingerInTriggerZone();
    private static native boolean nativeInputmgrIsFingerInFireZone();
    private static native boolean nativeInputmgrIsFingerInJoystickZone();
    private static native boolean nativeInputmgrLiftJoystickFinger();

    static {
        try {
            System.loadLibrary("uinput_inject");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load uinput_inject library: " + e.getMessage());
        }
        try {
            System.loadLibrary("inputmgr_inject");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "inputmgr_inject not available: " + e.getMessage());
        }
    }
}
