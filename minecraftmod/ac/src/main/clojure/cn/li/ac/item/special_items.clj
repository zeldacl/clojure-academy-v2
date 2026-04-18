(ns cn.li.ac.item.special-items
  "Special migrated items with original gameplay behavior."
  (:require [cn.li.mcmod.item.dsl :as idsl]
            [clojure.string :as str]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.item :as pitem]
            [cn.li.mcmod.platform.nbt :as nbt]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.ability.model.ability :as adata]
            [cn.li.mcmod.util.log :as log]))

(defonce ^:private special-items-installed? (atom false))

(def ^:private induction-factor->category
  {"induction_factor_electromaster" :electromaster
   "induction_factor_meltdowner" :meltdowner
   "induction_factor_teleporter" :teleporter
   "induction_factor_vecmanip" :vecmanip})

(def ^:private magnetic-coil-item-id "my_mod:magnetic_coil")
(def ^:private matter-unit-item-id "my_mod:matter_unit")
(def ^:private imag-phase-block-id "my_mod:imag_phase")
(def ^:private mag-hook-entity-id "my_mod:entity_mag_hook")

(defn- apply-induction-factor!
  [{:keys [player item-id side]}]
  (when (= side :server)
    (let [normalized-id (last (str/split (str item-id) #":" 2))
          target-category (get induction-factor->category normalized-id)]
      (when target-category
        (let [uuid (str (entity/player-get-uuid player))
              state (ps/get-or-create-player-state! uuid)
              ability (:ability-data state)
              current-category (:category-id ability)
              current-level (int (:level ability 1))]
          (cond
            (nil? current-category)
            (do
              (ps/update-ability-data! uuid adata/set-category target-category)
              (entity/player-consume-main-hand-item! player 1)
              (log/info "Applied induction factor for initial category" {:uuid uuid :category target-category}))

            (= current-category target-category)
            nil

            (and (>= current-level 3)
                 (pos? (int (entity/player-count-item-by-id player magnetic-coil-item-id))))
            (let [new-level (max 1 (dec current-level))
                  consumed-factor? (entity/player-consume-main-hand-item! player 1)
                  consumed-coil? (entity/player-consume-item-by-id! player magnetic-coil-item-id 1)]
              (when (and consumed-factor? consumed-coil?)
                (ps/update-ability-data!
                  uuid
                  (fn [data]
                    (-> data
                        (adata/set-category target-category)
                        (adata/set-level new-level))))
                (log/info "Applied induction factor category transform"
                          {:uuid uuid
                           :from current-category
                           :to target-category
                           :level new-level})))

            :else
            nil)))))
  {:consume? true})

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

(defn- throw-mag-hook!
  [{:keys [player side]}]
  (when (= side :server)
    (when (entity/player-spawn-entity-by-id! player mag-hook-entity-id 2.0)
      (entity/player-consume-main-hand-item! player 1)))
  {:consume? true})

(defn init-special-items!
  []
  (when (compare-and-set! special-items-installed? false true)
    ;; MC 1.20 does not support metadata subtypes, so induction factors are split.
    (idsl/register-item!
      (idsl/create-item-spec
        "induction_factor_electromaster"
        {:max-stack-size 1
         :creative-tab :misc
         :properties {:tooltip ["诱导因子 - 电击使"
                                "用于能力觉醒/类别转化"]
                      :model-texture "factor_electromaster"}
         :on-right-click apply-induction-factor!}))
    (idsl/register-item!
      (idsl/create-item-spec
        "induction_factor_meltdowner"
        {:max-stack-size 1
         :creative-tab :misc
         :properties {:tooltip ["诱导因子 - 原子崩坏"
                                "用于能力觉醒/类别转化"]
                      :model-texture "factor_meltdowner"}
         :on-right-click apply-induction-factor!}))
    (idsl/register-item!
      (idsl/create-item-spec
        "induction_factor_teleporter"
        {:max-stack-size 1
         :creative-tab :misc
         :properties {:tooltip ["诱导因子 - 空间移动"
                                "用于能力觉醒/类别转化"]
                      :model-texture "factor_teleporter"}
         :on-right-click apply-induction-factor!}))
    (idsl/register-item!
      (idsl/create-item-spec
        "induction_factor_vecmanip"
        {:max-stack-size 1
         :creative-tab :misc
         :properties {:tooltip ["诱导因子 - 矢量操纵"
                                "用于能力觉醒/类别转化"]
                      :model-texture "factor_vecmanip"}
         :on-right-click apply-induction-factor!}))
    (idsl/register-item!
      (idsl/create-item-spec
        "mag_hook"
        {:max-stack-size 1
         :creative-tab :tools
         :properties {:tooltip ["磁力钩"
                                "右键投掷，命中后可回收"]
                      :model-texture "mag_hook"}
         :on-right-click throw-mag-hook!}))
    (idsl/register-item!
      (idsl/create-item-spec
        "matter_unit"
        {:max-stack-size 16
         :creative-tab :misc
         :properties {:tooltip ["物质单元（空）"
                                "右键采集/放置虚相液体"]
                      :model-texture "matter_unit"}
         :on-right-click use-matter-unit!}))
    (log/info "Special items initialized: induction factors, mag_hook, matter_unit")))
