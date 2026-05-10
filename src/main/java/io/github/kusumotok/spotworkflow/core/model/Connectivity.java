package io.github.kusumotok.spotworkflow.core.model;

public enum Connectivity {
    C4, C6, C8, C18, C26;

    /** Convert to MorphoLibJ 3D connectivity constant (6 / 18 / 26). */
    public int to3D() {
        if (this == C18)               return 18;
        if (this == C26 || this == C8) return 26;
        return 6; // C4, C6
    }

    /** Convert an integer connectivity value (6, 18, 26) to the enum constant. */
    public static Connectivity fromInt(int n) {
        if (n == 26) return C26;
        if (n == 18) return C18;
        return C6;
    }
}
