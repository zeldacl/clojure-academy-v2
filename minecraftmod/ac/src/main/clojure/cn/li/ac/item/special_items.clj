(ns cn.li.ac.item.special-items
  "Special migrated items with original gameplay behavior."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.item.dsl :as idsl]
            [clojure.string :as str]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.item :as pitem]
            [cn.li.mcmod.platform.nbt :as nbt]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.util.log :as log]))

(def ^:private induction-factor->category
  {"induction_factor_electromaster" :electromaster
   "induction_factor_meltdowner" :meltdowner
   "induction_factor_teleporter" :teleporter
   "induction_factor_vecmanip" :vecmanip})

(def induction-factor-item-ids
  (mapv (fn [suffix] (modid/namespaced-path suffix))
        (keys induction-factor->category)))

(defn induction-factor-catalog
  "Vector of [item-id category-kw] for developer material checks."
  []
  (mapv (fn [[suffix category]]
          [(modid/namespaced-path suffix) category])
        induction-factor->category))

(defn find-induction-factor
  "First induction factor in `player`'s inventory as {:item-id :category},
   else nil. Works on both sides (inventory item counts are client-visible)."
  [player]
  (some (fn [[item-id category]]
          (when (pos? (int (entity/player-count-item-by-id player item-id)))
            {:item-id item-id :category category}))
        (induction-factor-catalog)))

(def magnetic-coil-item-id (modid/namespaced-path "magnetic_coil"))
(def ^:private matter-unit-item-id (modid/namespaced-path "matter_unit"))
(def ^:private imag-phase-block-id (modid/namespaced-path "imag_phase"))
(def ^:private mag-hook-entity-id (modid/namespaced-path "entity_mag_hook"))

(defn matter-unit-overlay-data
  "Render metadata for client item overlay/decorator.
   Pure data function to keep game-logic decisions in AC."
  [damage now-ms]
  (let [phase-liquid? (= 1 (int (or damage 0)))
        scroll-offset (double (mod (/ (double (or now-ms 0)) 1800.0) 1.0))]
    {:enabled? true
     :phase-liquid? phase-liquid?
     :alpha (if phase-liquid? 0.88 0.0)
     :scroll-offset scroll-offset
     :base-texture (modid/asset-path "textures" "item/matter_unit.png")
     :liquid-texture (modid/asset-path "textures" "item/matter_unit_phase_liquid.png")
     :mask-texture (modid/asset-path "textures" "item/matter_unit_overlay.png")}))

(defn- apply-induction-factor!
  "Induction factors are consumed via the developer block timed session, not direct use."
  [_ctx]
  {:consume? false})

(defn- get-matter-kind
  [item-stack]
  (let [tag (try (pitem/item-get-tag-compound item-stack) (catch Exception _ nil))
        from-tag (when tag (try (nbt/nbt-get-string tag "matterKind") (catch Exception _ nil)))]
    (or (case (some-> from-tag str)
          "phase-liquid" :phase-liquid
          "none" :none
          nil)
        (case (int (try (pitem/item-get-damage item-stack) (catch Exception _ 0)))
          1 :phase-liquid
          :none))))

(defn- set-matter-kind!
  [item-stack kind]
  (let [tag (pitem/item-get-or-create-tag item-stack)]
    (nbt/nbt-set-string! tag "matterKind" (if (= kind :phase-liquid) "phase-liquid" "none"))
    (pitem/item-set-damage! item-stack (if (= kind :phase-liquid) 1 0))))

(defn- make-matter-unit-stack
  [kind]
  (let [stack (pitem/create-item-stack-by-id matter-unit-item-id 1)]
    (when stack
      (set-matter-kind! stack kind)
      stack)))

(defn- mutate-or-convert-main-hand!
  [player item-stack target-kind]
  (if (<= (int (pitem/item-get-count item-stack)) 1)
    (set-matter-kind! item-stack target-kind)
    (do
      (entity/player-consume-main-hand-item! player 1)
      (when-let [converted (make-matter-unit-stack target-kind)]
        (entity/player-give-item-stack! player converted)))))

(defn- use-matter-unit!
  [{:keys [player item-stack side]}]
  (if (not= side :server)
    {:consume? true}
    (let [kind (get-matter-kind item-stack)
          hit (entity/player-raytrace-block player 5.0 (= kind :none))
          level (entity/player-get-level player)]
      (if-not hit
        {:consume? false}
        (let [{:keys [hit-pos place-pos block-id]} hit
              hit-block-pos (pos/create-block-pos (:x hit-pos) (:y hit-pos) (:z hit-pos))
              place-block-pos (pos/create-block-pos (:x place-pos) (:y place-pos) (:z place-pos))]
          (cond
            (and (= kind :none) (= block-id imag-phase-block-id))
            (do
              (when (world/world-remove-block* level hit-block-pos)
                (mutate-or-convert-main-hand! player item-stack :phase-liquid))
              {:consume? true})

            (= kind :phase-liquid)
            (let [target-state (world/world-get-block-state* level place-block-pos)
                  placeable? (or (nil? target-state) (world/block-state-is-air? target-state))]
              (if (and placeable? (world/world-place-block-by-id* level imag-phase-block-id place-block-pos 3))
                (do
                  (mutate-or-convert-main-hand! player item-stack :none)
                  {:consume? true})
                {:consume? false}))

            :else
            {:consume? false}))))))

(defn- play-mag-hook-throw-sound!
  "Matches original ItemMagHook#onItemRightClick: egg-throw sound, volume 0.5,
   pitch 0.4 / (rand[0,1) * 0.4 + 0.8) ~= [0.333, 0.5)."
  [player]
  (when (world-effects/available?)
    (when-let [world-id (world/world-get-dimension-id* (entity/player-get-level player))]
      (world-effects/play-sound!*
        world-id
        (entity/entity-get-x player) (entity/entity-get-y player) (entity/entity-get-z player)
        "minecraft:entity.egg.throw"
        :players
        0.5
        (/ 0.4 (+ (* (rand) 0.4) 0.8))))))

(defn- throw-mag-hook!
  [{:keys [player side]}]
  (when (= side :server)
    (play-mag-hook-throw-sound! player)
    (when (entity/player-spawn-entity-by-id! player mag-hook-entity-id 2.0)
      (entity/player-consume-main-hand-item! player 1)))
  {:consume? true})

(defn init-special-items!
  []
  (install/framework-once! ::special-items-installed?
  (fn []
    ;; MC 1.20 does not support metadata subtypes, so induction factors are split.
    (idsl/register-item!
      (idsl/create-item-spec
        "induction_factor_electromaster"
        {:max-stack-size 1
         :creative-tab :misc
         :properties {:tooltip ["Induction Factor - Electromaster"
                                "Used for ability awakening/category transform"]
                      :model-texture "factor_electromaster"}
         :on-right-click apply-induction-factor!}))
    (idsl/register-item!
      (idsl/create-item-spec
        "induction_factor_meltdowner"
        {:max-stack-size 1
         :creative-tab :misc
         :properties {:tooltip ["Induction Factor - Meltdowner"
                                "Used for ability awakening/category transform"]
                      :model-texture "factor_meltdowner"}
         :on-right-click apply-induction-factor!}))
    (idsl/register-item!
      (idsl/create-item-spec
        "induction_factor_teleporter"
        {:max-stack-size 1
         :creative-tab :misc
         :properties {:tooltip ["Induction Factor - Teleporter"
                                "Used for ability awakening/category transform"]
                      :model-texture "factor_teleporter"}
         :on-right-click apply-induction-factor!}))
    (idsl/register-item!
      (idsl/create-item-spec
        "induction_factor_vecmanip"
        {:max-stack-size 1
         :creative-tab :misc
         :properties {:tooltip ["Induction Factor - Vec Manip"
                                "Used for ability awakening/category transform"]
                      :model-texture "factor_vecmanip"}
         :on-right-click apply-induction-factor!}))
    (idsl/register-item!
      (idsl/create-item-spec
        "mag_hook"
        {:max-stack-size 1
         :creative-tab :tools
         :properties {:tooltip ["Mag Hook"
                                "Right click to throw and retrieve after hit"]
                      :model-texture "mag_hook"}
         :on-right-click throw-mag-hook!}))
    (idsl/register-item!
      (idsl/create-item-spec
        "matter_unit"
        {:max-stack-size 16
         :creative-tab :misc
         :properties {:tooltip ["Matter Unit (Empty)"
                                "Right click to collect/place imaginary phase liquid"]
                      :model-texture "matter_unit"
                      :filled-variant {:nbt {"matterKind" "phase-liquid"}
                                       :damage 1
                                       :label "phase-liquid"}}
         :on-right-click use-matter-unit!}))
    (log/info "Special items initialized: induction factors, mag_hook, matter_unit"))))


