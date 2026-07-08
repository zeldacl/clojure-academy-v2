(ns cn.li.ac.block.wireless-node.gui-reactive
  "Complete reactive replacement for wireless_node/gui.clj.
   All functional logic preserved: container, slots, animation, 2s link polling."
  (:require [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.gui.spec :as gui-reg] [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.network.client :as net-client] [cn.li.ac.energy.operations :as energy-stub]
            [cn.li.mcmod.util.log :as log] [cn.li.ac.gui.manifest :as gui-manifest]
            [cn.li.ac.gui.block-gui-reactive :as bgui]
            [cn.li.mcmod.ui.runtime :as rt] [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.signal :as sig] [cn.li.mcmod.ui.anim :as ranim]
            [cn.li.ac.block.gui.sync :as gui-sync] [cn.li.ac.config.modid :as modid]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.wireless.gui.container.move :as move-common]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.mcmod.gui.container.action-payload :as action-payload]
            [cn.li.ac.block.wireless-node.logic :as node-logic]
            [cn.li.ac.block.wireless-node.schema :as node-schema]
            [cn.li.mcmod.platform.be :as platform-be]))

;; ============================================================================
;; Animation config (preserved from old wireless_node/gui.clj)
;; ============================================================================

(defn- anim-config [state]
  (case state :linked {:begin 0 :frames 8 :frame-time 800} :unlinked {:begin 8 :frames 2 :frame-time 3000} {:begin 0 :frames 1 :frame-time 1000}))

(def ^:private anim-texture (modid/asset-path "textures" "guis/effect/effect_node.png"))
(def ^:private anim-tex-w 186) (def ^:private anim-tex-h 75)
(def ^:private anim-frame-count 10)

;; ============================================================================
;; Container + slots (preserved)
;; ============================================================================

(def wireless-node-id :wireless-node) (def ^:private gui-type :wireless-node)
(defn- ensure-slot-schema! [] (node-logic/ensure-node-slot-schema!))
(defn- resolve-state [tile] (if (map? tile) [nil tile] (try [tile (or (platform-be/get-custom-state tile) {})] (catch Exception e (log/warn "resolve-state:" (ex-message e)) [tile {}]))))
(defn create-container [tile player] (let [[be _] (resolve-state tile)] (gui-sync/create-schema-container node-schema/unified-node-schema (or be tile) player :node {:gui-id (gui-manifest/gui-id :wireless-node)})))
(defn get-slot-count [_] (slot-schema/tile-slot-count wireless-node-id))
(defn get-slot-item [c i] (common/get-slot-item-be c i))
(defn set-slot-item! [c i s] (common/set-slot-item-be! c i s {:inventory [nil]} identity))
(defn can-place-item? [_ _ s] (energy-stub/is-energy-item-supported? s))
(defn still-valid? [_ _] true)
(defn- node-container? [c] (and (map? c) (= (:container-type c) :node)))
(def ^{:private true} inventory-pred (fn [i s] (>= i s)))
(defn- quickly-move [c i ps] (let [rules [{:accept? energy-stub/is-energy-item-supported? :slot-ids [:input :output]}] cfg (slot-schema/build-quick-move-config wireless-node-id {:inventory-pred inventory-pred :rules rules})] (move-common/quick-move-with-rules c i ps cfg)))

;; ============================================================================
;; Network polling (2-second link query)
;; ============================================================================

(defn- msg [action] (msg-registry/msg gui-type action))
(defn- send-link-query! [container] (net-client/send-to-server (msg :query-link) (action-payload/action-payload container {}) (fn [resp] (when (and resp (contains? resp :linked)) (when-let [a (:linked container)] (reset! a (boolean (:linked resp))))))))

;; ============================================================================
;; Reactive animation + polling bindings
;; ============================================================================

(defn- attach-node-binds!
  "Reactive replacement for: create-anim-widget + 2s link polling + breathe-alpha.
   Uses computed signals driven by clock instead of on-frame polling."
  [r container _signals]
  (let [clock (rt/clock-ms-sig r)
        ;; 2-second polling: computed with side-effect (writes to linked atom)
        _ (rt/put-user-signal! r :link-poll-tick
            (sig/computed-o [clock]
              (fn [ms]
                (let [now (long ms)]
                  (when (zero? (rem (quot now 1000) 2))
                    (send-link-query! container)))
                nil)))
        ;; Breathe alpha: sin wave, 0.675~0.85 range, 0.8s period
        breathe-alpha (sig/computed-d [clock]
                        (fn [ms] (let [t (/ (double ms) 800.0)
                                       s (* (+ 1.0 (Math/sin (* t Math/PI 2.0))) 0.5)]
                                   (+ 0.675 (* s 0.175)))))
        _ (rt/put-user-signal! r :breathe-alpha breathe-alpha)
        ;; Animation frame: driven by state + time
        anim-frame (sig/computed-d [clock]
                     (fn [ms]
                       (let [linked? (boolean (when-let [l (:linked container)] @l))
                             state (if linked? :linked :unlinked)
                             {:keys [begin frames frame-time]} (anim-config state)
                             ticks (quot (long ms) (long frame-time))]
                         (double (+ begin (rem ticks frames))))))
        _ (rt/put-user-signal! r :anim-frame anim-frame)
        ;; Connections + range display
        _ (rt/put-user-signal! r :connections
            (sig/computed-o [clock] (fn [_] (str (or @(:connections container) 0)))))
        _ (rt/put-user-signal! r :range
            (sig/computed-o [clock] (fn [_] (str (or @(:range container) "...")))))]
    nil))

;; ============================================================================
;; Reactive screen
;; ============================================================================

(defn create-screen [container menu player]
  (bgui/create-screen
    {:page-xml "guis/rework/new/page_wireless.xml" :texture-name "wireless"
     :container container :menu menu
     :histograms [(bgui/hist-energy 0xFF4488CC)]
     :properties {:range #(str (or @(:range container) "..."))
                  :connections #(str (or @(:connections container) 0))}
     :wireless? true :wireless-role :machine
     :custom-bind! attach-node-binds!}))

(def update! bgui/update-signals!)
(def open! bgui/open!)

;; ============================================================================
;; Registration
;; ============================================================================

(defonce-guard node-reactive-installed?)
(defn init-wireless-node-reactive! []
  (with-init-guard node-reactive-installed?
    (ensure-slot-schema!)
    (gui-reg/register-block-gui!
      (gui-manifest/gui-name :wireless-node)
      (merge (gui-manifest/gui-registration :wireless-node)
        {:container-predicate node-container? :container-fn create-container
         :screen-fn create-screen :validate-fn still-valid?
         :slot-count-fn get-slot-count :slot-get-fn get-slot-item
         :slot-set-fn set-slot-item! :slot-can-place-fn can-place-item?
         :slot-changed-fn (fn [_ _] nil) :quick-move-fn quickly-move}))
    (log/info "Wireless Node GUI initialized (reactive: animation+polling preserved)")))
