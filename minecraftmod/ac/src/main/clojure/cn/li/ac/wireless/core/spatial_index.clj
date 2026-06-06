(ns cn.li.ac.wireless.core.spatial-index
  "Shared spatial index for the wireless system.

  The index maps chunk-coord [cx cy cz] -> #{vblock}, allowing O(1) range
  searches instead of full-table scans.

  This module is a pure atom-based data structure with no dependency on
  world.clj or network.clj, deliberately breaking the circular-dependency
  that previously forced network.clj to duplicate removal logic."
  (:require [cn.li.ac.foundation.position :as pos]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Construction
;; ============================================================================

(defn create-spatial-index
  "Return a fresh spatial-index atom: {[cx cy cz] -> #{vblock}}."
  []
  (atom {}))

(defn create-spatial-index-value
  "Return a fresh spatial-index value: {[cx cy cz] -> #{vblock}}."
  []
  {})

;; ============================================================================
;; Mutation
;; ============================================================================

(defn add-to-index
  "Add `vblock` to an immutable spatial index value."
  [index vblock]
  (let [chunk-key (pos/pos->chunk-key (:x vblock) (:y vblock) (:z vblock))]
    (update index chunk-key (fnil conj #{}) vblock)))

(defn add-to-index!
  "Add `vblock` to `index-atom` at the appropriate chunk bucket."
  [index-atom vblock]
  (swap! index-atom add-to-index vblock))

(defn remove-from-index
  "Remove `vblock` from an immutable spatial index value.
   Returns index unchanged when vblock has nil coordinates."
  [index vblock]
  (if (or (nil? (:x vblock)) (nil? (:y vblock)) (nil? (:z vblock)))
    index
    (let [chunk-key (pos/pos->chunk-key (:x vblock) (:y vblock) (:z vblock))]
      (if-let [chunk-set (get index chunk-key)]
        (let [new-set (disj chunk-set vblock)]
          (if (empty? new-set)
            (dissoc index chunk-key)
            (assoc index chunk-key new-set)))
        index))))

(defn remove-from-index!
  "Remove `vblock` from `index-atom`.  Removes the bucket entirely when empty."
  [index-atom vblock]
  (swap! index-atom remove-from-index vblock))

;; ============================================================================
;; Query
;; ============================================================================

(defn nearby-chunk-keys
  "Return the set of chunk keys whose bounding boxes overlap a sphere of
  `search-radius` centered at (x, y, z)."
  [x y z search-radius]
  (pos/nearby-chunk-keys x y z search-radius))

(defn vblocks-in-index
  "Return the union of all vblocks in `chunk-keys` from an index value."
  [index chunk-keys]
  (log/info "[vblocks-in-index] index-keys=" (pr-str (keys index)))
  (doseq [ck (take 3 chunk-keys)]
    (log/info "[vblocks-in-index] lookup-key=" (pr-str ck) "found=" (boolean (get index ck))))
  (reduce (fn [acc chunk-key]
            (if-let [vblocks (get index chunk-key)]
              (into acc vblocks)
              acc))
          #{}
          chunk-keys))

(defn vblocks-in-chunks
  "Return the union of all vblocks stored in the given `chunk-keys`."
  [index-atom chunk-keys]
  (vblocks-in-index @index-atom chunk-keys))
