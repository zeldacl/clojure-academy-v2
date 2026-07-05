(ns cn.li.ac.content.ability.meltdowner.jet-engine
  "JetEngine parity rewrite.

  Server flow:
  - key down: enter marking phase
  - key tick: keep target marker synced while checking CP affordability
  - key up: settle resource once (cp+overload), then enter triggering phase
  - triggering ticks: move player toward target, apply segment hit once per target
  - terminate after trigger lifetime or abort/failure

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill def-skill-config-ops]]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
                        [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as md-damage]
                        [cn.li.mcmod.platform.player-motion :as player-motion]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.teleportation :as teleportation]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.util.log :as log]))

(def-skill-config-ops :jet-engine)
(def ^:private jet-engine-skill-id :jet-engine)

; Phase timing/range stay as implementation constants; balance-facing values use skill-config below.
(def ^:private jet-target-range 12.0)
(def ^:private trigger-time-ticks 8)
(def ^:private trigger-lifetime-ticks 15)
(def ^:private min-segment-distance 1.0e-5)

(defn- release-cp-cost [player-id]
  (cfg-lerp :cost.down.cp (skill-exp player-id)))

(defn- release-overload-cost [player-id]
  (cfg-lerp :cost.down.overload (skill-exp player-id)))

(defn- damage-amount [player-id]
  (cfg-lerp :combat.damage (skill-exp player-id)))

(defn- cooldown-ticks [player-id]
  (cfg-lerp-int :cooldown.ticks (skill-exp player-id)))

(defn- eye-pos-safe [player-id]
  (or (geom/eye-pos player-id)
      {:x 0.0 :y 64.0 :z 0.0}))

(defn- lerp-pos [a b t]
  (let [clamped (max 0.0 (min 1.0 (double t)))]
    (geom/v+ a (geom/v* (geom/v- b a) clamped))))

(defn- get-target-pos [player-id]
  (let [world-id (geom/world-id-of player-id)
        eye (eye-pos-safe player-id)
        look (when (raycast/available?)
               (raycast/get-player-look-vector* player-id))]
    (when look
      (let [dir (geom/vnorm look)
            block-hit (when (raycast/available?)
                        (raycast/raycast-blocks*
                                                world-id
                                                (:x eye) (:y eye) (:z eye)
                                                (:x dir) (:y dir) (:z dir)
                                                jet-target-range))]
        (if block-hit
          {:x (double (:x block-hit))
           :y (double (:y block-hit))
           :z (double (:z block-hit))}
          (geom/v+ eye (geom/v* dir jet-target-range)))))))

(defn- get-player-pos [player-id]
  (or (when (teleportation/available?)
        (teleportation/get-player-position* player-id))
      (eye-pos-safe player-id)))

(defn- update-skill-state-root!
  [ctx-id f & args]
  (apply ctx-skill/update-skill-state-root! ctx-id f args))

(defn- can-afford-release? [player-id]
  (let [cp-needed (release-cp-cost player-id)
        overload-needed (release-overload-cost player-id)
        cur-cp (skill-effects/current-cp player-id)
        cur-overload (double (or (skill-effects/player-path player-id [:resource-data :cur-overload] 0.0) 0.0))
        max-overload (double (or (skill-effects/player-path player-id [:resource-data :max-overload] 0.0) 0.0))]
    (and (>= cur-cp cp-needed)
         (<= (+ cur-overload overload-needed) max-overload))))

(defn- send-mark-start! [ctx-id target]
  (fx/send! ctx-id {:topic :jet-engine/fx-start :mode :mark-start} nil {:target target}))

(defn- send-mark-update! [ctx-id target hold-ticks]
  (fx/send! ctx-id {:topic :jet-engine/fx-update :mode :mark-update} nil
            {:target target
             :hold-ticks (long hold-ticks)}))

(defn- send-mark-end! [ctx-id target]
  (fx/send! ctx-id {:topic :jet-engine/fx-end :mode :mark-end} nil {:target target}))

(defn- send-trigger-start! [ctx-id start target velocity]
  (fx/send! ctx-id {:topic :jet-engine/fx-trigger-start :mode :trigger-start} nil {:start start
                            :target target
                            :velocity velocity}))

(defn- send-trigger-update! [ctx-id pos trigger-ticks]
  (fx/send! ctx-id {:topic :jet-engine/fx-trigger-update :mode :trigger-update} nil {:pos pos
                            :trigger-ticks trigger-ticks}))

(defn- send-trigger-end! [ctx-id]
  (fx/send! ctx-id {:topic :jet-engine/fx-trigger-end :mode :trigger-end}))

(defn- mark-hit-and-damage!
  [player-id ctx-id world-id hit hit-uuids]
  (let [target-id (str (:uuid hit))
        self-id (str player-id)]
    (if (or (empty? target-id)
            (= target-id self-id)
            (contains? hit-uuids target-id))
      hit-uuids
      (do
        (when (entity-damage/available?)
          (entity-damage/apply-direct-damage!*
            world-id
            target-id
            (damage-amount player-id)
            :magic))
        (md-damage/mark-target! player-id target-id
              {:ctx-id ctx-id
               :target-pos {:x (:x hit)
                      :y (:y hit)
                      :z (:z hit)}})
        (conj hit-uuids target-id)))))

(defn- tick-triggering!
  [{:keys [player-id ctx-id]}]
  (let [ctx-data (ctx-skill/get-context ctx-id)
        st (:skill-state ctx-data)
        trigger-ticks (long (or (:trigger-ticks st) 0))
        start-pos (:start-pos st)
        target-pos (:target-pos st)
        world-id (or (:world-id st) (geom/world-id-of player-id))
        hit-uuids (or (:hit-uuids st) #{})]
    (if (or (nil? start-pos) (nil? target-pos) (>= trigger-ticks trigger-lifetime-ticks))
      (do
        (send-trigger-end! ctx-id)
        (send-mark-end! ctx-id target-pos)
        (ctx/terminate-context! ctx-id nil))
      (let [next-tick (inc trigger-ticks)
            t (/ (double next-tick) (double trigger-time-ticks))
            next-pos (lerp-pos start-pos target-pos t)
            prev-pos (or (:last-pos st) start-pos)
        segment (geom/v- next-pos prev-pos)
        segment-distance (geom/vlen segment)
        segment-dir (geom/vnorm segment)]
        (when (zero? trigger-ticks)
          (when (player-motion/available?)
            (player-motion/dismount-riding!* player-id)))
        (when (teleportation/available?)
          (teleportation/teleport-player!* player-id
                                         world-id
                                         (:x next-pos)
                                         (:y next-pos)
                                         (:z next-pos))
          (teleportation/reset-fall-damage!* player-id))
        (when (and (player-motion/available?)
                   (:velocity st))
          (let [{:keys [x y z]} (:velocity st)]
            (player-motion/set-velocity!* player-id x y z)))
        (let [hit (when (and (raycast/available?)
                             (> segment-distance min-segment-distance))
                    (raycast/raycast-entities*
                                              world-id
                                              (:x prev-pos) (:y prev-pos) (:z prev-pos)
                                              (:x segment-dir) (:y segment-dir) (:z segment-dir)
                                              segment-distance))
              next-hit-uuids (if hit
                              (mark-hit-and-damage! player-id ctx-id world-id hit hit-uuids)
                               hit-uuids)]
          (update-skill-state-root! ctx-id merge
                                    {:trigger-ticks next-tick
                                     :last-pos next-pos
                                     :hit-uuids next-hit-uuids})
          (send-trigger-update! ctx-id next-pos next-tick))))))

(defn jet-engine-down!
  [{:keys [ctx-id player-id cost-ok?]}]
  (when cost-ok?
    (let [target (get-target-pos player-id)]
      (ctx-skill/replace-skill-state! ctx-id {:phase :marking
                                     :hold-ticks 0
                                     :target-pos target
                                     :trigger-ticks 0
                                     :hit-uuids #{}})
      (send-mark-start! ctx-id target))))

(defn jet-engine-tick!
  [{:keys [ctx-id player-id hold-ticks]}]
  (let [st (get-in (ctx-skill/get-context ctx-id) [:skill-state])
        phase (:phase st)]
    (case phase
      :triggering
      (tick-triggering! {:player-id player-id :ctx-id ctx-id})

      :marking
      (if-not (can-afford-release? player-id)
        (do
          (send-mark-end! ctx-id (:target-pos st))
          (ctx/terminate-context! ctx-id nil))
        (let [target (or (get-target-pos player-id) (:target-pos st))]
          (update-skill-state-root! ctx-id merge
                                    {:hold-ticks (long hold-ticks)
                                     :target-pos target})
          (send-mark-update! ctx-id target hold-ticks)))

      nil)))

(defn jet-engine-up!
  [{:keys [player-id ctx-id]}]
  (try
    (let [st (get-in (ctx-skill/get-context ctx-id) [:skill-state])
          phase (:phase st)
          target-pos (or (:target-pos st) (get-target-pos player-id))]
      (if (not= phase :marking)
        nil
        (let [cp-needed (release-cp-cost player-id)
              overload-needed (release-overload-cost player-id)
              paid? (:success?
                     (skill-effects/perform-resource!
                       player-id
                       overload-needed
                       cp-needed
                       false))]
          (if-not (and paid? target-pos)
            (do
              (send-mark-end! ctx-id target-pos)
              (ctx/terminate-context! ctx-id nil))
            (let [start-pos (get-player-pos player-id)
                  travel (geom/v- target-pos start-pos)
                  velocity (geom/v* travel (/ 1.0 (double trigger-time-ticks)))]
              (update-skill-state-root! ctx-id merge
                                        {:phase :triggering
                                         :start-pos start-pos
                                         :target-pos target-pos
                                         :last-pos start-pos
                                         :velocity velocity
                                         :world-id (geom/world-id-of player-id)
                                         :trigger-ticks 0
                                         :hit-uuids #{}})
              (send-mark-end! ctx-id target-pos)
              (send-trigger-start! ctx-id start-pos target-pos velocity)
              (skill-effects/add-skill-exp! player-id jet-engine-skill-id (cfg-double :progression.exp-use))
              (skill-effects/set-main-cooldown! player-id jet-engine-skill-id (cooldown-ticks player-id)))))))
    (catch Exception e
      (log/warn "JetEngine up! failed" (ex-message e))
      (send-mark-end! ctx-id nil)
      (ctx/terminate-context! ctx-id nil))))

(defn jet-engine-abort!
  [{:keys [ctx-id]}]
  (let [st (get-in (ctx-skill/get-context ctx-id) [:skill-state])]
    (send-trigger-end! ctx-id)
    (send-mark-end! ctx-id (:target-pos st))))

(defskill jet-engine
  :id :jet-engine
  :category-id :meltdowner
  :name-key "ability.skill.meltdowner.jet_engine"
  :description-key "ability.skill.meltdowner.jet_engine.desc"
  :icon "textures/abilities/meltdowner/skills/jet_engine.png"
  :ui-position [170 32]
  :level 4
  :controllable? true
  :ctrl-id :jet-engine
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :pattern :release-cast
  :cooldown {:mode :manual}
  :input-policy {:terminate-on-key-up? false
                 :keep-active-on-key-up? true
                 :settle-perform-on-key-up? false}
  :actions {:down! jet-engine-down!
            :tick! jet-engine-tick!
            :up! jet-engine-up!
            :abort! jet-engine-abort!}
  :prerequisites [{:skill-id :meltdowner :min-exp 1.0}])

