package io.github.kusumotok.spotworkflow.core.alg;

public class SpotMeasurement {
    public final int    id;
    public final long   volumeVox;
    public final double volumeUm3;
    public final double surfaceAreaUm2;
    public final double sphericity;
    public final double integratedIntensity;
    public final double meanIntensity;
    public final double maxIntensity;
    public final double centroidXUm;
    public final double centroidYUm;
    public final double centroidZUm;
    public final double maxFeret3DUm;
    public final double maxFeretP1XUm;
    public final double maxFeretP1YUm;
    public final double maxFeretP1ZUm;
    public final double maxFeretP2XUm;
    public final double maxFeretP2YUm;
    public final double maxFeretP2ZUm;

    public SpotMeasurement(int id, long volumeVox, double volumeUm3, double surfaceAreaUm2,
                            double sphericity,
                            double integratedIntensity, double meanIntensity, double maxIntensity,
                            double centroidXUm, double centroidYUm, double centroidZUm,
                            double maxFeret3DUm,
                            double maxFeretP1XUm, double maxFeretP1YUm, double maxFeretP1ZUm,
                            double maxFeretP2XUm, double maxFeretP2YUm, double maxFeretP2ZUm) {
        this.id                  = id;
        this.volumeVox           = volumeVox;
        this.volumeUm3           = volumeUm3;
        this.surfaceAreaUm2      = surfaceAreaUm2;
        this.sphericity          = sphericity;
        this.integratedIntensity = integratedIntensity;
        this.meanIntensity       = meanIntensity;
        this.maxIntensity        = maxIntensity;
        this.centroidXUm         = centroidXUm;
        this.centroidYUm         = centroidYUm;
        this.centroidZUm         = centroidZUm;
        this.maxFeret3DUm        = maxFeret3DUm;
        this.maxFeretP1XUm       = maxFeretP1XUm;
        this.maxFeretP1YUm       = maxFeretP1YUm;
        this.maxFeretP1ZUm       = maxFeretP1ZUm;
        this.maxFeretP2XUm       = maxFeretP2XUm;
        this.maxFeretP2YUm       = maxFeretP2YUm;
        this.maxFeretP2ZUm       = maxFeretP2ZUm;
    }
}
