(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.mine-detect
  (:require [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.client.effect-controller :as vfx-level]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.ability.client.effects.rv3 :as vec3]
            [clojure.string :as str]
            [cn.li.ac.ability.client.fx-templates.arc-beam]))

(def ^:private mineview-texture
  (modid/namespaced-path "textures/effects/mineview.png"))

(def ^:private default-life-ticks 100)
(def ^:private default-rescan-interval 5)
(def ^:private max-client-range 28.0)
(def ^:private max-ore-results 8400)

(def ^:private default-ore-color
  {:r 220 :g 235 :b 255 :a 185})

(def ^:private advanced-tier-colors
  ;; Matching original MineDetect colors array (5 tiers):
  ;;   default(0) → harvest-level 0-3 mapped via Math.min(3, harvest+1)
  {0 {:r 161 :g 181 :b 188 :a 165}    ;; harvest level 0 → tier 1 (original index 1)
   1 {:r 87  :g 231 :b 248 :a 190}    ;; harvest level 1 → tier 2 (original index 2)
   2 {:r 97  :g 204 :b 94  :a 210}    ;; harvest level 2 → tier 3 (original index 3)
   3 {:r 235 :g 109 :b 84  :a 225}})   ;; harvest level 3 → tier 4 (original index 4)

(defn- ore-block?
  ([block-id]
   (ore-block? block-id nil))
  ([block-id {:keys [ore-tagged?]}]
   (and (string? block-id)
        (or ore-tagged?
            (str/includes? block-id "_ore")
            ;; Modern equivalent of a rare mineral block; retained as an
            ;; enhanced 1.20-era extension.
            (str/includes? block-id "ancient_debris")))))

(defn- clamped-range
  [range]
  (double (max 1.0 (min max-client-range (double (or range 20.0))))))

(defn- fallback-advanced-tier
  [block-id]
  (cond
    (or (str/includes? block-id "diamond")
        (str/includes? block-id "emerald")
        (str/includes? block-id "ancient_debris")) 2

    (or (str/includes? block-id "gold")
        (str/includes? block-id "redstone")
        (str/includes? block-id "lapis")) 1

    :else 0))

(defn- advanced-tier
  "Map ore harvest-level to color tier, matching original MineDetect:
   colors[Math.min(3, harvestLevel+1)], re-indexed to this 0-based 4-entry
   map (advanced-tier-colors key K == original colors[K+1]) — so the tier
   here is Math.min(2, harvestLevel), one less than the original index."
  [{:keys [harvest-level block-id]}]
  (if (number? harvest-level)
    (min 2 (long harvest-level))
    (fallback-advanced-tier (str block-id))))

(defn- ore-color
  [ore advanced?]
  (if advanced?
    (get advanced-tier-colors (advanced-tier ore) default-ore-color)
    default-ore-color))

(defn- faded-color
  "Alpha based on distance from player, matching original calcAlpha:
   alpha = 0.3 + (1 - (dist/range) * 2.2) * 0.7, clamped to [0.0, 1.0]"
  [color player-pos ore-x ore-y ore-z range]
  (let [dx (- (double ore-x) (double (:x player-pos 0.0)))
        dy (- (double ore-y) (double (:y player-pos 0.0)))
        dz (- (double ore-z) (double (:z player-pos 0.0)))
        dist (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))
        jdg (max 0.0 (- 1.0 (* 2.2 (/ dist (double range)))))
        alpha-factor (+ 0.3 (* jdg 0.7))
        scaled-alpha (int (* (double (:a color)) (max 0.0 (min 1.0 alpha-factor))))]
    (ru/with-alpha color scaled-alpha)))

(defn- highlight-quad
  [texture p0 p1 p2 p3 color]
  ;; Upstream HandlerRender disables BOTH the depth test and the fog for the
  ;; ore-highlight pass (glDisable(GL_DEPTH_TEST) + glDisable(GL_FOG)): the
  ;; ores sit underground so a depth-tested quad would be hidden by the
  ;; terrain, and the blindness the skill itself applies would fog the boxes
  ;; out at range. The no-fog render path is no-depth, no-cull as well.
  (-> (ru/quad-op texture p0 p1 p2 p3 color)
      (assoc :no-depth-test? true)
      (assoc :no-fog? true)))

(defn- block-highlight-ops
  [x y z color]
  (let [eps 0.02
        x0 (- (double x) eps)
        y0 (- (double y) eps)
        z0 (- (double z) eps)
        x1 (+ (double x) 1.0 eps)
        y1 (+ (double y) 1.0 eps)
        z1 (+ (double z) 1.0 eps)
        v3 vec3/v3
        p000 (v3 x0 y0 z0) p100 (v3 x1 y0 z0) p110 (v3 x1 y1 z0) p010 (v3 x0 y1 z0)
        p101 (v3 x1 y0 z1) p001 (v3 x0 y0 z1) p011 (v3 x0 y1 z1) p111 (v3 x1 y1 z1)]
    [(highlight-quad mineview-texture p000 p100 p110 p010 color)
     (highlight-quad mineview-texture p101 p001 p011 p111 color)
     (highlight-quad mineview-texture p001 p000 p010 p011 color)
     (highlight-quad mineview-texture p100 p101 p111 p110 color)
     (highlight-quad mineview-texture p010 p110 p111 p011 color)
     (highlight-quad mineview-texture p001 p101 p100 p000 color)]))

(defn- should-rescan?
  [{:keys [ticks rescan-interval last-rescan-tick]}]
  (or (nil? last-rescan-tick)
      (>= (- (long ticks) (long last-rescan-tick))
          (long (max 1 (or rescan-interval default-rescan-interval))))))

(defn- player-position
  "Use the local player's feet position, matching HandlerEntity.posX/Y/Z.
  The :x/:y/:z fallback keeps the renderer contract compatible with callers
  that only provide the historic hand-center coordinates."
  [view-pos]
  {:x (double (or (:player-x view-pos) (:x view-pos) 0.0))
   :y (double (or (:player-y view-pos) (:y view-pos) 64.0))
   :z (double (or (:player-z view-pos) (:z view-pos) 0.0))})

(defn- rescan-ores
  [{:keys [range]} view-pos query-fn]
  (if (and (fn? query-fn) (map? view-pos))
      (let [{origin-x :x origin-y :y origin-z :z} (player-position view-pos)
            r (clamped-range range)]
        (->> (query-fn origin-x origin-y origin-z r ore-block?)
             (take max-ore-results)
             (map (fn [block]
                    {:x (int (:x block))
                     :y (int (:y block))
                     :z (int (:z block))
                     :block-id (:block-id block)
                     :harvest-level (:harvest-level block)}))
             distinct
             vec))
      []))

(defn- apply-perform!
  [{:keys [range advanced? life-ticks rescan-interval]}]
  (client-sounds/queue-current-sound-effect!
    {:type :sound
     :sound-id (modid/namespaced-path "em.minedetect")
     :source :ambient
     :volume 0.5
     :pitch 1.0})
  {:active? true
   :ticks 0
   :life-ticks (long (max 1 (or life-ticks default-life-ticks)))
   :rescan-interval (long (max 1 (or rescan-interval default-rescan-interval)))
   :last-rescan-tick nil
   :range (clamped-range range)
   :advanced? (boolean advanced?)
   :ores []})

;; vfx-core :transient migration (docs/04-systems/COMBAT_VFX_PLATFORM_GAPS.md
;; E section): one real vfx-core instance per (owner, activation) now, so
;; state is this cast's own map directly -- no more :effect-state owner-map
;; wrapping (owner isolation comes from instance identity itself, INCLUDING
;; for the sample-time rescan write-back below, which previously picked
;; "the first active owner in the shared map" -- a real latent bug that
;; only mattered once combat_content.clj's actual wiring ever reaches this
;; file, see the note below).
;;
;; This case dispatch is preserved EXACTLY as it was before this migration.
;; combat_content.clj's :mine-detect skill sends exactly ONE :vfx step,
;; ever: :event :release from its one-shot :instant program, with
;; :params {:range 30.0} -- :release doesn't match :perform/:end, so it
;; falls straight through to the trailing `state*` no-op default every
;; time. In production today apply-perform! never runs: no sound, no ore
;; highlight, ever. Migrated structurally only.
;;
;; Separately, this file's OLD owner-map design had a real bug the plan
;; flagged before any of this was investigated: build-plan's owner lookup
;; used `some` to pick the first :active? entry in the shared map, so two
;; players mine-detecting at once would only ever render (and rescan) for
;; whichever one's entry `some` happened to reach first — the other saw no
;; highlights at all. That bug is now structurally impossible: each
;; instance is its own owner, so there is no "which active owner" choice
;; left to make. It has no observable effect today (see above -- nothing
;; reaches :perform to make an instance :active? in the first place), but
;; is recorded here since it's a real, if currently unreachable, behavior
;; change from before this migration.
(defn- enqueue-state!
  [state ctx-id channel _owner-key payload]
  (let [state* (or state {})]
    (case (:mode payload)
      :perform
      (apply-perform! payload)

      :end
      {}

      state*)))

(defn- tick-state!
  "Returning nil ends the instance -- see vfx-core/runtime.clj's
   tick-instance. Only reachable while :active? and before :life-ticks
   elapses, matching the pre-migration per-owner expiry exactly."
  [state]
  (let [state* (or state {})]
    (when (:active? state*)
      (let [next-ticks (inc (long (:ticks state*)))
            life-ticks (long (:life-ticks state*))]
        (when (< next-ticks life-ticks)
          (assoc state* :ticks next-ticks))))))

(defn- maybe-refresh-ores!
  "Write back a rescan result into THIS instance's own state -- see
   update-state-for-owner!'s docstring for why a plain update-state! (which
   would target vfx-core's core/instance-for-effect, \"the first instance
   of :mine-detect\") is the wrong tool once more than one player can have
   a live instance at once."
  [uuid hand-center-pos query-fn state]
  (when (should-rescan? state)
    (vfx-level/update-state-for-owner! :mine-detect uuid :level
      (fn [st]
        (if (and st (should-rescan? st))
          (assoc st
                 :ores (rescan-ores st hand-center-pos query-fn)
                 :last-rescan-tick (:ticks st))
          st)))))

(defn- build-plan
  [_camera-pos hand-center-pos _tick query-fn]
  (let [state (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :mine-detect)]
    (when (:active? state)
      (when-let [uuid (:player-uuid hand-center-pos)]
        (maybe-refresh-ores! uuid hand-center-pos query-fn state))
      (let [{:keys [ores advanced? range]}
            (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :mine-detect)
            player-pos (player-position hand-center-pos)
            ops (into []
                      (mapcat (fn [{:keys [x y z] :as ore}]
                                (let [base-color (ore-color ore advanced?)
                                      color (faded-color base-color player-pos
                                                         x y z range)]
                                  (block-highlight-ops x y z color))))
                      ores)]
        (when (seq ops)
          {:ops ops})))))

(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:mine-detect :level] [_ _] {})
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:mine-detect :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:mine-detect :level] [_ _ store] (tick-state! store))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-build-plan :mine-detect
  [_effect-id camera-pos hand-center-pos tick & [query-fn]]
  (build-plan camera-pos hand-center-pos tick query-fn))
;; No effect-clear-owner! override anymore -- no live caller, no
;; side-effecting resource here (see mark_teleport.clj's migration commit).
