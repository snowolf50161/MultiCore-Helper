package com.example.forcedmulticore;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ClientUpdateChecker {
    static final String DOWNLOAD_PAGE = "https://www.curseforge.com/minecraft/mc-mods/multicore-helper";
    private static final String UPDATE_ENDPOINT = "https://api.cfwidget.com/1600650";
    private static final Pattern MOD_VERSION = Pattern.compile("-(\\d+\\.\\d+\\.\\d+)\\.jar$", Pattern.CASE_INSENSITIVE);
    private static final int MAX_RESPONSE_CHARS = 512 * 1024;

    private final String currentVersion;
    private final String gameVersion;
    private final String loader;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean consumed = new AtomicBoolean();
    private volatile Result availableUpdate;

    ClientUpdateChecker(String currentVersion, String gameVersion, String loader) {
        this.currentVersion = normalizeVersion(currentVersion);
        this.gameVersion = gameVersion;
        this.loader = loader;
    }

    void start() {
        if (!started.compareAndSet(false, true) || !isEnabled()) {
            return;
        }

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    availableUpdate = queryLatestRelease();
                } catch (Throwable ignored) {
                    // Update checks must never affect game startup or gameplay.
                }
            }
        }, "MultiCore Helper Update Check");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    Result poll() {
        Result result = availableUpdate;
        if (result == null || !consumed.compareAndSet(false, true)) {
            return null;
        }
        return result;
    }

    private Result queryLatestRelease() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(UPDATE_ENDPOINT).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "MultiCore-Helper-Update-Checker/" + currentVersion);

        Reader reader = null;
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }

            reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8);
            JsonObject root = new JsonParser().parse(new LimitedReader(reader, MAX_RESPONSE_CHARS)).getAsJsonObject();
            JsonArray files = root.getAsJsonArray("files");
            if (files == null) {
                return null;
            }

            String newestVersion = null;
            String newestUrl = DOWNLOAD_PAGE;
            for (JsonElement element : files) {
                if (!element.isJsonObject()) {
                    continue;
                }

                JsonObject file = element.getAsJsonObject();
                if (!equalsField(file, "type", "release")
                        || !equalsField(file, "version", gameVersion)
                        || !containsLoader(file.getAsJsonArray("versions"), loader)) {
                    continue;
                }

                String name = stringField(file, "name");
                Matcher matcher = name == null ? null : MOD_VERSION.matcher(name);
                if (matcher == null || !matcher.find()) {
                    continue;
                }

                String candidate = matcher.group(1);
                if (newestVersion == null || compareVersions(candidate, newestVersion) > 0) {
                    newestVersion = candidate;
                    String fileUrl = stringField(file, "url");
                    newestUrl = fileUrl == null ? DOWNLOAD_PAGE : fileUrl;
                }
            }

            if (newestVersion != null && compareVersions(newestVersion, currentVersion) > 0) {
                return new Result(currentVersion, newestVersion, newestUrl);
            }
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
            connection.disconnect();
        }
    }

    private boolean isEnabled() {
        File config = new File(new File(System.getProperty("user.dir", "."), "config"),
                "multicore-helper-client.properties");
        Properties properties = new Properties();

        if (config.isFile()) {
            FileInputStream input = null;
            try {
                input = new FileInputStream(config);
                properties.load(input);
            } catch (Exception ignored) {
                return true;
            } finally {
                if (input != null) {
                    try {
                        input.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            return Boolean.parseBoolean(properties.getProperty("checkForUpdates", "true"));
        }

        File parent = config.getParentFile();
        if (parent != null && parent.isDirectory() || parent != null && parent.mkdirs()) {
            properties.setProperty("checkForUpdates", "true");
            FileOutputStream output = null;
            try {
                output = new FileOutputStream(config);
                properties.store(output,
                        "MultiCore Helper client settings. The update check sends no gameplay or player data.");
            } catch (Exception ignored) {
            } finally {
                if (output != null) {
                    try {
                        output.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return true;
    }

    private static boolean equalsField(JsonObject object, String key, String expected) {
        String value = stringField(object, key);
        return value != null && expected.equalsIgnoreCase(value);
    }

    private static String stringField(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static boolean containsLoader(JsonArray values, String expected) {
        if (values == null) {
            return false;
        }
        for (JsonElement value : values) {
            if (expected.equalsIgnoreCase(value.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeVersion(String version) {
        Matcher matcher = Pattern.compile("(\\d+\\.\\d+\\.\\d+)").matcher(version);
        String normalized = version;
        while (matcher.find()) {
            normalized = matcher.group(1);
        }
        return normalized;
    }

    private static int compareVersions(String left, String right) {
        String[] a = normalizeVersion(left).split("\\.");
        String[] b = normalizeVersion(right).split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int av = i < a.length ? Integer.parseInt(a[i]) : 0;
            int bv = i < b.length ? Integer.parseInt(b[i]) : 0;
            if (av != bv) {
                return av < bv ? -1 : 1;
            }
        }
        return 0;
    }

    static final class Result {
        final String currentVersion;
        final String latestVersion;
        final String downloadUrl;

        Result(String currentVersion, String latestVersion, String downloadUrl) {
            this.currentVersion = currentVersion;
            this.latestVersion = latestVersion;
            this.downloadUrl = downloadUrl;
        }
    }

    private static final class LimitedReader extends Reader {
        private final Reader delegate;
        private int remaining;

        LimitedReader(Reader delegate, int limit) {
            this.delegate = delegate;
            this.remaining = limit;
        }

        @Override
        public int read(char[] buffer, int offset, int length) throws java.io.IOException {
            if (remaining <= 0) {
                return -1;
            }
            int read = delegate.read(buffer, offset, Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }

        @Override
        public void close() throws java.io.IOException {
            delegate.close();
        }
    }
}

