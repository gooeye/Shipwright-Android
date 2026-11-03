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
import android.hardware.input.InputManager;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
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

    // An app update ships a new soh.otr (refreshed by the file setup). The game data built from
    // the ROM (oot.otr / oot-mq.otr) is kept: the game checks its version itself and offers to
    // rebuild it only when it no longer matches, so updates don't ask for the ROM again.
    private void deleteOutdatedAssets() {
        File root = sohRoot();
        deleteIfExists(new File(root, "soh.otr"));
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
                if (!gameReady) {
                    gameReady = true;
                    touchControls.showFirstRunHint();
                }
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
    // Touch controls, pause menu and save states
    // ---------------------------------------------------------------------------------------

    public native void attachController();
    public native void detachController();
    public native void setButton(int button, boolean value);
    public native void setAxis(int axis, short value);
    public native void addLookDelta(float dxDp, float dyDp);
    private native void nativeSaveState(int slot, boolean load);
    private native boolean nativeHasSaveState(int slot);
    private native void nativeSetTouchControlsVisible(boolean visible);
    private native int nativeGetSetting(String name, int defaultValue);
    private native void nativeSetSetting(String name, int value);

    // Game settings (CVars) shown as toggles in the pause menu.
    private static final String SETTING_CAMERA_FOLLOWS = "gSettings.Mobile.CameraFollowsMovement";
    private static final String SETTING_MOVE_FIRST_PERSON = "gSettings.MoveInFirstPerson";

    private TouchControls touchControls;
    private QuickMenu quickMenu;
    private boolean gameReady;      // setup finished and the game is running
    private boolean gameMenuOpen;   // the game's own (ImGui) settings menu is showing
    private boolean pausedByMenu;   // we paused the game for the pause menu or layout editor

    // Called by native code (game thread) when the game's settings menu closes / opens.
    void EnableTouchArea() {
        runOnUiThread(() -> {
            gameMenuOpen = false;
            if (touchControls != null) {
                touchControls.setSuspended(false);
            }
        });
    }

    void DisableTouchArea() {
        runOnUiThread(() -> {
            gameMenuOpen = true;
            if (touchControls != null) {
                touchControls.setSuspended(true);
            }
        });
    }

    private void setupControllerOverlay() {
        ViewGroup content = (ViewGroup) getContentView();
        content.setKeepScreenOn(true);
        touchControls = new TouchControls(this, content, preferences, new TouchControls.Input() {
            @Override
            public void setButton(int button, boolean value) {
                MainActivity.this.setButton(button, value);
            }

            @Override
            public void setAxis(int axis, short value) {
                MainActivity.this.setAxis(axis, value);
            }

            @Override
            public void addLookDelta(float dxDp, float dyDp) {
                MainActivity.this.addLookDelta(dxDp, dyDp);
            }

            @Override
            public void setControlsVisible(boolean visible) {
                try {
                    nativeSetTouchControlsVisible(visible);
                } catch (UnsatisfiedLinkError e) {
                    // native library failed to load; SDL shows its own error
                }
            }
        }, v -> openPauseMenu());

        quickMenu = new QuickMenu(this, content, new QuickMenu.Listener() {
            @Override
            public void onResumeGame() {
                closePauseMenu();
            }

            @Override
            public void onSaveState(int slot) {
                nativeSaveState(slot, false);
                closePauseMenu();
            }

            @Override
            public void onLoadState(int slot) {
                nativeSaveState(slot, true);
                closePauseMenu();
            }

            @Override
            public boolean hasSaveState(int slot) {
                try {
                    return nativeHasSaveState(slot);
                } catch (UnsatisfiedLinkError e) {
                    return false;
                }
            }

            @Override
            public void onOpenSettings() {
                closePauseMenu();
                // The game opens its menu on the Android back key (ImGuiKey_AppBack).
                SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_BACK);
                SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_BACK);
            }

            @Override
            public void onEditControls() {
                quickMenu.hide();
                // Stay paused while editing; resume when the player taps Done.
                touchControls.startEditing(MainActivity.this::resumeFromMenu);
            }

            @Override
            public void onToggleControls() {
                touchControls.setControlsHidden(!touchControls.areControlsHidden());
                closePauseMenu();
            }

            @Override
            public boolean areControlsHidden() {
                return touchControls.areControlsHidden();
            }
        });
        addPauseMenuToggles();

        InputManager inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        inputManager.registerInputDeviceListener(gamepadListener, handler);
        updateGamepadState(false);
    }

    private void addPauseMenuToggles() {
        quickMenu.addToggle(R.string.toggle_camera_follow,
                () -> nativeGetSetting(SETTING_CAMERA_FOLLOWS, 1) != 0,
                on -> nativeSetSetting(SETTING_CAMERA_FOLLOWS, on ? 1 : 0));
        quickMenu.addToggle(R.string.toggle_move_first_person,
                () -> nativeGetSetting(SETTING_MOVE_FIRST_PERSON, 1) != 0,
                on -> nativeSetSetting(SETTING_MOVE_FIRST_PERSON, on ? 1 : 0));
        quickMenu.addToggle(R.string.toggle_vibration, touchControls::isVibrationOn, touchControls::setVibrationOn);
    }

    // Coming back to the app lands on the pause menu rather than straight into the action.
    @Override
    protected void onPause() {
        boolean showMenu = gameReady && !gameMenuOpen && touchControls != null && !touchControls.isEditing()
                && !quickMenu.isShowing();
        super.onPause();
        if (showMenu) {
            touchControls.releaseAll();
            pausedByMenu = true; // keeps SDL from resuming the game in onResume
            quickMenu.show();
        }
    }

    // Physical controllers: hide the touch controls while one is connected.
    private final InputManager.InputDeviceListener gamepadListener = new InputManager.InputDeviceListener() {
        @Override
        public void onInputDeviceAdded(int deviceId) {
            updateGamepadState(true);
        }

        @Override
        public void onInputDeviceRemoved(int deviceId) {
            updateGamepadState(false);
        }

        @Override
        public void onInputDeviceChanged(int deviceId) {
            updateGamepadState(false);
        }
    };
    private boolean gamepadConnected;

    private void updateGamepadState(boolean announce) {
        boolean connected = false;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || device.isVirtual()) {
                continue;
            }
            int sources = device.getSources();
            boolean pad = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
            // Some phones report sensors (e.g. fingerprint readers) as gamepads; a real one has face buttons.
            if (pad && device.hasKeys(KeyEvent.KEYCODE_BUTTON_A)[0]) {
                connected = true;
                break;
            }
        }
        if (connected == gamepadConnected) {
            return;
        }
        gamepadConnected = connected;
        if (touchControls != null) {
            boolean wasVisible = !touchControls.areControlsHidden();
            touchControls.setHiddenForGamepad(connected);
            if (connected && announce && wasVisible) {
                Toast.makeText(this, R.string.gamepad_connected, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void openPauseMenu() {
        if (!gameReady || gameMenuOpen || quickMenu.isShowing() || touchControls.isEditing()) {
            return;
        }
        touchControls.releaseAll();
        pausedByMenu = true;
        pauseNativeThread();
        quickMenu.show();
    }

    private void closePauseMenu() {
        quickMenu.hide();
        resumeFromMenu();
    }

    private void resumeFromMenu() {
        if (pausedByMenu) {
            pausedByMenu = false;
            super.resumeNativeThread();
        }
    }

    // SDL resumes the game when the app returns to the foreground; keep it paused if the pause
    // menu or the layout editor is still open.
    @Override
    protected void resumeNativeThread() {
        if (pausedByMenu) {
            return;
        }
        super.resumeNativeThread();
    }

    // Back opens the pause menu (or closes whatever is open on top of the game). A gamepad's own
    // back/select button still goes to the game.
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        boolean fromGamepad = (event.getSource() & (InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_JOYSTICK)) != 0;
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && gameReady && !fromGamepad && !gameMenuOpen
                && touchControls != null) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                if (touchControls.isEditing()) {
                    touchControls.finishEditing();
                } else if (quickMenu.isShowing()) {
                    closePauseMenu();
                } else {
                    openPauseMenu();
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDestroy() {
        pollingNative = false;
        ((InputManager) getSystemService(Context.INPUT_SERVICE)).unregisterInputDeviceListener(gamepadListener);
        handler.removeCallbacksAndMessages(null);
        worker.shutdown();
        super.onDestroy();
    }
}
