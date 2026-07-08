(ns cn.li.ac.block.wind-gen.gui-reactive
  "Complete reactive replacement for wind_gen/gui.clj (main + base GUIs)."
  (:require [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.gui.spec :as gui-reg] [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.platform.item :as pitem] [cn.li.ac.energy.operations :as energy]
            [cn.li.mcmod.util.log :as log] [cn.li.ac.gui.manifest :as gui-manifest]
            [cn.li.ac.gui.block-gui-reactive :as bgui]
            [cn.li.mcmod.ui.runtime :as rt] [cn.li.mcmod.ui.signal :as sig]
            [cn.li.ac.block.gui.sync :as gui-sync]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.block.wind-gen.schema :as wind-schema]))

;; ============================================================================
;; Main GUI (fan slot)
;; ============================================================================
(def ^:private main-schema-id :wind-gen-main) (def ^:private main-sync (gui-sync/schema-sync-fns wind-schema/wind-gen-main-schema))
(defn- fan-item-stack? [s] (when (and s (not (try (pitem/item-is-empty? s) (catch Exception _ true)))) (let [rn (try (some-> s pitem/item-get-item pitem/item-get-registry-name str) (catch Exception _ nil))] (or (= rn "windgen_fan") (= rn "my_mod:windgen_fan") (and rn (.endsWith rn ":windgen_fan"))))))
(defn- create-main-container [tile player] (gui-sync/create-schema-container wind-schema/wind-gen-main-schema tile player :wind-gen-main {:gui-id (gui-manifest/gui-id :wind-gen-main)}))
(defn- main-slot-count [_] (slot-schema/tile-slot-count main-schema-id))
(defn- main-get-slot [c i] (common/get-slot-item-be c i))
(defn- main-set-slot! [c i s] (common/set-slot-item-be! c i s {:inventory [nil]} identity))
(defn- main-can-place? [_ _ s] (fan-item-stack? s))
(defn- main-still-valid? [_ _] true)
(def ^:private main-server-sync! (:server-menu-sync! main-sync))
(defn- main-container? [c] (= (:container-type c) :wind-gen-main))
(defn- create-main-screen [container menu player]
  (bgui/create-screen {:page-xml "guis/rework/page_windmain.xml" :texture-name "windmain"
                        :container container :menu menu :histograms []
                        :properties {:altitude (fn [] (str (or @(:altitude container) "...")))
                                     :fan (fn [] (if @(:fan-installed container) "YES" "NO"))
                                     :obstacle (fn [] (if @(:no-obstacle container) "CLEAR" "BLOCKED"))}
                        :wireless? false}))

;; ============================================================================
;; Base GUI (energy + wireless)
;; ============================================================================
(def ^:private base-schema-id :wind-gen-base) (def ^:private base-sync (gui-sync/schema-sync-fns wind-schema/wind-gen-base-schema))
(defn- create-base-container [tile player] (gui-sync/create-schema-container wind-schema/wind-gen-base-schema tile player :wind-gen-base {:gui-id (gui-manifest/gui-id :wind-gen-base)}))
(defn- base-slot-count [_] (slot-schema/tile-slot-count base-schema-id))
(defn- base-get-slot [c i] (common/get-slot-item-be c i))
(defn- base-set-slot! [c i s] (common/set-slot-item-be! c i s {:inventory [nil]} identity))
(defn- base-can-place? [_ _ s] (energy/is-energy-item-supported? s))
(defn- base-still-valid? [_ _] true)
(def ^:private base-server-sync! (:server-menu-sync! base-sync))
(defn- base-container? [c] (= (:container-type c) :wind-gen-base))

(defn- completeness-alpha [completeness status]
  (case completeness "complete" (if (= status "COMPLETE") [1.0 1.0 1.0] [0.6 1.0 1.0]) "no_top" [0.2 1.0 1.0] [0.2 0.2 1.0]))

(defn- attach-structure-bind! [r container _signals]
  (let [clock (rt/clock-ms-sig r)]
    (rt/put-user-signal! r :icon-main-alpha (sig/computed-d [clock] (fn [_] (first (completeness-alpha (or @(:completeness container) "") (or @(:status container) ""))))))
    (rt/put-user-signal! r :icon-middle-alpha (sig/computed-d [clock] (fn [_] (second (completeness-alpha (or @(:completeness container) "") (or @(:status container) ""))))))
    (rt/put-user-signal! r :icon-base-alpha (sig/computed-d [clock] (fn [_] 1.0)))))

(defn- create-base-screen [container menu player]
  (let [safe-val #(some-> % deref)]
    (bgui/create-screen
      {:page-xml "guis/rework/page_windbase.xml" :texture-name "windbase"
       :container container :menu menu
       :histograms [(bgui/hist-buffer (fn [] (double (or @(:energy container) 0.0))) (fn [] (max 1.0 (double (or @(:max-energy container) 1.0)))))]
       :properties {:gen_speed (fn [] (format "%.2fIF/T" (double (or @(:gen-speed container) 0.0))))
                    :status (fn [] (or @(:status container) "IDLE"))
                    :altitude (fn [] (str (or @(:altitude container) "...")))
                    :fan (fn [] (if @(:fan-installed container) "YES" "NO"))
                    :obstacle (fn [] (if @(:no-obstacle container) "CLEAR" "BLOCKED"))}
       :wireless? true :wireless-role :generator :custom-bind! attach-structure-bind!})))

(def update! bgui/update-signals!) (def open! bgui/open!)

;; ============================================================================
;; Registration
;; ============================================================================
(defonce-guard wind-gen-reactive-installed?)
(defn init-wind-gen-reactive! []
  (with-init-guard wind-gen-reactive-installed?
    (slot-schema/register-slot-schema! {:schema-id main-schema-id :slots [{:id :fan :type :standard :x 78 :y 9}]})
    (slot-schema/register-slot-schema! {:schema-id base-schema-id :slots [{:id :energy :type :energy :x 42 :y 80}]})
    (gui-reg/register-block-gui! (gui-manifest/gui-name :wind-gen-main) (merge (gui-manifest/gui-registration :wind-gen-main) {:container-predicate main-container? :container-fn create-main-container :screen-fn create-main-screen :server-menu-sync-fn main-server-sync! :validate-fn main-still-valid? :close-fn (:on-close main-sync) :slot-count-fn main-slot-count :slot-get-fn main-get-slot :slot-set-fn main-set-slot! :slot-can-place-fn main-can-place? :slot-changed-fn (fn [_ _] nil)}))
    (gui-reg/register-block-gui! (gui-manifest/gui-name :wind-gen-base) (merge (gui-manifest/gui-registration :wind-gen-base) {:container-predicate base-container? :container-fn create-base-container :screen-fn create-base-screen :server-menu-sync-fn base-server-sync! :validate-fn base-still-valid? :close-fn (:on-close base-sync) :slot-count-fn base-slot-count :slot-get-fn base-get-slot :slot-set-fn base-set-slot! :slot-can-place-fn base-can-place? :slot-changed-fn (fn [_ _] nil)}))
    (log/info "Wind Generator GUIs initialized (reactive: main + base)")))
