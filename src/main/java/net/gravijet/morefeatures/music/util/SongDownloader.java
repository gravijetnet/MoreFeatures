package net.gravijet.morefeatures.music.util;

import net.gravijet.morefeatures.music.MusicConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Downloads .nbs song files from configured URLs into the local songs folder.
 * Uses java.net.HttpURLConnection with the JVM's default TLS trust store.
 */
public class SongDownloader {

    private static final int  CONNECT_TIMEOUT  = 15_000;   // 15 s
    private static final int  READ_TIMEOUT     = 60_000;   // 60 s
    private static final long MAX_DOWNLOAD_BYTES = 20 * 1024 * 1024; // 20 MB cap for NBS files

    private final Logger logger;
    private final File songsFolder;

    public SongDownloader(Logger logger, File songsFolder) {
        this.logger = logger;
        this.songsFolder = songsFolder;
    }

    /**
     * Downloads any missing song files synchronously.
     * Call this off the main thread (e.g. in an async task) to avoid lag.
     *
     * @param config the music config with song entries
     * @return the number of songs successfully downloaded
     */
    public int downloadMissing(MusicConfig config) {
        if (!songsFolder.exists() && !songsFolder.mkdirs()) {
            logger.warning("Could not create songs folder: " + songsFolder.getAbsolutePath());
            return 0;
        }

        int downloaded = 0;
        for (MusicConfig.SongEntry entry : config.getSongs()) {
            String filename = entry.getFilename();

            if (!isSafeFilename(filename)) {
                logger.warning("Skipping song with unsafe filename: " + filename);
                continue;
            }

            File target = new File(songsFolder, filename);
            if (target.exists()) {
                logger.fine("Song already exists, skipping: " + filename);
                continue;
            }

            String url = entry.getUrl();
            // Log only the filename, not the full URL, to avoid leaking tokens in log files.
            logger.info("Downloading song: " + filename);

            // Write to a .tmp file first; rename to the real name only on success.
            // This prevents a failed/partial download from being mistaken for a valid
            // file on the next server start (which would skip the download forever).
            File tmp = new File(songsFolder, filename + ".tmp");
            try {
                downloadFile(url, tmp);
                if (!tmp.renameTo(target)) {
                    throw new IOException("Could not rename " + tmp.getName() + " to " + filename);
                }
                downloaded++;
                logger.info("Downloaded: " + filename + " (" + target.length() + " bytes)");
            } catch (Exception e) {
                logger.log(Level.WARNING,
                        "Failed to download song '" + filename + "': " + e.getMessage());
                if (tmp.exists() && !tmp.delete()) {
                    logger.warning("Could not delete partial download: " + tmp.getAbsolutePath());
                }
            }
        }

        return downloaded;
    }

    private static boolean isSafeFilename(String name) {
        if (name == null || name.isEmpty()) return false;
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) return false;
        if (name.contains("..")) return false;
        return new File(name).getName().equals(name);
    }

    /**
     * Downloads a single file from a URL to a local file.
     * Only http:// and https:// URLs are permitted. Redirects are disabled to prevent SSRF.
     * Downloads are capped at MAX_DOWNLOAD_BYTES to prevent disk exhaustion.
     */
    private void downloadFile(String urlString, File destination) throws IOException {
        if (!urlString.startsWith("http://") && !urlString.startsWith("https://")) {
            throw new IOException("Rejected non-HTTP URL (only http/https allowed)");
        }
        URL url = new URL(urlString);
        // Open the connection inside the try block so disconnect() always runs,
        // even if any of the configuration calls below throw.
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("User-Agent", "MoreFeatures-Plugin/1.0");
            conn.setInstanceFollowRedirects(false);

            int responseCode;
            try {
                responseCode = conn.getResponseCode();
            } catch (IOException e) {
                throw new IOException("Could not reach server (connection or SSL error): " + e.getMessage(), e);
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("Server returned HTTP " + responseCode);
            }

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(destination)) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalRead = 0;
                while ((bytesRead = in.read(buffer)) != -1) {
                    totalRead += bytesRead;
                    if (totalRead > MAX_DOWNLOAD_BYTES) {
                        throw new IOException("Download exceeded size limit of "
                                + (MAX_DOWNLOAD_BYTES / 1024 / 1024) + " MB");
                    }
                    out.write(buffer, 0, bytesRead);
                }
                out.flush();
            }
        } finally {
            conn.disconnect();
        }
    }
}
