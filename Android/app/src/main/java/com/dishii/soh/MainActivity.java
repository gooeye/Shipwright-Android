package com.dishii.soh;

import org.libsdl.app.SDLActivity;

import android.Manifest;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Main SDL activity: runs first-time setup (with a progress screen), then hosts the game
// and the on-screen touch controls.
public class MainActivity extends SDLActivity {

    private static final String TAG = "SohMain";

    // Mirrors SetupPhase in soh/soh/Extractor/Extract.h
    private static final int PHASE_BOOT = 0;
    private static final int PHASE_NEED_ROM = 1;
    private static final int PHASE_READING_ROM = 3;
    private static final int PHASE_EXTRACTING = 4;
    private static final int PHASE_PACKING = 5;
    private static final int PHASE_SAVING = 6;
    private static final int PHASE_EXTRACT_DONE = 7;
    private static final int PHASE_READY = 100;

    private static final int STORAGE_PERMISSION_REQUEST_CODE = 2296;
    private static final int FILE_PICKER_REQUEST_CODE = 0;

    private static final CountDownLatch setupLatch = new CountDownLatch(1);

    SharedPreferences preferences;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SetupOverlay setupOverlay;
    private SetupFiles setupFiles;
    private boolean setupStarted;
    private boolean setupFinished;
    private boolean pollingNative;
    private boolean importingRom;
    private File importedRomFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        preferences = getSharedPreferences("com.dishii.soh.prefs", Context.MODE_PRIVATE);

        super.onCreate(savedInstanceState);

        ViewGroup content = (ViewGroup) getContentView();
        if (content == null) {
            return; // SDL failed to start (e.g. missing libraries); it shows its own error.
        }

        setupFiles = new SetupFiles(this, sohRoot());
        setupControllerOverlay();
        attachController();
        setupOverlay = new SetupOverlay(this, content);

        if (hasStoragePermission()) {
            beginSetup();
        } else {
            setupOverlay.show();
            setupOverlay.showPermissionRequest(this::requestStoragePermission);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Returning from the "All files access" settings screen.
        if (!setupStarted && setupOverlay != null && hasStoragePermission()) {
            beginSetup();
        }
    }

    public static void waitForSetupFromNative() {
        try {
            setupLatch.await(); // Block the game thread until the files it needs exist
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static File sohRoot() {
        return new File(Environment.getExternalStorageDirectory(), "SOH");
    }

    // ---------------------------------------------------------------------------------------
    // First-time setup
    // ---------------------------------------------------------------------------------------

    private void beginSetup() {
        if (setupStarted) {
            return;
        }
        setupStarted = true;
        doVersionCheck();

        if (setupFiles.isComplete()) {
            finishFileSetup();
            return;
        }

        setupOverlay.show();
        setupOverlay.setStep(SetupOverlay.STEP_PREPARE);
        setupOverlay.setIndeterminate(R.string.setup_preparing, R.string.setup_preparing_hint);
        runFileSetup();
    }

    private void runFileSetup() {
        worker.execute(() -> {
            try {
                migrateOldData();
                setupFiles.run((done, total) -> handler.post(() -> setupOverlay.setProgress(
                        R.string.setup_preparing, R.string.setup_preparing_hint, done, total, "files", 1)));
                handler.post(this::finishFileSetup);
            } catch (Exception e) {
                Log.e(TAG, "File setup failed", e);
                String message = e.getMessage() != null ? e.getMessage() : e.toString();
                handler.post(() -> setupOverlay.showError(message, () -> {
                    setupOverlay.setIndeterminate(R.string.setup_preparing, R.string.setup_preparing_hint);
                    runFileSetup();
                }));
            }
        });
    }

    private void finishFileSetup() {
        if (setupFinished) {
            return;
        }
        setupFinished = true;
        if (setupOverlay.isVisible()) {
            setupOverlay.setStep(SetupOverlay.STEP_ROM);
            setupOverlay.setIndeterminate(R.string.setup_starting, 0);
        }
        setupLatch.countDown();
        startNativePolling();
        // The old version unpacked ~56 MB of XMLs into the shared SOH folder; they now live in
        // app storage, so clear the stale copy. Own low-priority thread so it never delays a ROM import.
        File legacyAssets = new File(sohRoot(), "assets");
        if (legacyAssets.exists()) {
            Thread cleanup = new Thread(() -> SetupFiles.deleteRecursive(legacyAssets), "legacy-assets-cleanup");
            cleanup.setPriority(Thread.MIN_PRIORITY);
            cleanup.start();
        }
    }

    private void doVersionCheck() {
        int currentVersion = BuildConfig.VERSION_CODE;
        int storedVersion = preferences.getInt("appVersion", 1);

        if (currentVersion > storedVersion) {
            deleteOutdatedAssets();
            setupFiles.invalidate();
            preferences.edit().putInt("appVersion", currentVersion).apply();
        }
    }

    private void deleteOutdatedAssets() {
        File root = sohRoot();
        deleteIfExists(new File(root, "soh.otr"));
        deleteIfExists(new File(root, "oot.otr"));
        deleteIfExists(new File(root, "oot-mq.otr"));
        SetupFiles.deleteRecursive(new File(root, "assets"));
    }

    private void deleteIfExists(File file) {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Failed to delete " + file.getAbsolutePath());
        }
    }

    // Older builds kept data in Android/data/<package>/files; move it to the SOH folder.
    private void migrateOldData() {
        File sourceOldRoot = getExternalFilesDir(null);
        if (sourceOldRoot == null || !new File(sourceOldRoot, "Save").isDirectory()) {
            return;
        }
        File target = sohRoot();
        //noinspection ResultOfMethodCallIgnored
        target.mkdirs();
        File[] files = sourceOldRoot.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = file.getName();
            if (name.equals("assets") || name.endsWith(".otr")) {
                continue;
            }
            try {
                File dest = new File(target, name);
                if (file.isDirectory()) {
                    AssetCopyUtil.copyDirectory(file, dest);
                } else {
                    AssetCopyUtil.copyFile(file, dest);
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to migrate " + name, e);
            }
        }
        handler.post(() -> Toast.makeText(this, "Save data migrated", Toast.LENGTH_SHORT).show());
    }

    // ---------------------------------------------------------------------------------------
    // Native setup progress (ROM selection and game data extraction)
    // ---------------------------------------------------------------------------------------

    private static native int[] nativeGetSetupState();

    private final Runnable pollNative = new Runnable() {
        @Override
        public void run() {
            if (!pollingNative) {
                return;
            }
            int[] state;
            try {
                state = nativeGetSetupState();
            } catch (UnsatisfiedLinkError e) {
                state = null; // native library not ready yet
            }
            if (state != null && renderNativeState(state[0], state[1], state[2])) {
                pollingNative = false;
                return;
            }
            handler.postDelayed(this, 120);
        }
    };

    private void startNativePolling() {
        if (pollingNative) {
            return;
        }
        pollingNative = true;
        handler.post(pollNative);
    }

    /** Returns true once the game is ready and polling can stop. */
    private boolean renderNativeState(int phase, int done, int total) {
        if (importingRom) {
            return false; // the ROM import progress owns the screen
        }
        switch (phase) {
            case PHASE_BOOT:
                return false;
            case PHASE_NEED_ROM:
                setupOverlay.show();
                setupOverlay.setStep(SetupOverlay.STEP_ROM);
                setupOverlay.setIndeterminate(R.string.setup_need_rom, R.string.setup_need_rom_hint);
                return false;
            case PHASE_READING_ROM:
                setupOverlay.show();
                setupOverlay.setStep(SetupOverlay.STEP_BUILD);
                setupOverlay.setIndeterminate(R.string.setup_reading_rom, R.string.setup_extracting_hint);
                return false;
            case PHASE_EXTRACTING:
                setupOverlay.show();
                setupOverlay.setStep(SetupOverlay.STEP_BUILD);
                setupOverlay.setProgress(R.string.setup_extracting, R.string.setup_extracting_hint,
                        done, total, "files", 3);
                return false;
            case PHASE_PACKING:
                setupOverlay.setStep(SetupOverlay.STEP_BUILD);
                setupOverlay.setIndeterminate(R.string.setup_packing, R.string.setup_extracting_hint);
                return false;
            case PHASE_SAVING:
                setupOverlay.setStep(SetupOverlay.STEP_BUILD);
                setupOverlay.setIndeterminate(R.string.setup_saving, R.string.setup_extracting_hint);
                return false;
            case PHASE_EXTRACT_DONE:
                setupOverlay.setAllDone();
                setupOverlay.setDone(R.string.setup_extract_done);
                deleteImportedRom();
                return false;
            case PHASE_READY:
                if (setupOverlay.isVisible()) {
                    setupOverlay.setAllDone();
                    setupOverlay.setDone(R.string.setup_ready);
                    setupOverlay.dismiss(700, null);
                }
                deleteImportedRom();
                return true;
            default:
                return false;
        }
    }

    // ---------------------------------------------------------------------------------------
    // ROM picker
    // ---------------------------------------------------------------------------------------

    private native void nativeHandleSelectedFile(String filePath);

    // Called from the native game thread.
    public void openFilePicker() {
        runOnUiThread(() -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            try {
                startActivityForResult(intent, FILE_PICKER_REQUEST_CODE);
            } catch (Exception e) {
                Log.e(TAG, "No file picker available", e);
                Toast.makeText(this, "No file picker app found on this device.", Toast.LENGTH_LONG).show();
                nativeHandleSelectedFile(""); // let the game thread continue instead of waiting forever
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_PICKER_REQUEST_CODE) {
            Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
            if (uri == null) {
                // Cancelled. Native asks whether to pick again rather than hanging.
                nativeHandleSelectedFile("");
                return;
            }
            importRom(uri);
        } else if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            if (hasStoragePermission()) {
                beginSetup();
            }
        }
    }

    private void importRom(Uri uri) {
        importingRom = true;
        setupOverlay.show();
        setupOverlay.setStep(SetupOverlay.STEP_ROM);
        setupOverlay.setIndeterminate(R.string.setup_importing_rom, 0);

        worker.execute(() -> {
            File dir = new File(getCacheDir(), "rom-import");
            SetupFiles.deleteRecursive(dir);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File dest = new File(dir, "OOT" + romExtension(uri));
            long total = querySize(uri);
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(dest)) {
                if (in == null) {
                    throw new IOException("Couldn't open the selected file");
                }
                byte[] buffer = new byte[256 * 1024];
                long done = 0;
                long lastReport = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    done += read;
                    long now = System.nanoTime();
                    if (now - lastReport > 50_000_000L) {
                        lastReport = now;
                        final long d = done;
                        handler.post(() -> setupOverlay.setProgress(
                                R.string.setup_importing_rom, 0, d, total, "MB", 2));
                    }
                }
                importedRomFile = dest;
                handler.post(() -> {
                    importingRom = false;
                    nativeHandleSelectedFile(dest.getAbsolutePath());
                });
            } catch (Exception e) {
                Log.e(TAG, "ROM import failed", e);
                SetupFiles.deleteRecursive(dir);
                handler.post(() -> {
                    importingRom = false;
                    Toast.makeText(this, R.string.setup_rom_import_failed, Toast.LENGTH_LONG).show();
                    nativeHandleSelectedFile("");
                });
            }
        });
    }

    private void deleteImportedRom() {
        if (importedRomFile != null) {
            File dir = importedRomFile.getParentFile();
            importedRomFile = null;
            worker.execute(() -> SetupFiles.deleteRecursive(dir));
        }
    }

    private String romExtension(Uri uri) {
        String name = queryDisplayName(uri);
        if (name != null) {
            String lower = name.toLowerCase();
            if (lower.endsWith(".n64") || lower.endsWith(".v64") || lower.endsWith(".z64")) {
                return lower.substring(lower.length() - 4);
            }
        }
        return ".z64";
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getString(0);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private long querySize(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] { OpenableColumns.SIZE }, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getLong(0);
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    // ---------------------------------------------------------------------------------------
    // Storage permission
    // ---------------------------------------------------------------------------------------

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, STORAGE_PERMISSION_REQUEST_CODE);
            } catch (Exception e) {
                // Some ROMs lack the per-app screen; fall back to the global list.
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                        STORAGE_PERMISSION_REQUEST_CODE);
            }
        } else {
            ActivityCompat.requestPermissions(this, new String[] {
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, STORAGE_PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE && hasStoragePermission()) {
            beginSetup();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Dialogs: restyle SDL's message boxes (ROM prompts, errors) to match the setup screen
    // ---------------------------------------------------------------------------------------

    @Override
    protected void messageboxCreateAndShow(Bundle args) {
        final Dialog dialog = new Dialog(this, R.style.SohDialog);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        View view = LayoutInflater.from(this).inflate(R.layout.soh_dialog, null);
        ((TextView) view.findViewById(R.id.dialog_title)).setText(args.getString("title"));
        ((TextView) view.findViewById(R.id.dialog_message)).setText(args.getString("message"));

        int[] buttonFlags = args.getIntArray("buttonFlags");
        int[] buttonIds = args.getIntArray("buttonIds");
        String[] buttonTexts = args.getStringArray("buttonTexts");
        LinearLayout buttons = view.findViewById(R.id.dialog_buttons);
        final SparseArray<Button> keyMapping = new SparseArray<>();

        for (int i = 0; i < buttonTexts.length; i++) {
            final int id = buttonIds[i];
            boolean isDefault = (buttonFlags[i] & 0x00000001) != 0; // SDL_MESSAGEBOX_BUTTON_RETURNKEY_DEFAULT
            boolean isEscape = (buttonFlags[i] & 0x00000002) != 0; // SDL_MESSAGEBOX_BUTTON_ESCAPEKEY_DEFAULT
            Button button = (Button) LayoutInflater.from(this).inflate(
                    isDefault || buttonTexts.length == 1 ? R.layout.soh_dialog_button_primary
                                                          : R.layout.soh_dialog_button_text,
                    buttons, false);
            button.setText(buttonTexts[i]);
            button.setOnClickListener(v -> {
                messageboxSelection[0] = id;
                dialog.dismiss();
            });
            if (isDefault) {
                keyMapping.put(KeyEvent.KEYCODE_ENTER, button);
                keyMapping.put(KeyEvent.KEYCODE_BUTTON_A, button);
            }
            if (isEscape) {
                keyMapping.put(KeyEvent.KEYCODE_ESCAPE, button);
                keyMapping.put(KeyEvent.KEYCODE_BUTTON_B, button);
            }
            // Primary action last (rightmost), as on modern Android dialogs.
            if (isDefault) {
                buttons.addView(button);
            } else {
                buttons.addView(button, 0);
            }
        }

        dialog.setContentView(view);
        dialog.setOnDismissListener(d -> {
            synchronized (messageboxSelection) {
                messageboxSelection.notify();
            }
        });
        dialog.setOnKeyListener((d, keyCode, event) -> {
            Button button = keyMapping.get(keyCode);
            if (button != null) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    button.performClick();
                }
                return true;
            }
            return false;
        });
        Window window = dialog.getWindow();
        if (window != null) {
            // Don't pull the game out of immersive fullscreen while the dialog is up.
            window.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
        dialog.show();
        if (window != null) {
            window.getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility());
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Touch controls
    // ---------------------------------------------------------------------------------------

    public native void attachController();
    public native void detachController();
    public native void setButton(int button, boolean value);
    public native void setCameraState(int axis, float value);
    public native void setAxis(int axis, short value);

    private View overlayView;
    private boolean touchAreaEnabled = true;

    void DisableTouchArea() {
        touchAreaEnabled = false;
    }

    void EnableTouchArea() {
        touchAreaEnabled = true;
    }

    private void setupControllerOverlay() {
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        overlayView = inflater.inflate(R.layout.touchcontrol_overlay, null);
        overlayView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        ViewGroup content = (ViewGroup) getContentView();
        content.addView(overlayView);
        content.setKeepScreenOn(true);

        final ViewGroup buttonGroup = overlayView.findViewById(R.id.button_group);

        bindButton(R.id.buttonA, ControllerButtons.BUTTON_A);
        bindButton(R.id.buttonB, ControllerButtons.BUTTON_B);
        bindButton(R.id.buttonX, ControllerButtons.BUTTON_X);
        bindButton(R.id.buttonY, ControllerButtons.BUTTON_Y);

        bindCButton(R.id.buttonDpadUp, ControllerButtons.AXIS_RY, 1);
        bindCButton(R.id.buttonDpadDown, ControllerButtons.AXIS_RY, -1);
        bindCButton(R.id.buttonDpadLeft, ControllerButtons.AXIS_RX, 1);
        bindCButton(R.id.buttonDpadRight, ControllerButtons.AXIS_RX, -1);

        bindButton(R.id.buttonLB, ControllerButtons.BUTTON_LB);
        bindButton(R.id.buttonRB, ControllerButtons.BUTTON_RB);
        bindButton(R.id.buttonZ, ControllerButtons.AXIS_RT);

        bindButton(R.id.buttonStart, ControllerButtons.BUTTON_START);
        bindButton(R.id.buttonBack, ControllerButtons.BUTTON_BACK);

        setupJoystick(overlayView.findViewById(R.id.left_joystick),
                overlayView.findViewById(R.id.left_joystick_knob));
        setupLookAround(overlayView.findViewById(R.id.right_screen_area));
        setupToggleButton(overlayView.findViewById(R.id.buttonToggle), buttonGroup);
    }

    private void setupToggleButton(Button button, ViewGroup uiGroup) {
        final boolean[] hidden = { preferences.getBoolean("controlsVisible", false) }; // key kept for compatibility
        uiGroup.setVisibility(hidden[0] ? View.INVISIBLE : View.VISIBLE);
        button.setAlpha(hidden[0] ? 0.45f : 1f);
        button.setOnClickListener(v -> {
            hidden[0] = !hidden[0];
            uiGroup.animate().cancel();
            if (hidden[0]) {
                uiGroup.animate().alpha(0f).setDuration(150)
                        .withEndAction(() -> uiGroup.setVisibility(View.INVISIBLE)).start();
            } else {
                uiGroup.setAlpha(0f);
                uiGroup.setVisibility(View.VISIBLE);
                uiGroup.animate().alpha(1f).setDuration(150).withEndAction(null).start();
            }
            button.animate().alpha(hidden[0] ? 0.45f : 1f).setDuration(150).start();
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            preferences.edit().putBoolean("controlsVisible", hidden[0]).apply();
        });
    }

    private void bindButton(int viewId, int buttonNum) {
        View button = overlayView.findViewById(viewId);
        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    setButton(buttonNum, true);
                    pressFeedback(v, true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    setButton(buttonNum, false);
                    pressFeedback(v, false);
                    return true;
            }
            return true;
        });
    }

    private void bindCButton(int viewId, int axis, int direction) {
        View button = overlayView.findViewById(viewId);
        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    setAxis(axis, direction < 0 ? Short.MAX_VALUE : Short.MIN_VALUE);
                    pressFeedback(v, true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    setAxis(axis, (short) 0);
                    pressFeedback(v, false);
                    return true;
            }
            return true;
        });
    }

    private static final OvershootInterpolator RELEASE_INTERPOLATOR = new OvershootInterpolator(3f);

    /** Visual "squish", highlight and a light haptic tick for a pressed control. */
    private void pressFeedback(View v, boolean down) {
        v.setPressed(down);
        v.animate().cancel();
        if (down) {
            v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(60).setInterpolator(null).start();
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        } else {
            v.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(RELEASE_INTERPOLATOR).start();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE);
            }
        }
    }

    private void setupLookAround(FrameLayout rightScreenArea) {
        rightScreenArea.setOnTouchListener(new View.OnTouchListener() {
            private float lastX;
            private float lastY;
            private boolean isTouching;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = event.getX();
                        lastY = event.getY();
                        isTouching = true;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (isTouching) {
                            float deltaX = event.getX() - lastX;
                            float deltaY = event.getY() - lastY;
                            lastX = event.getX();
                            lastY = event.getY();
                            float sensitivityMultiplier = 15;
                            setCameraState(0, deltaX * sensitivityMultiplier);
                            setCameraState(1, deltaY * sensitivityMultiplier);
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        isTouching = false;
                        setCameraState(0, 0.0f);
                        setCameraState(1, 0.0f);
                        break;
                }
                return touchAreaEnabled;
            }
        });
    }

    private void setupJoystick(FrameLayout base, ImageView knob) {
        base.setAlpha(0.75f);
        base.setOnTouchListener(new View.OnTouchListener() {
            private boolean atEdge;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                float centerX = base.getWidth() / 2f;
                float centerY = base.getHeight() / 2f;
                float maxRadius = base.getWidth() / 2f - knob.getWidth() / 2f;

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        base.animate().cancel();
                        base.animate().alpha(1f).setDuration(80).start();
                        knob.animate().cancel();
                        knob.setPressed(true);
                        knob.animate().scaleX(1.12f).scaleY(1.12f).setDuration(80).setInterpolator(null).start();
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        atEdge = false;
                        // fall through to position the knob
                    case MotionEvent.ACTION_MOVE: {
                        float deltaX = event.getX() - centerX;
                        float deltaY = event.getY() - centerY;
                        float distance = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
                        if (distance > maxRadius && distance > 0) {
                            float scale = maxRadius / distance;
                            deltaX *= scale;
                            deltaY *= scale;
                        }
                        // A light tick when the stick reaches full tilt, like hitting the gate on a real stick.
                        boolean edge = distance >= maxRadius * 0.98f;
                        if (edge && !atEdge && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        }
                        atEdge = edge;

                        knob.setTranslationX(deltaX);
                        knob.setTranslationY(deltaY);

                        if (maxRadius > 0) {
                            setAxis(ControllerButtons.AXIS_LX, (short) (deltaX / maxRadius * Short.MAX_VALUE));
                            setAxis(ControllerButtons.AXIS_LY, (short) (deltaY / maxRadius * Short.MAX_VALUE));
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        setAxis(ControllerButtons.AXIS_LX, (short) 0);
                        setAxis(ControllerButtons.AXIS_LY, (short) 0);
                        knob.setPressed(false);
                        knob.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                                .setDuration(220).setInterpolator(RELEASE_INTERPOLATOR).start();
                        base.animate().alpha(0.75f).setDuration(200).start();
                        return true;
                }
                return true;
            }
        });
    }

    @Override
    protected void onDestroy() {
        pollingNative = false;
        handler.removeCallbacksAndMessages(null);
        worker.shutdown();
        super.onDestroy();
    }
}
