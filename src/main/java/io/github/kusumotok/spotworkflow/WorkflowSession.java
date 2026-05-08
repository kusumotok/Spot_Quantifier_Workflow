package io.github.kusumotok.spotworkflow;

import ij.ImagePlus;

import java.nio.file.Path;

public final class WorkflowSession {

    private Path resultFolder;
    private Path roiRoot;
    private ImagePlus boundImage;

    public Path getResultFolder()  { return resultFolder; }
    public Path getRoiRoot()       { return roiRoot; }
    public ImagePlus getBoundImage() { return boundImage; }

    public void setResultFolder(Path p)    { resultFolder = p; }
    public void setRoiRoot(Path p)         { roiRoot = p; }
    public void setBoundImage(ImagePlus imp) { boundImage = imp; }

    public boolean hasResultFolder() { return resultFolder != null; }
    public boolean hasBoundImage()   { return boundImage != null; }

    public void clear() {
        resultFolder = null;
        roiRoot = null;
        boundImage = null;
    }
}
