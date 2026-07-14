(ns cn.li.ac.ability.service.radiation-mark-index
  "Derived read index for meltdowner radiation marks.

  Authoritative data lives in per-player state
  ([:runtime :meltdowner :radiation-marks]); this index is a derived cache
  maintained via :radiation-index-sync effects emitted by the reducer
  (full replacement per source-player on every mark/clear/tick command).

  Tests must not seed radiation-marks via seed-player-state!/reset-store!
  directly — always go through commands so the index stays in sync."
  (:require [cn.li.mcmod.framework :as fw]))

(def ^:private index-path [:service :radiation-mark-index])

(defn- index-atom
  ^clojure.lang.IAtom []
  (if-let [fw-atom (fw/fw-atom)]
    (or (get-in @fw-atom index-path)
        (get-in (swap! fw-atom update-in index-path #(or % (atom {})))
                index-path))
    (atom {})))

;; @index shape:
;; {server-session-id
;;  {:by-source {"caster-uuid" {"target-id" mark}}    ; mirrors each holder's
;;                                                     ; authoritative marks map
;;   :by-target {"target-id" {"caster-uuid" mark}}}}  ; reverse index for O(1)
;;                                                     ; per-target lookup

(defn apply-source-marks
  "Pure: replace source-id's entries inside one session index.
  Removes stale by-target reverse-entries and adds fresh ones; drops empty
  maps entirely rather than leaving empty-map residue."
  [session-idx source-id new-marks]
  (let [old-marks (get-in session-idx [:by-source source-id] {})
        removed (remove #(contains? new-marks %) (keys old-marks))
        idx (reduce (fn [i t]
                      (let [m (not-empty (dissoc (get-in i [:by-target t]) source-id))]
                        (if m
                          (assoc-in i [:by-target t] m)
                          (update i :by-target #(not-empty (dissoc % t))))))
                    session-idx removed)
        idx (reduce-kv (fn [i t mark] (assoc-in i [:by-target t source-id] mark))
                        idx new-marks)]
    (if (seq new-marks)
      (assoc-in idx [:by-source source-id] new-marks)
      (update idx :by-source #(not-empty (dissoc % source-id))))))

(defn sync-source-marks!
  "Idempotent full replacement of one player's mark entries in the index."
  [session-id source-id marks]
  (swap! (index-atom)
         (fn [top]
           (let [nxt (apply-source-marks (get top session-id {}) (str source-id) (or marks {}))]
             (if (or (:by-source nxt) (:by-target nxt))
               (assoc top session-id nxt)
               (dissoc top session-id)))))
  nil)

(defn execute-index-sync!
  "Interpreter handler entry point for the :radiation-index-sync effect."
  [session-id {:keys [player-uuid marks]}]
  (sync-source-marks! session-id player-uuid marks))

(defn mark-holders
  "Player-uuid strings that currently hold at least one outgoing mark."
  [session-id]
  (keys (get-in @(index-atom) [session-id :by-source])))

(defn sources-for-target
  [session-id target-id]
  (keys (get-in @(index-atom) [session-id :by-target (str target-id)])))

(defn strongest-mark-for-target
  "Deterministic: the mark with the greatest :ticks-left among all sources
  currently marking target-id (matches mark-target!'s refresh-to-max semantics)."
  [session-id target-id]
  (when-let [by-src (not-empty (get-in @(index-atom) [session-id :by-target (str target-id)]))]
    (apply max-key #(long (or (:ticks-left %) 0)) (vals by-src))))

(defn snapshot-by-target
  [session-id]
  (reduce-kv (fn [acc t by-src]
               (assoc acc t (apply max-key #(long (or (:ticks-left %) 0)) (vals by-src))))
             {}
             (get-in @(index-atom) [session-id :by-target] {})))

(defn clear-session!
  [session-id]
  (swap! (index-atom) dissoc session-id)
  nil)

(defn reset-for-test!
  []
  (reset! (index-atom) {})
  nil)
