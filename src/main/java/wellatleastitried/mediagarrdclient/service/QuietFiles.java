package wellatleastitried.mediagarrdclient.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class QuietFiles {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuietFiles.class);

    private QuietFiles() {
    }

    static boolean delete(Path path) {
        try {
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.warn("Could not delete {}: {}", path, e.getMessage());
            return false;
        }
    }
}
