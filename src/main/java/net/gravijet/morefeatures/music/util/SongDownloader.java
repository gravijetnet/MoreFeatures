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

    private static final int CONNECT_TIMEOUT = 15_000;  // 15s
    private static final int READ_TIMEOUT    = 60_000;  // 60s

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
            File target = new File(songsFolder, entry.getFilename());
            if (target.exists()) {
                logger.fine("Song already exists, skipping: " + entry.getFilename());
                continue;
            }

            String url = entry.getUrl();
            logger.info("Downloading song: " + entry.getFilename() + " from " + url);

            try {
                downloadFile(url, target);
                downloaded++;
                logger.info("Downloaded song: " + entry.getFilename() + " (" + target.length() + " bytes)");
            } catch (IOException e) {
                logger.log(Level.WARNING,
                        "Failed to download song '" + entry.getFilename() + "' from " + url
                        + " — " + e.getMessage());
                // Clean up partial file if download failed
                if (target.exists()) {
                    target.delete();
                }
            }
        }

        return downloaded;
    }

    /**
     * Downloads a single file from a URL to a local file.
     */
    private void downloadFile(String urlString, File destination) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("User-Agent", "MoreFeatures-Plugin/1.0");
        conn.setInstanceFollowRedirects(true);

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
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
        } finally {
            conn.disconnect();
        }
    }
}
