package io.github.kusumotok.spotworkflow.core.model;

public enum Connectivity {
    C4, C8, C6;

    /**
     * Convert to MorphoLibJ 3D connectivity constant.
     * C6 = 6-connectivity (face neighbors only).
     */
    public int to3D() {
        return this == C6 ? 6 : (this == C8 ? 26 : 6);
    }

    /** Convert an integer connectivity value (6, 18, 26) to the nearest enum constant. */
    public static Connectivity fromInt(int n) {
        return n == 26 ? C8 : C6; // 6 and 18 both map to C6 (face-connectivity)
    }
}
