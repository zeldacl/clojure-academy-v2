(ns cn.li.ability.compose
  "Pure composition boundary for Combat, VFX and Presentation.

   A content pack supplies combat outcomes and VFX descriptors. This namespace
   is the only place that combines those values into a frame/result envelope;
   it never calls Minecraft or owns process-global state."
  (:require [cn.li.presentation.core.frame :as presentation-frame]))

(defn normalize-scope
  [scope]
  (merge {:server-epoch 0 :world-epoch 0 :catalog-generation 0}
         (select-keys (or scope {}) [:server-epoch :world-epoch
                                      :catalog-generation :player-id
                                      :max-render-commands-per-frame])))

(defn compose-catalog
  "Create the immutable cross-core bundle consumed by a content pack.
   Combat, VFX and Presentation are values at this boundary; no content-pack
   policy or host adapter is allowed to mutate or reinterpret them here."
  [content-id node-environment combat vfx]
  (when-not (keyword? content-id)
    (throw (ex-info "catalog content id must be a keyword"
                    {:content-id content-id})))
  (when-not (map? node-environment)
    (throw (ex-info "catalog requires a node environment" {})))
  (when-not (map? combat)
    (throw (ex-info "catalog requires combat content" {})))
  (when-not (map? vfx)
    (throw (ex-info "catalog requires vfx content" {})))
  {:content-id content-id
   :node-environment node-environment
   :combat combat
   :vfx vfx})

(defn route-intent
  "Resolve an intent's recipient without allowing one player to mutate another
   player's state. :self is owner-only; :tracking/:world are explicit server
   broadcasts and are still filtered by the host adapter."
  [scope intent]
  (let [owner (:owner intent)
        audience (:audience intent)]
    (case (:type audience)
      :owner {:kind :player :players [owner]}
      :nearby {:kind :tracking :center owner :radius (double (or (:radius audience) 96.0))}
      :world {:kind :world}
      {:kind :tracking :center owner :radius 96.0})))

(defn compose-result
  [scope combat-result vfx-intents]
  {:scope (normalize-scope scope)
   :owner (:owner combat-result)
   :result (dissoc combat-result :vfx-signals)
   :vfx-intents (vec (map #(assoc % :route (route-intent scope %)) vfx-intents))})

(defn compose-frame
  "Materialize a bounded immutable Presentation frame at the composition
   boundary. Presentation contributors remain pure; only the host adapter
   turns commands into engine-specific objects."
  [scope contributors context]
  (let [scope (normalize-scope scope)
        limit (long (or (:max-render-commands-per-frame scope) 8192))]
    (presentation-frame/frame contributors
                                 (merge scope (or context {}))
                                 limit)))

(defn contributor
  "Expose the composition-layer contributor constructor so content adapters
   do not depend on Presentation Core's implementation namespace directly."
  [id render-fn]
  (presentation-frame/contributor id render-fn))

(defn reduce-player
  "Activation-local reducer. The mutable shell may store the returned value in
   a player shard; no server-global atom is required."
  [state event]
  (let [player (:player-id event)]
    (when-not (= player (:player-id state))
      (throw (ex-info "cross-player state mutation" {:state-player (:player-id state)
                                                      :event-player player})))
    (case (:op event)
      :activate (-> state (update :active-sessions (fnil conj #{}) (:session-id event))
                    (update :last-seq (fnil max -1) (long (:sequence event))))
      :complete (-> state (update :active-sessions disj (:session-id event))
                    (update :last-seq (fnil max -1) (long (:sequence event))))
      state)))
