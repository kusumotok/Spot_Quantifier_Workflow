package io.github.kusumotok.spotworkflow;

import ij.ImagePlus;

import java.nio.file.Path;

public final class WorkflowSession {

    private Path resultFolder;
    private Path roiRoot;
    private Path projectFolder;
    private Path seedRoiRoot;
    private Path resultRoiRoot;
    private ImagePlus boundImage;

    public Path getResultFolder()  { return resultFolder; }
    public Path getRoiRoot()       { return roiRoot; }
    public Path getProjectFolder() { return projectFolder; }
    public Path getSeedRoiRoot()   { return seedRoiRoot; }
    public Path getResultRoiRoot() { return resultRoiRoot; }
    public ImagePlus getBoundImage() { return boundImage; }

    public void setResultFolder(Path p)    { resultFolder = p; }
    public void setRoiRoot(Path p)         { roiRoot = p; }
    public void setProjectFolder(Path p) {
        projectFolder = p;
        resultFolder = p;
        seedRoiRoot = p != null ? p.resolve("seed_rois") : null;
        resultRoiRoot = p != null ? p.resolve("result_rois") : null;
        roiRoot = resultRoiRoot;
    }
    public void setSeedRoiRoot(Path p)     { seedRoiRoot = p; }
    public void setResultRoiRoot(Path p) {
        resultRoiRoot = p;
        roiRoot = p;
    }
    public void setBoundImage(ImagePlus imp) { boundImage = imp; }

    public boolean hasResultFolder() { return resultFolder != null; }
    public boolean hasProjectFolder() { return projectFolder != null; }
    public boolean hasResultRoiRoot() { return resultRoiRoot != null; }
    public boolean hasBoundImage()   { return boundImage != null; }

    public void clear() {
        resultFolder = null;
        roiRoot = null;
        projectFolder = null;
        seedRoiRoot = null;
        resultRoiRoot = null;
        boundImage = null;
    }
}
