package com.punch.app.network;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class InteractionLogStore {
    private static final String FILE_NAME = "interaction_logs.jsonl";
    private static final int MAX_ENTRIES = 300;

    private static InteractionLogStore instance;

    private final Gson gson = new Gson();
    private final File storageFile;
    private final ArrayList<InteractionLogEntry> entries = new ArrayList<>();
    private final ArrayList<Listener> listeners = new ArrayList<>();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface Listener {
        void onLogsChanged();
    }

    private InteractionLogStore(Context context) {
        storageFile = new File(context.getFilesDir(), FILE_NAME);
        loadFromDisk();
    }

    public static synchronized void init(Context context) {
        if (instance == null) {
            instance = new InteractionLogStore(context.getApplicationContext());
        }
    }

    public static synchronized InteractionLogStore get() {
        return instance;
    }

    public void append(InteractionLogEntry entry) {
        if (entry == null) {
            return;
        }
        synchronized (this) {
            if (entry.id == null || entry.id.trim().isEmpty()) {
                entry.id = UUID.randomUUID().toString();
            }
            if (entry.timeMillis <= 0L) {
                entry.timeMillis = System.currentTimeMillis();
            }
            entries.add(0, entry);
            trimLocked();
        }
        persistAsync();
        notifyListeners();
    }

    public synchronized List<InteractionLogEntry> snapshot() {
        return new ArrayList<>(entries);
    }

    public synchronized void registerListener(Listener listener) {
        if (listener == null || listeners.contains(listener)) {
            return;
        }
        listeners.add(listener);
    }

    public synchronized void unregisterListener(Listener listener) {
        listeners.remove(listener);
    }

    public void clear() {
        synchronized (this) {
            entries.clear();
        }
        ioExecutor.execute(() -> {
            if (storageFile.exists() && !storageFile.delete()) {
                // Ignore cleanup failure. A later rewrite will replace the file.
            }
        });
        notifyListeners();
    }

    private void loadFromDisk() {
        if (!storageFile.exists()) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(storageFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                try {
                    InteractionLogEntry entry = gson.fromJson(line, InteractionLogEntry.class);
                    if (entry != null) {
                        entries.add(entry);
                    }
                } catch (Exception ignored) {
                    // Skip malformed historical lines.
                }
            }
            trimLocked();
        } catch (IOException ignored) {
            // Ignore read failures. Logging must never block the app.
        }
    }

    private void persistAsync() {
        List<InteractionLogEntry> snapshot = snapshot();
        ioExecutor.execute(() -> writeSnapshot(snapshot));
    }

    private void writeSnapshot(List<InteractionLogEntry> snapshot) {
        File parent = storageFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return;
        }
        try (FileWriter writer = new FileWriter(storageFile, false)) {
            for (InteractionLogEntry entry : snapshot) {
                writer.write(gson.toJson(entry));
                writer.write('\n');
            }
            writer.flush();
        } catch (IOException ignored) {
            // Ignore write failures. Logging must never block the app.
        }
    }

    private synchronized void notifyListeners() {
        List<Listener> currentListeners = new ArrayList<>(listeners);
        for (Listener listener : currentListeners) {
            mainHandler.post(listener::onLogsChanged);
        }
    }

    private void trimLocked() {
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }
    }
}
