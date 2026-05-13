(ns cn.li.ac.wireless.core.spatial-index
  "Shared spatial index for the wireless system.

  The index maps chunk-coord [cx cy cz] -> #{vblock}, allowing O(1) range
  searches instead of full-table scans.

  This module is a pure atom-based data structure with no dependency on
  world.clj or network.clj, deliberately breaking the circular-dependency
  that previously forced network.clj to duplicate removal logic.")

;; ============================================================================
;; Construction
;; ============================================================================

(defn create-spatial-index
  "Return a fresh spatial-index atom: {[cx cy cz] -> #{vblock}}."
  []
  (atom {}))

;; ============================================================================
;; Internal helpers
;; ============================================================================

(defn- pos->chunk-key
  "Convert world coordinates to the enclosing 16³-block chunk key."
  [x y z]
  [(quot x 16) (quot y 16) (quot z 16)])

;; ============================================================================
;; Mutation
;; ============================================================================

(defn add-to-index!
  "Add `vblock` to `index-atom` at the appropriate chunk bucket."
  [index-atom vblock]
  (let [chunk-key (pos->chunk-key (:x vblock) (:y vblock) (:z vblock))]
    (swap! index-atom update chunk-key (fnil conj #{}) vblock)))

(defn remove-from-index!
  "Remove `vblock` from `index-atom`.  Removes the bucket entirely when empty."
  [index-atom vblock]
  (let [chunk-key (pos->chunk-key (:x vblock) (:y vblock) (:z vblock))]
    (swap! index-atom
           (fn [idx]
             (if-let [chunk-set (get idx chunk-key)]
               (let [new-set (disj chunk-set vblock)]
                 (if (empty? new-set)
                   (dissoc idx chunk-key)
                   (assoc idx chunk-key new-set)))
               idx)))))

;; ============================================================================
;; Query
;; ============================================================================

(defn nearby-chunk-keys
  "Return the set of chunk keys whose bounding boxes overlap a sphere of
  `search-radius` centered at (x, y, z)."
  [x y z search-radius]
  (let [chunk-range (inc (quot search-radius 16))
        cx (quot x 16)
        cy (quot y 16)
        cz (quot z 16)]
    (for [dx (range (- chunk-range) (inc chunk-range))
          dy (range (- chunk-range) (inc chunk-range))
          dz (range (- chunk-range) (inc chunk-range))]
      [(+ cx dx) (+ cy dy) (+ cz dz)])))

(defn vblocks-in-chunks
  "Return the union of all vblocks stored in the given `chunk-keys`."
  [index-atom chunk-keys]
  (let [idx @index-atom]
    (reduce (fn [acc chunk-key]
              (if-let [vblocks (get idx chunk-key)]
                (into acc vblocks)
                acc))
            #{}
            chunk-keys)))
