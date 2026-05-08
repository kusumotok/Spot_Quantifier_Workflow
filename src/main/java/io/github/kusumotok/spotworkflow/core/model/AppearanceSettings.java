package io.github.kusumotok.spotworkflow.core.model;

public class AppearanceSettings {
    private int seedColor = 0xFF0000;      // Red for seeds
    private int domainColor = 0x00FF00;    // Green for domain (more visible)
    private int bgColor = 0x0000FF;        // Blue for background
    private boolean showSeed = true;
    private boolean showDomain = true;
    private boolean showBg = false;
    private boolean showSeedCentroids = true;
    private double opacity = 0.6;

    public int getSeedColor() { return seedColor; }
    public int getDomainColor() { return domainColor; }
    public int getBgColor() { return bgColor; }
    public boolean isShowSeed() { return showSeed; }
    public boolean isShowDomain() { return showDomain; }
    public boolean isShowBg() { return showBg; }
    public boolean isShowSeedCentroids() { return showSeedCentroids; }
    public double getOpacity() { return opacity; }

    public void setSeedColor(int seedColor) { this.seedColor = seedColor; }
    public void setDomainColor(int domainColor) { this.domainColor = domainColor; }
    public void setBgColor(int bgColor) { this.bgColor = bgColor; }
    public void setShowSeed(boolean showSeed) { this.showSeed = showSeed; }
    public void setShowDomain(boolean showDomain) { this.showDomain = showDomain; }
    public void setShowBg(boolean showBg) { this.showBg = showBg; }
    public void setShowSeedCentroids(boolean showSeedCentroids) { this.showSeedCentroids = showSeedCentroids; }
    public void setOpacity(double opacity) { this.opacity = opacity; }
}
