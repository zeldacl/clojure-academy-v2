(ns cn.li.ac.wireless.core.spatial-index
  "Shared spatial index for the wireless system.

  The index maps chunk-coord [cx cy cz] -> #{vblock}, allowing O(1) range
  searches instead of full-table scans.

  This module is a pure atom-based data structure with no dependency on
  world.clj or network.clj, deliberately breaking the circular-dependency
  that previously forced network.clj to duplicate removal logic."
  (:require [cn.li.ac.foundation.position :as pos]))

;; ============================================================================
;; Construction
;; ============================================================================

(defn create-spatial-index
  "Return a fresh spatial-index atom: {[cx cy cz] -> #{vblock}}."
  []
  (atom {}))

;; ============================================================================
;; Mutation
;; ============================================================================

(defn add-to-index!
  "Add `vblock` to `index-atom` at the appropriate chunk bucket."
  [index-atom vblock]
  (let [chunk-key (pos/pos->chunk-key (:x vblock) (:y vblock) (:z vblock))]
    (swap! index-atom update chunk-key (fnil conj #{}) vblock)))

(defn remove-from-index!
  "Remove `vblock` from `index-atom`.  Removes the bucket entirely when empty."
  [index-atom vblock]
  (let [chunk-key (pos/pos->chunk-key (:x vblock) (:y vblock) (:z vblock))]
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
  (pos/nearby-chunk-keys x y z search-radius))

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
