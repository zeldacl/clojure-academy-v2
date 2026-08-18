(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.mag-movement
  (:require [cn.li.ac.ability.client.arc-patterns :as arc-patterns]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.ability.client.effects.rv3 :as vec3]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.ac.ability.client.fx-templates.arc-beam]))

(def ^:private loop-sound (modid/namespaced-path "em.move_loop"))
(defn- loop-sound-key [ctx-id] (str "mag-movement/" ctx-id))

(defn- start-loop-sound! [ctx-id source-player-id]
  ;; Original c_startEffect: FollowEntitySound(player, SOUND).setLoop(), stopped
  ;; in c_endEffect. Queuing em.move_loop as one-shots instead left the last
  ;; sample playing past the end of the skill with no handle to stop it, and it
  ;; is a loop sample — so it kept going long after the arc was gone.
  (try
    (client-bridge/run-client-effect!
     :mcmod/start-loop-sound-at-player
     {:key (loop-sound-key ctx-id)
      :sound-id loop-sound
      :owner-uuid (str source-player-id)
      :volume 0.58
      :pitch 1.0})
    (catch Throwable _ nil)))

(defn- stop-loop-sound! [ctx-id]
  (try
    (client-bridge/run-client-effect!
     :mcmod/stop-loop-sound
     {:key (loop-sound-key ctx-id)})
    (catch Throwable _ nil)))

;; vfx-core :transient migration (docs/04-systems/COMBAT_VFX_PLATFORM_GAPS.md
;; E section): one real vfx-core instance per (owner, activation) -- state is
;; this cast's own map directly, no owner-key wrapping. combat_content.clj's
;; :mag-movement only ever sends :vfx :event :update, every pulse, for as
;; long as the toggle is held -- no :start (the :update handler below
;; already self-initializes on the first call, matching that reality) and,
;; notably, no :end/:abort :vfx step exists at all for this ability. That
;; means :active? never actually flips false today (:end is dead code, same
;; as before this migration) and the instance stays alive until the owner
;; disconnects (clear-owner!) -- not something this migration changes, just
;; carrying forward the pre-existing gap. What the migration does fix: the
;; loop sound now reliably stops on that eventual disconnect-triggered
;; teardown too, via :destroy-fn below, where before clear-owner-fn had no
;; live caller at all (see the vfx-core destroy! commit for why).
(defn- enqueue-state!
  [store ctx-id channel _owner-key payload]
  (let [store* (or store {})
        {:keys [mode target source-player-id world-id]} (or payload {})
        base-meta {:queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (do
        (start-loop-sound! ctx-id source-player-id)
        (merge store* base-meta {:active? true :target target :ticks 0}))
      :update
      (if (:active? store*)
        (merge store* base-meta {:target target})
        (do
          (start-loop-sound! ctx-id source-player-id)
          (merge store* base-meta {:active? true :target target :ticks 0})))
      :end
      (do
        (stop-loop-sound! ctx-id)
        (assoc store* :active? false))
      store*)))

(defn- tick-state!
  "Returning nil ends the instance -- see vfx-core/runtime.clj's
   tick-instance. Only reachable once :active? goes false, which (per the
   comment above) never actually happens via current combat-core content."
  [store]
  (let [state* (or store {})]
    (when (:active? state*)
      ;; The charge loop is one continuous FollowEntitySound started on
      ;; :start/the first :update and stopped on :end — not a re-queued
      ;; one-shot.
      (assoc state* :ticks (inc (long (or (:ticks state*) 0)))))))

(defn- destroy-fx!
  "vfx-core's :destroy hook: release the loop sound on any teardown path
   (explicit :destroy signal, clear-owner!/clear-world!, or :update itself
   returning nil) that isn't the normal :end case above, which already
   stopped it."
  [state]
  (when (:active? state)
    (stop-loop-sound! (:ctx-id state))))

(def ^:private mag-movement-pattern
  (arc-patterns/get-pattern :thin-continuous))

(defn- build-plan
  "Continuously-guided beam (hand-position -> live target): the zigzag path
  is re-derived every frame from the two live endpoints — unlike a fire-and-
  forget arc it has no fixed lifetime to precompute vertices once for, so
  only the per-arc constants (pattern lookup, wiggle phase/amplitude) are
  hoisted out of the segment loop, matching build-arc-plan's per-call cost
  shape in arc_beam.clj."
  [camera-pos hand-center-pos tick]
  ;; One vfx-core instance now exists per active caster; this still only
  ;; draws the LOCAL player's own beam, because hand-center-pos is only ever
  ;; the local viewer's own hand position -- there is no remote-player hand
  ;; endpoint to draw a beam from, so any other player's mag-movement
  ;; instance must be skipped here on every sample, not just once globally.
  (let [mag-move (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :mag-movement)
        mag-move (when (and (:active? mag-move)
                             (or (nil? (:source-player-id mag-move))
                                 (nil? (:player-uuid hand-center-pos))
                                 (= (str (:source-player-id mag-move))
                                    (str (:player-uuid hand-center-pos)))))
                   mag-move)]
    (when (and hand-center-pos
               (:active? mag-move)
               (map? (:target mag-move)))
      (let [hand-v (vec3/map->v3 (dissoc hand-center-pos :player-uuid))
            target-v (vec3/map->v3 (:target mag-move))
            vertices (arc-patterns/generate-zigzag-segments hand-v target-v mag-movement-pattern)]
        {:ops (vec (ru/zigzag-arc-ops (vec3/map->v3 camera-pos) vertices mag-movement-pattern
                                      ;; life-fade-alpha fades OUT over the last
                                      ;; 20% of life, so life-ratio 1.0 ("about to
                                      ;; die") multiplies every colour by alpha 0
                                      ;; — the arc was emitted fully transparent
                                      ;; every frame. This arc is upstream's
                                      ;; thinContiniousArc: it lives as long as the
                                      ;; skill and never fades, so it sits in the
                                      ;; curve's flat full-brightness middle.
                                      {:life-ratio 0.5
                                       :wiggle-phase (arc-patterns/wiggle-phase)
                                       :effective-wiggle (arc-patterns/effective-wiggle-amount mag-movement-pattern 0.5)}))}))))

(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:mag-movement :level] [_ _] {})
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:mag-movement :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:mag-movement :level] [_ _ store] (tick-state! store))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-build-plan :mag-movement
  [_effect-id camera-pos hand-center-pos tick & _more]
  (build-plan camera-pos hand-center-pos tick))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-destroy! :mag-movement [_ state] (destroy-fx! state))
;; No effect-clear-owner! override anymore -- superseded by :destroy-fn
;; above (build-spec wires it unconditionally via dispatch-destroy!), which
;; vfx-core's real destroy!/clear-owner! now reach correctly per instance.
;; :clear-owner-fn itself has no live caller for any effect (see the
;; vfx-core destroy! commit).
