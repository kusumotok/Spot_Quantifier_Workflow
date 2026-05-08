package io.github.kusumotok.spotworkflow.core.model;

/**
 * Immutable key class representing a (sliceIndex, label) combination.
 * Used for tracking and merging regions across slices in 3D segmentation.
 * 
 * <p>This class is designed to be used as a key in hash-based collections
 * (e.g., HashMap, HashSet) with proper equals() and hashCode() implementations.</p>
 * 
 * @see io.github.kusumotok.spotworkflow.core.alg.SliceMerger
 */
public final class LabelKey {
    /**
     * The slice index (0-based).
     */
    public final int sliceIndex;
    
    /**
     * The label value (1-based, 0 represents background).
     */
    public final int label;
    
    /**
     * Creates a new LabelKey with the specified slice index and label.
     * 
     * @param sliceIndex the slice index (0-based)
     * @param label the label value (typically 1-based, where 0 represents background)
     */
    public LabelKey(int sliceIndex, int label) {
        this.sliceIndex = sliceIndex;
        this.label = label;
    }
    
    /**
     * Compares this LabelKey to another object for equality.
     * Two LabelKey objects are equal if they have the same sliceIndex and label.
     * 
     * @param o the object to compare with
     * @return true if the objects are equal, false otherwise
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LabelKey labelKey = (LabelKey) o;
        return sliceIndex == labelKey.sliceIndex && label == labelKey.label;
    }
    
    /**
     * Returns a hash code value for this LabelKey.
     * The hash code is computed based on both sliceIndex and label.
     * 
     * @return a hash code value for this object
     */
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + sliceIndex;
        result = 31 * result + label;
        return result;
    }
    
    /**
     * Returns a string representation of this LabelKey.
     * 
     * @return a string in the format "LabelKey(slice=X, label=Y)"
     */
    @Override
    public String toString() {
        return "LabelKey(slice=" + sliceIndex + ", label=" + label + ")";
    }
}
