(ns cn.li.ac.ability.server.effect.beam
  "Beam shooting effect op shared between railgun, meltdowner, and similar skills.

  The :beam op fires a wide cylindrical beam from the player's eye position.
  Reflection callbacks are passed via the event map (not params) to avoid
  resolve-params calling them prematurely:
    :reflect-can-fn  (fn [uuid] -> bool)   called to check each candidate
    :reflect-shot-fn (fn [uuid] -> bool?)  called when reflection triggers, returns hit?"
  (:require [cn.li.ac.ability.server.effect.core :as effect]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.ability.state.context :as ctx]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.block-manipulation :as block-manip]))

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn- beam-candidates
  "Return candidate entities within the beam cylinder, annotated with
  :forward-dist and :radial-dist, sorted nearest-first."
  [player-id world-id start-pos dir max-distance query-radius radius]
  (when world-effects/*world-effects*
    (let [mid-pos (geom/v+ start-pos (geom/v* dir (/ (double max-distance) 2.0)))]
      (->> (world-effects/find-entities-in-radius world-effects/*world-effects*
                                                   world-id
                                                   (:x mid-pos) (:y mid-pos) (:z mid-pos)
                                                   (double query-radius))
           (remove (fn [{:keys [uuid]}] (= uuid player-id)))
           (map (fn [{:keys [x y z] :as target}]
                  (let [to-target    (geom/v- {:x x :y y :z z} start-pos)
                        forward-dist (geom/vdot to-target dir)
                        closest      (geom/v+ start-pos (geom/v* dir forward-dist))
                        radial-dist  (geom/vdist {:x x :y y :z z} closest)]
                    (assoc target :forward-dist forward-dist :radial-dist radial-dist))))
           (filter (fn [{:keys [forward-dist radial-dist]}]
                     (and (<= 0.0 (double forward-dist) (double max-distance))
                          (<= (double radial-dist) (* (double radius) 1.2)))))
           (sort-by (fn [{:keys [x y z]}]
                      (geom/vdist-sq start-pos {:x x :y y :z z})))
           vec))))

(defn- break-blocks!
  "Advance a cylindrical beam along dir, breaking blocks that can be broken
  within the available energy budget."
  [player-id world-id start-pos dir max-distance energy radius step]
  (when (and block-manip/*block-manipulation* (pos? (double energy)) (pos? (double max-distance)))
    (let [[right up]      (geom/orthonormal-basis dir)
          processed       (atom #{})
          sample-points   (transient [])]
      (doseq [s (range (- (double radius)) (+ (double radius) (double step)) (double step))
              t (range (- (double radius)) (+ (double radius) (double step)) (double step))]
        (when (<= (+ (* (double s) (double s)) (* (double t) (double t)))
                  (* (double radius) (double radius)))
          (let [offset (geom/v+ (geom/v* right s) (geom/v* up t))
                origin (geom/v+ start-pos offset)
                key    [(geom/floor-int (:x origin))
                        (geom/floor-int (:y origin))
                        (geom/floor-int (:z origin))]]
            (when-not (contains? @processed key)
              (swap! processed conj key)
              (conj! sample-points origin)))))
      (let [origins         (persistent! sample-points)
            line-energy     (if (seq origins)
                              (/ (double energy) (double (count origins)))
                              0.0)
            neighbor-offsets [[1 0 0] [-1 0 0] [0 1 0] [0 -1 0] [0 0 1] [0 0 -1]]]
        (doseq [origin origins]
          (loop [travel    0.0
                 remaining (* line-energy (+ 0.95 (* 0.1 (rand))))]
            (when (and (<= travel (double max-distance)) (pos? remaining))
              (let [pos      (geom/v+ origin (geom/v* dir travel))
                    bx       (geom/floor-int (:x pos))
                    by       (geom/floor-int (:y pos))
                    bz       (geom/floor-int (:z pos))
                    hardness (block-manip/get-block-hardness block-manip/*block-manipulation*
                                                             world-id bx by bz)]
                (if (or (nil? hardness) (neg? (double hardness)))
                  (recur (+ travel 1.0) remaining)
                  (if (and (pos? (double hardness))
                           (<= (double hardness) remaining)
                           (block-manip/can-break-block? block-manip/*block-manipulation*
                                                         player-id world-id bx by bz))
                    (do
                      (block-manip/break-block! block-manip/*block-manipulation*
                                                player-id world-id bx by bz (< (rand) 0.05))
                      (when (< (rand) 0.05)
                        (let [[ox oy oz] (rand-nth neighbor-offsets)
                              nx         (+ bx (int ox))
                              ny         (+ by (int oy))
                              nz         (+ bz (int oz))]
                          (when (block-manip/can-break-block? block-manip/*block-manipulation*
                                                              player-id world-id nx ny nz)
                            (block-manip/break-block! block-manip/*block-manipulation*
                                                      player-id world-id nx ny nz false))))
                      (recur (+ travel 1.0) (- remaining (double hardness))))
                    (recur (+ travel 1.0) 0.0)))))))))))

;; ---------------------------------------------------------------------------
;; :beam effect op
;; ---------------------------------------------------------------------------

(effect/defop :beam
  "Fire a cylindrical beam from the player's eye position.

  Required in evt (set by :aim-raycast or manually):
    :player-id  :world-id  :eye-pos  :look-dir

  Optional in evt (reflection callbacks; passed via evt to avoid resolve-params):
    :reflect-can-fn   (fn [uuid] -> bool)  returns true when this entity can reflect
    :reflect-shot-fn  (fn [uuid] -> bool?) fires reflection, returns truthy if hit

  Params (all optional, use defaults shown):
    :radius           2.0    beam cylinder half-width
    :query-radius     30.0   entity search radius around beam midpoint
    :step             0.9    grid sampling step for block-break pass
    :max-distance     50.0   hard beam distance cap
    :visual-distance  45.0   FX beam end cap (slightly shorter than damage range)
    :damage           0.0    base entity damage
    :damage-type      :magic
    :block-energy     0.0    energy budget for block breaking (0 = disabled)
    :break-blocks?    false  enable block-breaking pass
    :fx-topic         nil    keyword topic for :railgun/fx-shot style FX; nil = no FX

  Result: evt with :beam-result {:performed? :reflection-hit? :normal-hit-count :visual-distance}"
  [evt {:keys [radius query-radius step max-distance visual-distance
               damage damage-type block-energy break-blocks? fx-topic]}]
  (let [player-id     (:player-id evt)
        world-id      (:world-id evt)
        eye           (or (:eye-pos evt) (geom/eye-pos player-id))
        look          (:look-dir evt)
        reflect-can?  (:reflect-can-fn evt)
        reflect-shot! (:reflect-shot-fn evt)]
    (if-not (and look (:dx look))
      (assoc evt :beam-result {:performed? false})
      (let [dir      (geom/vnorm {:x (double (or (:dx look) 0.0))
                                  :y (double (or (:dy look) 0.0))
                                  :z (double (or (:dz look) 1.0))})
            r        (double (or radius 2.0))
            qr       (double (or query-radius 30.0))
            st       (double (or step 0.9))
            md       (double (or max-distance 50.0))
            vd       (double (or visual-distance 45.0))
            dmg      (double (or damage 0.0))
            benergy  (double (or block-energy 0.0))
            candidates (beam-candidates player-id world-id eye dir md qr r)
            step-result
            (fn [{:keys [stop? reflection-distance reflection-hit? normal-hit-count] :as acc}
                 {:keys [uuid x y z radial-dist]}]
              (if stop?
                (reduced acc)
                (if (and reflect-can? (reflect-can? uuid))
                  (let [dist     (geom/vdist eye {:x x :y y :z z})
                        hit?     (boolean (and reflect-shot! (reflect-shot! uuid)))]
                    (reduced {:stop?               true
                              :reflection-distance dist
                              :reflection-hit?     hit?
                              :normal-hit-count    normal-hit-count}))
                  (do
                    (when (and (pos? dmg) entity-damage/*entity-damage*)
                      (let [radial (double radial-dist)
                            factor (+ 0.2 (* 0.8 (- 1.0 (/ (min md (max 0.0 radial)) md))))]
                        (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                                            world-id uuid
                                                            (* dmg factor)
                                                            (or damage-type :magic))))
                    {:stop?               false
                     :reflection-distance reflection-distance
                     :reflection-hit?     reflection-hit?
                     :normal-hit-count    (inc (long (or normal-hit-count 0)))}))))
            result       (reduce step-result
                                 {:stop? false :reflection-distance nil
                                  :reflection-hit? false :normal-hit-count 0}
                                 candidates)
            block-dist   (min md (double (or (:reflection-distance result) md)))
            visual-dist  (min vd (double (or (:reflection-distance result) vd)))
            end-pos      (geom/v+ eye (geom/v* dir visual-dist))]
        (when (and break-blocks? (pos? benergy))
          (break-blocks! player-id world-id eye dir block-dist benergy r st))
        (when fx-topic
          (ctx/ctx-send-to-client! (:ctx-id evt) fx-topic
                                   {:mode         :perform
                                    :start        eye
                                    :end          end-pos
                                    :hit-distance visual-dist}))
        (assoc evt :beam-result {:performed?       true
                                 :reflection-hit?  (:reflection-hit? result)
                                 :normal-hit-count (:normal-hit-count result)
                                 :visual-distance  visual-dist})))))
