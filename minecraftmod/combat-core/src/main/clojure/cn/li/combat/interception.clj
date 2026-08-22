(ns cn.li.combat.interception
  "Authoritative damage-interception boundary: decides the neutral outcome
   of ANY incoming damage -- not only ability-cast damage, vanilla hits too
   -- by running it through the same declarative :reactions every EDN
   ability's own :program does. This is the single place that call happens;
   platform adapters report only the raw hit fact (who hit whom, how much,
   what damage type) and commit whatever comes back, exactly like an
   ability's own dispatch result.

   Combat Core reaches the platform directly through mcmod (raycast/entity-
   motion queries, the :entity/damage capability for reflected/reaction
   damage) -- there is no reason for AC to sit in the middle relaying those
   calls. What genuinely cannot move here is AC's own persistent player-
   state store: :reactions/:session-fn/:state-fn/:domain-state/:tunables-fn
   are injected because Combat Core does not own that store, the same
   injection shape combat-reactions/apply! already required before this
   boundary existed in AC."
  (:require [cn.li.combat.reactions :as reactions]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity-motion :as entity-motion]
            [cn.li.mcmod.runtime.capabilities :as capabilities]))

(defn- horizontal-yaw-degrees [x z]
  (- (Math/toDegrees (Math/atan2 (double x) (double z)))))

(defn attacker-front?
  "Whether attacker-id is within front-cone-degrees of target-id's facing.
   Missing entity geometry fails closed. A damage-source map may already
   carry a validated neutral :attacker-front? fact (tests, special damage
   types); ordinary entity damage is resolved from raycast/entity-motion."
  [target-id attacker-id damage-source front-cone-degrees]
  (cond
    (and (map? damage-source) (contains? damage-source :attacker-front?))
    (boolean (:attacker-front? damage-source))

    (nil? attacker-id) true

    (not (and (raycast/available?) (entity-motion/available?)))
    false

    :else
    (try
      (let [position (raycast/player-position (str target-id))
            look (raycast/player-look-vector (str target-id))
            world-id (:world-id position)
            attacker-pos (entity-motion/entity-position world-id (str attacker-id))]
        (boolean
         (when (and (map? position) (map? look) (map? attacker-pos))
           (let [dx (- (double (:x attacker-pos)) (double (:x position)))
                 dz (- (double (:z attacker-pos)) (double (:z position)))
                 player-yaw (horizontal-yaw-degrees (:x look) (:z look))
                 target-yaw (horizontal-yaw-degrees dx dz)
                 diff (mod (Math/abs (double (- target-yaw player-yaw))) 360.0)]
             (< diff (double front-cone-degrees))))))
      (catch Exception _ false))))

(defn- valid-damage-world-effect?
  "A malformed or out-of-bound reaction-produced damage effect must never
   reach the platform: no target, self-damage, non-finite, or absurd (a
   compromised/misauthored EDN reaction must fail closed here, not apply
   whatever it computed)."
  [effect]
  (let [request (:request effect)
        base (:base request)]
    (and (= :damage (:type effect))
         (:source request) (:target request)
         (not= (str (:source request)) (str (:target request)))
         (number? base) (Double/isFinite (double base))
         (pos? (double base)) (<= (double base) 10000.0))))

(defn- apply-reaction-world-effects!
  "Invoke the registered :entity/damage capability for every valid reaction-
   produced damage effect -- the exact same capability handler every EDN
   ability's own :combat/damage component already calls, never a second raw
   platform port. All-or-nothing: if any effect in the batch fails
   validation, none are applied."
  [world-effects]
  (let [effects (vec (filter valid-damage-world-effect? world-effects))]
    (when (and (seq effects) (= (count effects) (count world-effects)))
      (when-let [handler (get (:actions (capabilities/snapshot)) :entity/damage)]
        (doseq [{:keys [request]} effects]
          (let [{:keys [world-id target base type source]} request]
            (handler {:world-id world-id :target target :amount base
                      :damage-type type :owner source})))))
    effects))

(defn intercept!
  "Run one incoming hit through the declarative reaction pipeline and, for
   any reaction-produced damage, apply it immediately through the
   registered :entity/damage capability.

   Returns the neutral request combat-reactions/apply! produced
   (:cancelled?, :base, :state-patch, :source-state-patch, :session-patch,
   :vfx-signals, :events) -- the caller's only remaining job is committing
   those to its own player-state store and delivering the vfx-signals; it
   never re-derives or reapplies the decision."
  [{:keys [target-id attacker-id base damage-type damage-source
           reactions session-fn state-fn domain-state tunables-fn
           now-tick front-cone-degrees precheck?]}]
  (let [world-id (or (:world-id damage-source)
                     (when (raycast/available?)
                       (some-> (raycast/player-position (str target-id)) :world-id)))
        target-position (when (and world-id (entity-motion/available?))
                          (entity-motion/entity-position world-id (str target-id)))
        request {:source (or attacker-id :environment)
                 :target target-id
                 :base (double base)
                 :type (or damage-type :generic)
                 :components {:direct (double base)}
                 :tags (if precheck? #{:combat :attack-precheck} #{:combat :intercepted})
                 :metadata {:damage-source damage-source
                            :world-id world-id
                            :target-position (when (map? target-position)
                                               [(double (or (:x target-position) 0.0))
                                                (double (or (:y target-position) 0.0))
                                                (double (or (:z target-position) 0.0))])
                            :activation-seed (hash [attacker-id target-id now-tick
                                                    base damage-type])
                            :attacker-front? (attacker-front?
                                              target-id attacker-id damage-source
                                              (or front-cone-degrees 180.0))}}
        result (reactions/apply! request
                                 {:reactions reactions
                                  :session-fn session-fn
                                  :state-fn state-fn
                                  :domain-state domain-state
                                  :precheck? precheck?
                                  :tunables-fn tunables-fn})
        applied (when (and (not (:cancelled? result)) (seq (:world-effects result)))
                 (apply-reaction-world-effects! (:world-effects result)))]
    ;; The caller (AC) needs to know whether reaction damage actually landed
    ;; -- e.g. to decide whether to cancel the native hit it intercepted --
    ;; without re-deriving valid-damage-world-effect? itself.
    (assoc result :reaction-damage-applied? (boolean (seq applied)))))
