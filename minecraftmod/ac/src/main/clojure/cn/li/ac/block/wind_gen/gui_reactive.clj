(ns cn.li.ac.block.wind-gen.gui-reactive
  "Reactive GUI registration for the Wind Generator main and base screens."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.gui.spec :as gui-reg] [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.platform.item :as pitem] [cn.li.ac.energy.operations :as energy]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.util.log :as log] [cn.li.ac.gui.manifest :as gui-manifest]
            [cn.li.ac.gui.presentation-container :as presentation-container]
            [cn.li.ac.block.gui.sync :as gui-sync]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.block.wind-gen.schema :as wind-schema]))

;; ============================================================================
;; Wind Generator GUIs (main + base)
;; ============================================================================

(defn- block-altitude
  "Info-area \"altitude\" value (upstream: tile.getPos.getY). The block never
   moves, so resolve it once at screen creation rather than per frame."
  [container]
  (or (try (some-> (:tile-entity container) pos/block-pos pos/pos-y str)
           (catch Exception _ nil))
      "..."))

;; ============================================================================
;; Main GUI (fan slot)
;; ============================================================================
(def ^:private main-schema-id :wind-gen-main) (def ^:private main-sync (gui-sync/schema-sync-fns wind-schema/wind-gen-main-schema))
(defn- fan-item-stack? [s] (when (and s (not (try (pitem/empty? s) (catch Exception _ true)))) (let [^String rn (try (some-> s pitem/object pitem/registry-name str) (catch Exception _ nil))] (or (= rn "windgen_fan") (= rn (modid/namespaced-path "windgen_fan")) (and rn (.endsWith rn ":windgen_fan"))))))
(defn- create-main-container [tile player] (assoc (gui-sync/create-schema-container wind-schema/wind-gen-main-schema tile player :wind-gen-main {:gui-id (gui-manifest/gui-id :wind-gen-main)}) :presentation-close-fn (:on-close main-sync)))
(defn- main-slot-count [_] (slot-schema/tile-slot-count main-schema-id))
(defn- main-get-slot [c i] (common/get-slot-item-be c i))
(defn- main-set-slot! [c i s] (common/set-slot-item-be! c i s {:inventory [nil]} identity))
(defn- main-can-place? [_ _ s] (fan-item-stack? s))
(defn- main-still-valid? [_ _] true)
(def ^:private main-server-sync! (:server-menu-sync! main-sync))
(defn- main-container? [c] (= (:container-type c) :wind-gen-main))
(defn- create-main-screen [container menu player]
  ;; First migrated Menu/Slot vertical slice. The server menu remains
  ;; authoritative; only its neutral snapshot/action bridge enters Runtime.
  (presentation-container/presentation-screen-data
    container menu player main-schema-id "academy:machine_container"))

;; ============================================================================
;; Base GUI (energy + wireless)
;; ============================================================================
(def ^:private base-schema-id :wind-gen-base) (def ^:private base-sync (gui-sync/schema-sync-fns wind-schema/wind-gen-base-schema))
(defn- create-base-container [tile player] (assoc (gui-sync/create-schema-container wind-schema/wind-gen-base-schema tile player :wind-gen-base {:gui-id (gui-manifest/gui-id :wind-gen-base)}) :presentation-close-fn (:on-close base-sync)))
(defn- base-slot-count [_] (slot-schema/tile-slot-count base-schema-id))
(defn- base-get-slot [c i] (common/get-slot-item-be c i))
(defn- base-set-slot! [c i s] (common/set-slot-item-be! c i s {:inventory [nil]} identity))
(defn- base-can-place? [_ _ s] (energy/is-energy-item-supported? s))
(defn- base-still-valid? [_ _] true)
(def ^:private base-server-sync! (:server-menu-sync! base-sync))
(defn- base-container? [c] (= (:container-type c) :wind-gen-base))

(defn- completeness-alpha [completeness status]
  ;; Tile :completeness values are (name kw) strings: "complete" / "no-top" /
  ;; "base-only" — "no_top" (underscore) never matched, so a tower missing only
  ;; its top kept the main+middle icons dark.
  (case completeness "complete" (if (= status "COMPLETE") [1.0 1.0 1.0] [0.6 1.0 1.0]) "no-top" [0.2 1.0 1.0] [0.2 0.2 1.0]))

(defn- create-base-screen [container menu player]
  (presentation-container/presentation-screen-data
    container menu player base-schema-id "academy:machine_container"))


;; ============================================================================
;; Registration
;; ============================================================================
(defn init-wind-gen-reactive! []
  (install/framework-once! ::wind-gen-reactive-installed?
  (fn []
    (slot-schema/register-slot-schema! {:schema-id main-schema-id :slots [{:id :fan :type :standard :x 78 :y 9}]})
    (slot-schema/register-slot-schema! {:schema-id base-schema-id :slots [{:id :energy :type :energy :x 42 :y 80}]})
    (gui-reg/register-block-gui! (gui-manifest/gui-name :wind-gen-main) (merge (gui-manifest/gui-registration :wind-gen-main) {:container-predicate main-container? :container-fn create-main-container :screen-fn create-main-screen :server-menu-sync-fn main-server-sync! :validate-fn main-still-valid? :close-fn (:on-close main-sync) :slot-count-fn main-slot-count :slot-get-fn main-get-slot :slot-set-fn main-set-slot! :slot-can-place-fn main-can-place? :slot-changed-fn (fn [_ _] nil)}))
    (gui-reg/register-block-gui! (gui-manifest/gui-name :wind-gen-base) (merge (gui-manifest/gui-registration :wind-gen-base) {:container-predicate base-container? :container-fn create-base-container :screen-fn create-base-screen :server-menu-sync-fn base-server-sync! :validate-fn base-still-valid? :close-fn (:on-close base-sync) :slot-count-fn base-slot-count :slot-get-fn base-get-slot :slot-set-fn base-set-slot! :slot-can-place-fn base-can-place? :slot-changed-fn (fn [_ _] nil)}))
    (log/info "Wind Generator GUIs initialized (reactive: main + base)"))))
