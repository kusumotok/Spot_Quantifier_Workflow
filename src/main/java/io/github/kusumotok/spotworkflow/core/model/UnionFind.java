package io.github.kusumotok.spotworkflow.core.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Union-Find (Disjoint Set Union) data structure with path compression and union by rank.
 * 
 * This data structure efficiently manages a collection of disjoint sets and supports
 * two primary operations:
 * - find(x): Find the representative (root) of the set containing element x
 * - union(x, y): Merge the sets containing elements x and y
 * 
 * The implementation uses two optimizations:
 * - Path compression: During find operations, flatten the tree structure
 * - Union by rank: Attach smaller trees under larger trees to keep trees shallow
 * 
 * Time complexity:
 * - find: O(α(n)) amortized, where α is the inverse Ackermann function (effectively constant)
 * - union: O(α(n)) amortized
 * - getGroups: O(n)
 * 
 * Used in slice-based 3D segmentation to merge overlapping regions across slices.
 * 
 * @see SliceMerger
 */
public class UnionFind {
    private final int[] parent;
    private final int[] rank;
    private final int size;
    
    /**
     * Creates a new UnionFind data structure with the specified number of elements.
     * Initially, each element is in its own set (parent[i] = i).
     * 
     * @param size the number of elements (must be positive)
     * @throws IllegalArgumentException if size is not positive
     */
    public UnionFind(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Size must be positive, got: " + size);
        }
        
        this.size = size;
        this.parent = new int[size];
        this.rank = new int[size];
        
        // Initialize: each element is its own parent (separate set)
        for (int i = 0; i < size; i++) {
            parent[i] = i;
            rank[i] = 0;
        }
    }
    
    /**
     * Finds the representative (root) of the set containing element x.
     * Uses path compression to flatten the tree structure for future operations.
     * 
     * @param x the element to find (must be in range [0, size))
     * @return the representative of the set containing x
     * @throws IllegalArgumentException if x is out of bounds
     */
    public int find(int x) {
        validateIndex(x);
        
        // Path compression: make every node on the path point directly to the root
        if (parent[x] != x) {
            parent[x] = find(parent[x]);
        }
        return parent[x];
    }
    
    /**
     * Merges the sets containing elements x and y.
     * Uses union by rank to keep the tree structure shallow.
     * 
     * If x and y are already in the same set, this operation has no effect.
     * 
     * @param x the first element (must be in range [0, size))
     * @param y the second element (must be in range [0, size))
     * @throws IllegalArgumentException if x or y is out of bounds
     */
    public void union(int x, int y) {
        validateIndex(x);
        validateIndex(y);
        
        int rootX = find(x);
        int rootY = find(y);
        
        // Already in the same set
        if (rootX == rootY) {
            return;
        }
        
        // Union by rank: attach smaller tree under larger tree
        if (rank[rootX] < rank[rootY]) {
            parent[rootX] = rootY;
        } else if (rank[rootX] > rank[rootY]) {
            parent[rootY] = rootX;
        } else {
            // Same rank: arbitrarily choose one as parent and increment its rank
            parent[rootY] = rootX;
            rank[rootX]++;
        }
    }
    
    /**
     * Returns all groups (connected components) as a map from representative to group members.
     * 
     * The returned map has the following properties:
     * - Keys are representatives (roots) of each group
     * - Values are lists of all elements in that group
     * - Each element appears in exactly one group
     * 
     * @return a map from representative to list of group members
     */
    public Map<Integer, List<Integer>> getGroups() {
        Map<Integer, List<Integer>> groups = new HashMap<>();
        
        for (int i = 0; i < size; i++) {
            int root = find(i);
            groups.computeIfAbsent(root, k -> new ArrayList<>()).add(i);
        }
        
        return groups;
    }
    
    /**
     * Returns the number of elements in this UnionFind structure.
     * 
     * @return the number of elements
     */
    public int getSize() {
        return size;
    }
    
    /**
     * Validates that the given index is within bounds.
     * 
     * @param index the index to validate
     * @throws IllegalArgumentException if index is out of bounds
     */
    private void validateIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IllegalArgumentException(
                String.format("Index %d is out of bounds [0, %d)", index, size)
            );
        }
    }
}
