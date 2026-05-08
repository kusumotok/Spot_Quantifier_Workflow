package io.github.kusumotok.spotworkflow.save;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ResultFolderService {

    /**
     * Creates a result folder under baseDir with the given desired name.
     * If a folder with that name already exists, appends " 2", " 3", ... until a free name is found.
     * Returns the path of the newly created folder.
     */
    public Path createResultFolder(Path baseDir, String desiredName) throws IOException {
        Path candidate = baseDir.resolve(desiredName);
        if (!Files.exists(candidate)) {
            Files.createDirectories(candidate);
            return candidate;
        }
        int suffix = 2;
        while (true) {
            candidate = baseDir.resolve(desiredName + " " + suffix);
            if (!Files.exists(candidate)) {
                Files.createDirectories(candidate);
                return candidate;
            }
            suffix++;
        }
    }

    /**
     * Returns the rois/ subdirectory path within the given result folder.
     * Does not create the directory.
     */
    public Path roiRoot(Path resultFolder) {
        return resultFolder.resolve("rois");
    }
}
