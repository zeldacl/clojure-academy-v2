(ns cn.li.ac.test.support.skill-context
  "Owner bindings and in-memory skill-state mocks for ability content unit tests."
  (:require [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
            [cn.li.ac.test.support.owner :as owner-support]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn default-server-owner
  ([]
   (owner-support/default-server-owner))
  ([player-uuid]
   (owner-support/default-server-owner player-uuid)))

(defn with-server-skill-context
  "Bind dispatcher + player-state owners for server-side skill tests."
  ([f]
   (with-server-skill-context "p1" f))
  ([player-uuid f]
   (let [owner (default-server-owner player-uuid)
         hooks-owner {:server-session-id owner-support/default-server-session-id
                      :player-uuid (str player-uuid)}]
     (binding [ctx/*context-owner* owner
               runtime-hooks/*player-state-owner* hooks-owner]
       (f)))))

(defn atom-store
  "Create an atom-backed context store keyed by ctx-id."
  [ctx-id seed]
  (atom {ctx-id seed}))

(defn update-skill-state-root!*
  [store ctx-id f args]
  (swap! store update ctx-id
         (fn [ctx-data]
           (let [current (or (:skill-state ctx-data) {})
                 next-state (if (and (= f identity) (= 1 (count args)))
                              (first args)
                              (apply f current args))]
             (assoc ctx-data :skill-state next-state))))
  nil)

(defn in-memory-skill-mocks
  "Return a map of vars + fns for `with-redefs` (vector form via `flatten-redefs`).

  Options:
  - :store atom from `atom-store`
  - :terminate-calls* optional atom to record terminate calls"
  [{:keys [store terminate-calls*]}]
  (let [get-context (fn
                      ([ctx-id]
                       (get @store ctx-id))
                      ([_owner ctx-id]
                       (get @store ctx-id)))
        assoc-skill-state! (fn [ctx-id k v]
                             (swap! store update ctx-id
                                    (fn [ctx]
                                      (let [path (if (vector? k) k [k])]
                                        (update ctx :skill-state #(assoc-in (or % {}) path v)))))
                             nil)
        update-skill-state-root! (fn [ctx-id f & args]
                                   (update-skill-state-root!* store ctx-id f args))
        clear-skill-state! (fn [ctx-id]
                             (swap! store update ctx-id dissoc :skill-state)
                             nil)
        terminate-context! (fn
                             ([ctx-id _]
                              (when terminate-calls*
                                (swap! terminate-calls* conj ctx-id))
                              nil)
                             ([_owner ctx-id _]
                              (when terminate-calls*
                                (swap! terminate-calls* conj ctx-id))
                              nil))]
    {:get-context get-context
     :assoc-skill-state! assoc-skill-state!
     :update-skill-state-root! update-skill-state-root!
     :clear-skill-state! clear-skill-state!
     :terminate-context! terminate-context!}))

(defn flatten-redefs
  "Expand `in-memory-skill-mocks` map into a `with-redefs` vector."
  [mocks]
  [ctx/get-context (:get-context mocks)
   ctx/terminate-context! (:terminate-context! mocks)
   ctx-skill/assoc-skill-state! (:assoc-skill-state! mocks)
   ctx-skill/update-skill-state-root! (:update-skill-state-root! mocks)
   ctx-skill/clear-skill-state! (:clear-skill-state! mocks)])

(defn content-ctx-mocks
  "Scatter-style in-memory context atom for public skill entrypoint tests.

  The atom holds a single merged context map (not keyed by ctx-id). Returns
  mock fns suitable for vector `with-redefs` alongside `redefs-vector`."
  [seed & {:keys [terminate-calls*]}]
  (let [ctx* (atom seed)
        get-context (fn
                      ([_ctx-id] @ctx*)
                      ([_owner _ctx-id] @ctx*))
        assoc-skill-state! (fn [_ctx-id k v]
                             (swap! ctx*
                                    (fn [ctx-data]
                                      (let [path (if (vector? k) k [k])]
                                        (update ctx-data :skill-state #(assoc-in (or % {}) path v)))))
                             nil)
        update-skill-state-root! (fn [_ctx-id f & args]
                                   (swap! ctx*
                                          (fn [ctx-data]
                                            (let [current (or (:skill-state ctx-data) {})
                                                  next-state (if (and (= f identity) (= 1 (count args)))
                                                               (first args)
                                                               (apply f current args))]
                                              (assoc ctx-data :skill-state next-state))))
                                   nil)
        clear-skill-state! (fn [_ctx-id]
                             (swap! ctx* dissoc :skill-state)
                             nil)
        terminate-context! (fn
                             ([ctx-id _]
                              (when terminate-calls*
                                (swap! terminate-calls* conj ctx-id))
                              nil)
                             ([_owner ctx-id _]
                              (when terminate-calls*
                                (swap! terminate-calls* conj ctx-id))
                              nil))]
    {:ctx* ctx*
     :get-context get-context
     :assoc-skill-state! assoc-skill-state!
     :update-skill-state-root! update-skill-state-root!
     :clear-skill-state! clear-skill-state!
     :terminate-context! terminate-context!}))

(defn redefs-vector
  "Expand `content-ctx-mocks` into a `with-redefs` vector."
  [mocks]
  [ctx/get-context (:get-context mocks)
   ctx/terminate-context! (:terminate-context! mocks)
   ctx-skill/assoc-skill-state! (:assoc-skill-state! mocks)
   ctx-skill/update-skill-state-root! (:update-skill-state-root! mocks)
   ctx-skill/clear-skill-state! (:clear-skill-state! mocks)])

(defn with-in-memory-skill-mocks
  "Bind owners and in-memory ctx/skill-state mocks around `f`."
  [store opts f]
  (let [mocks (in-memory-skill-mocks (assoc opts :store store))]
    (with-server-skill-context
      #(with-redefs (flatten-redefs mocks)
         (f)))))
