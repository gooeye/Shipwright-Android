package com.dishii.soh;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Prepares the files the native side needs before the game can start:
 * the extractor XMLs (unpacked from one zip into fast app-internal storage)
 * and soh.otr (copied to the shared SOH folder). Every step writes to a temporary
 * name first and is committed with a rename, plus a version marker, so a setup
 * interrupted at any point is redone cleanly on the next launch.
 */
public class SetupFiles {

    private static final String TAG = "SetupFiles";
    private static final String EXTRACTOR_ZIP = "extractor.zip";
    private static final String EXTRACTOR_MANIFEST = "extractor_manifest.txt";
    private static final String MARKER_NAME = ".setup-complete";

    public interface Listener {
        /** Called from the worker thread, throttled. */
        void onProgress(long filesDone, long filesTotal);
    }

    private final Context context;
    private final File sohRoot;

    public SetupFiles(Context context, File sohRoot) {
        this.context = context;
        this.sohRoot = sohRoot;
    }

    /** Where the native extractor looks for assets/extractor (see Extractor::ResolveInstallPath). */
    public File extractorRoot() {
        return new File(context.getFilesDir(), "extractor-root");
    }

    private File markerFile() {
        return new File(extractorRoot(), MARKER_NAME);
    }

    private String expectedMarker() {
        return "version=" + BuildConfig.VERSION_CODE;
    }

    public boolean isComplete() {
        File marker = markerFile();
        if (!marker.isFile() || !new File(sohRoot, "soh.otr").isFile()
                || !new File(extractorRoot(), "assets/extractor").isDirectory()) {
            return false;
        }
        try {
            return expectedMarker().equals(readFirstLine(new java.io.FileInputStream(marker)));
        } catch (IOException e) {
            return false;
        }
    }

    /** Forces the next launch to redo setup (used when the app version changes). */
    public void invalidate() {
        //noinspection ResultOfMethodCallIgnored
        markerFile().delete();
    }

    /** Runs all setup steps. Throws with a user-readable message on failure. */
    public void run(Listener listener) throws IOException {
        ensureDir(sohRoot);
        ensureDir(new File(sohRoot, "mods"));
        unpackExtractor(listener);
        copySohOtr();
        writeMarker();
    }

    private void unpackExtractor(Listener listener) throws IOException {
        File root = extractorRoot();
        File staging = new File(context.getFilesDir(), "extractor-root.partial");
        deleteRecursive(staging);
        deleteRecursive(root);
        ensureDir(staging);

        long total = readManifestFileCount();
        long done = 0;
        long lastReport = 0;
        byte[] buffer = new byte[64 * 1024];
        String stagingCanonical = staging.getCanonicalPath() + File.separator;

        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(context.getAssets().open(EXTRACTOR_ZIP), 256 * 1024))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File out = new File(staging, entry.getName());
                // Guard against entries escaping the target folder.
                if (!out.getCanonicalPath().startsWith(stagingCanonical)) {
                    throw new IOException("Unexpected entry in extractor archive: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    ensureDir(out);
                    continue;
                }
                ensureDir(out.getParentFile());
                try (OutputStream os = new FileOutputStream(out)) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        os.write(buffer, 0, read);
                    }
                }
                done++;
                long now = System.nanoTime();
                if (listener != null && now - lastReport > 50_000_000L) {
                    lastReport = now;
                    listener.onProgress(done, Math.max(total, done));
                }
            }
        }
        if (listener != null) {
            listener.onProgress(done, Math.max(total, done));
        }
        if (done == 0) {
            throw new IOException("The extractor archive in this build is empty.");
        }
        if (!staging.renameTo(root)) {
            throw new IOException("Couldn't move extractor files into place.");
        }
    }

    private void copySohOtr() throws IOException {
        File target = new File(sohRoot, "soh.otr");
        File partial = new File(sohRoot, "soh.otr.partial");
        try (InputStream in = context.getAssets().open("soh.otr");
             OutputStream out = new FileOutputStream(partial)) {
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("Couldn't replace " + target.getPath());
        }
        if (!partial.renameTo(target)) {
            throw new IOException("Couldn't write " + target.getPath());
        }
    }

    private void writeMarker() throws IOException {
        try (OutputStream out = new FileOutputStream(markerFile())) {
            out.write((expectedMarker() + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private long readManifestFileCount() {
        try {
            String line = readFirstLine(context.getAssets().open(EXTRACTOR_MANIFEST));
            return Long.parseLong(line.trim().split("\\s+")[0]);
        } catch (Exception e) {
            Log.w(TAG, "No extractor manifest; progress will be approximate", e);
            return 0;
        }
    }

    private static String readFirstLine(InputStream in) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        }
    }

    private static void ensureDir(File dir) throws IOException {
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Couldn't create folder " + dir.getPath());
        }
    }

    public static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
