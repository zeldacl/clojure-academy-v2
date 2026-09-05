(ns cn.li.ac.block.wireless-node.gui-reactive
  "Wireless Node container and Presentation Runtime bridge.
   Owns form drafts, effect_node animation, and 2s link polling."
  (:require [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.gui.spec :as gui-reg]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.ac.energy.operations :as energy-stub]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.gui.manifest :as gui-manifest]
            [cn.li.ac.gui.presentation-container :as presentation-container]
            [cn.li.ac.block.wireless-node.node-info-reactive :as node-info]
            [cn.li.ac.block.gui.sync :as gui-sync]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.wireless.gui.container.move :as move-common]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.mcmod.gui.container.action-payload :as action-payload]
            [cn.li.ac.block.wireless-node.logic :as node-logic]
            [cn.li.ac.block.wireless-node.schema :as node-schema]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(def wireless-node-id :wireless-node)
(def ^:private gui-type :node)

;; effect_node.png — 186×75 sheet, 10 vertical frames (main attach-node-binds!).
(def ^:private anim-texture "academy:textures/guis/effect/effect_node.png")
(def ^:private anim-frame-count 10)
(def ^:private anim-display-w 93.0)  ;; 186 @ scale 0.5
(def ^:private anim-display-h 37.5)  ;; 75 @ scale 0.5
;; Backgrounds sit in the centered page-art group; anim coords match main
;; attach-node-binds! relative to the 176-wide ui_node art (not the slot origin).
(def ^:private anim-x 25.0)
(def ^:private anim-y 34.75)
;; Quantize continuous breathe alpha so present! runs ~20/s, not every render frame.
(def ^:private anim-paint-quantum-ms 50)

(defn- anim-config [state]
  (case state
    :linked {:begin 0 :frames 8 :frame-time 800}
    :unlinked {:begin 8 :frames 2 :frame-time 3000}
    {:begin 0 :frames 1 :frame-time 1000}))

(defn- now-ms []
  (System/currentTimeMillis))

(defn- anim-signature
  "Cheap dirty key for live-sync fingerprint: link state + sprite frame +
   quantized breathe clock. Sprite frames already change slowly (0.8s/3s);
   breath-bucket caps alpha-driven rebuilds at ~20 Hz."
  [linked?]
  (let [ms (long (now-ms))
        state (if linked? :linked :unlinked)
        {:keys [begin frames frame-time]} (anim-config state)
        ticks (quot ms (long frame-time))
        frame (+ begin (rem ticks frames))
        breath-bucket (quot ms anim-paint-quantum-ms)]
    [linked? frame breath-bucket]))

(defn- node-anim-items
  "Build the single composite image for the center strip animation."
  [linked?]
  (let [ms (now-ms)
        state (if linked? :linked :unlinked)
        {:keys [begin frames frame-time]} (anim-config state)
        ticks (quot (long ms) (long frame-time))
        frame (+ begin (rem ticks frames))
        v0 (/ (double frame) (double anim-frame-count))
        v1 (/ (double (inc frame)) (double anim-frame-count))
        ;; Breathe alpha: 0.675~0.85, 0.8s period (main breathe-alpha).
        t (/ (double ms) 800.0)
        s (* (+ 1.0 (Math/sin (* t Math/PI 2.0))) 0.5)
        alpha (+ 0.675 (* s 0.175))]
    [{:kind :image
      :src anim-texture
      :x anim-x :y anim-y
      :w anim-display-w :h anim-display-h
      :u0 0.0 :v0 v0 :u1 1.0 :v1 v1
      :rgba {:r 1.0 :g 1.0 :b 1.0 :a alpha}}]))

(defn- ensure-slot-schema! [] (node-logic/ensure-node-slot-schema!))
(defn- resolve-state [tile]
  (if (map? tile)
    [nil tile]
    (try [tile (or (platform-be/get-custom-state tile) {})]
         (catch Exception e (log/warn "resolve-state:" (ex-message e)) [tile {}]))))

(defn- msg [action] (msg-registry/msg gui-type action))

(defn- send-link-query!
  "Poll server link flag. Keep :linked DTO when still linked; clear it when not.
   Does not replace a map DTO with a bare boolean (Connected label needs :ssid)."
  [container menu owner wireless*]
  (let [c (assoc container :minecraft-container menu)]
    (when (and owner wireless* (action-payload/menu-container-id c))
      (net-client/send-to-server owner (msg :query-link)
        (action-payload/action-payload c {})
        (fn [resp]
          (when (and resp (contains? resp :linked))
            (let [linked? (boolean (:linked resp))]
              (swap! wireless*
                     (fn [st]
                       (cond
                         (not linked?)
                         (assoc st :linked nil)
                         ;; Still linked but panel never got a DTO — leave as-is;
                         ;; connect/list refresh supplies the full row.
                         (map? (:linked st))
                         st
                         :else
                         st))))))))))

(defn- wireless-linked?
  "True when the wireless panel has a linked network DTO."
  [container]
  (some? (some-> (:presentation-wireless-state container) deref :linked)))

(defn create-container [tile player]
  (let [[be state] (resolve-state tile)
        base (gui-sync/create-schema-container node-schema/unified-node-schema
                (or be tile) player :node {:gui-id (gui-manifest/gui-id :wireless-node)})
        value-of (fn [key default]
                   (let [value (get base key default)]
                     (if (instance? clojure.lang.IDeref value) @value value)))
        ;; Bind atoms before assoc so text-change/submit closures do not capture
        ;; the pre-assoc container (where :presentation-form-state is nil).
        form-state (atom {:node-name (str (value-of :ssid ""))
                          :password (str (value-of :password ""))})
        wireless* (atom {:linked nil :avail [] :password ""})
        last-poll* (atom -1)
        link-owner (atom nil)]
    (assoc base
           :presentation-form-state form-state
           :presentation-wireless-state wireless*
           ;; Anim paint only — hist live-sync is unified in presentation_container
           ;; (info-area/hist-live-keys + presentation-network). Do not re-list
           ;; energy/capacity here.
           :presentation-anim-fingerprint
           (fn [c] (anim-signature (wireless-linked? c)))
           :presentation-tech-tabs? true
           :presentation-wireless {:domain :node :role :node}
           :presentation-text-fields [{:id :node-name :binding-key :node-name :x 12 :y 82 :width 120 :height 18
                                       :value-fn (fn [_ _] (value-of :ssid ""))}
                                      {:id :password :binding-key :network-password :x 12 :y 105 :width 120 :height 18
                                       :value-fn (fn [_ _] (value-of :password ""))}]
           :presentation-on-mount!
           (fn [container]
             (reset! link-owner (or (runtime-hooks/current-player-state-owner)
                                    (runtime-hooks/default-client-owner)))
             (when-let [menu (:minecraft-container container)]
               (send-link-query! container menu @link-owner wireless*)))
           :presentation-frame!
           (fn [container]
             (let [bucket (quot (long (now-ms)) 2000)]
               (when (not= bucket @last-poll*)
                 (reset! last-poll* bucket)
                 (when-let [menu (:minecraft-container container)]
                   (send-link-query! container menu @link-owner
                                     (:presentation-wireless-state container))))))
           :presentation-snapshot-fn
           (fn [container _]
             ;; Read atoms from the live screen container (DataSlot target).
             (let [live (fn [k default]
                          (let [x (get container k ::missing)]
                            (cond
                              (= x ::missing) default
                              (instance? clojure.lang.IDeref x) @x
                              :else x)))
                   energy (double (or (live :energy 0.0) 0.0))
                   max-energy-atom (double (or (live :max-energy 0.0) 0.0))
                   max-energy (if (pos? max-energy-atom)
                                max-energy-atom
                                (double (node-logic/node-max-energy
                                          {:node-type (or (live :node-type :basic)
                                                         :basic)})))
                   load (double (or (live :capacity 0.0) 0.0))
                   max-load (double (or (live :max-capacity 0.0) 0.0))
                   owner? (boolean (node-logic/owner-authorized? state player))
                   form @form-state
                   linked? (wireless-linked? container)
                   node-name (str (if (contains? form :node-name)
                                    (:node-name form)
                                    (live :ssid "")))
                   password (str (if (contains? form :password)
                                   (:password form)
                                   (live :password "")))]
               {:node-name node-name
                :network-password password
                :node-anim (node-anim-items linked?)
                :info-area (node-info/info-area-snapshot
                             {:initialized true
                              :energy energy
                              :max-energy max-energy
                              :capacity load
                              :owner (node-logic/owner-name state)
                              :range (or (live :range 0) 0)
                              :ssid node-name
                              :password password
                              :load load
                              :max-capacity max-load}
                             owner?)}))
           :presentation-text-change!
           (fn [field value]
             (swap! form-state assoc field value))
           :presentation-text-submit!
           (fn [field value container]
             ;; Use the live screen container (has :minecraft-container), not
             ;; create-container's base map — action-payload needs menu id.
             (when (node-logic/owner-authorized? state player)
               (case field
                 :node-name (node-info/send-change-name container value)
                 :password (node-info/send-change-password container value)
                 nil)))
           :presentation-dispatch-action!
           (fn [_action _payload] nil))))

(defn get-slot-count [_] (slot-schema/tile-slot-count wireless-node-id))
(defn get-slot-item [c i] (common/get-slot-item-be c i))
(defn set-slot-item! [c i s] (common/set-slot-item-be! c i s {:inventory [nil]} identity))
(defn can-place-item? [_ _ s] (energy-stub/is-energy-item-supported? s))
(defn still-valid? [_ _] true)
(defn- node-container? [c] (and (map? c) (= (:container-type c) gui-type)))
(def ^:private inventory-pred (fn [i s] (>= i s)))
(defn- quickly-move [c i stack]
  (move-common/quick-move-with-rules
    c i stack
    (slot-schema/build-quick-move-config wireless-node-id
      {:inventory-pred inventory-pred
       :rules [{:accept? energy-stub/is-energy-item-supported? :slot-ids [:input :output]}]})))

(defn create-screen [container menu player]
  (presentation-container/presentation-screen-data
    container menu player wireless-node-id "academy:wireless_node"))

(defn init-wireless-node-reactive! []
  (install/framework-once! ::node-reactive-installed?
    (fn []
      (ensure-slot-schema!)
      (gui-reg/register-block-gui!
        (gui-manifest/gui-name :wireless-node)
        (merge (gui-manifest/gui-registration :wireless-node)
               {:container-predicate node-container? :container-fn create-container
                :screen-fn create-screen :validate-fn still-valid?
                :close-fn (:on-close (gui-sync/schema-sync-fns node-schema/unified-node-schema))
                :slot-count-fn get-slot-count :slot-get-fn get-slot-item
                :slot-set-fn set-slot-item! :slot-can-place-fn can-place-item?
                :slot-changed-fn (fn [_ _] nil) :quick-move-fn quickly-move}))
      (log/info "Wireless Node GUI initialized (Presentation Runtime)"))))
