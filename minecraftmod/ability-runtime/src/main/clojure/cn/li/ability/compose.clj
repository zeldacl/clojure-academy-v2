(ns cn.li.ability.compose
  "Pure composition boundary for Combat, VFX and Presentation.

   A content pack supplies combat outcomes and VFX descriptors. This namespace
   is the only place that combines those values into a frame/result envelope;
   it never calls Minecraft or owns process-global state.")

(defn normalize-scope
  [scope]
  (merge {:server-epoch 0 :world-epoch 0 :catalog-generation 0}
         (select-keys (or scope {}) [:server-epoch :world-epoch
                                      :catalog-generation :player-id])))

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
