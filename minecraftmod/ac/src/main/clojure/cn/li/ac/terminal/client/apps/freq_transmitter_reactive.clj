(ns cn.li.ac.terminal.client.apps.freq-transmitter-reactive
  "Complete reactive replacement for freq_transmitter.clj — scan/configure/result FSM."
  (:require [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.xml :as ui-xml]
            [clojure.string :as str])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [cn.li.mcmod.ui.node INode]))

(def ^:private freq-scan-msg 1005)
(def ^:private freq-config-msg 1006)
(def ^:private timeout-sec 20.0)
(def ^:private active-color 0xFFFFD700)
(def ^:private idle-color 0xFFFFFF88)

(defn- player-uuid [player]
  (or (uuid/player-uuid player)
      (:player-uuid (runtime-hooks/default-client-owner))))

(defn- content-node [^UiRt rt]
  (ui/node rt "content"))

(defn- clear-content! [^UiRt rt]
  (when-let [^INode area (content-node rt)]
    (rt/clear-children! rt area)))

(defn- add-text! [^UiRt rt y text & {:keys [id color font-size]}]
  (let [^INode area (content-node rt)
        nid (or id (keyword (str "ft-" (rand-int 100000))))
        spec {:kind :text
              :props {:id nid :x 0.0 :y (double y) :w 360.0 :h 24.0
                      :text text :font-size (double (or font-size 9.0))
                      :color (long (or color 0xFFAAAAAA))}}]
    (rt/build-child! rt spec area)))

(defn- add-button! [^UiRt rt y label tint on-click]
  (let [^INode area (content-node rt)
        bid (keyword (str "ft-btn-" (rand-int 100000)))
        spec {:kind :box
              :props {:id bid :x 120.0 :y (double y) :w 120.0 :h 28.0
                      :fill (long tint) :hover-tint 0x33FFFFFF}
              :children [{:kind :text
                          :props {:id (keyword (str (name bid) "-lbl"))
                                  :x 0.0 :y 6.0 :w 120.0 :h 16.0
                                  :text label :font-size 10.0 :color 0xFFFFFFFF}}]}]
    (let [^INode btn (rt/build-child! rt spec area)]
      (rt/register-event! rt (.getIdx btn) :left-click (fn [_ _ _] (on-click))))))

(defn- add-editable-field!
  [^UiRt rt y prefix value active? masked?]
  (let [^INode area (content-node rt)
        fid (keyword (str "ft-field-" (rand-int 100000)))
        display (if masked?
                  (apply str (repeat (count (str value)) "*"))
                  (str value))
        spec {:kind :text
              :props {:id fid :x 0.0 :y (double y) :w 360.0 :h 24.0
                      :text (str prefix display)
                      :font-size 9.0
                      :color (long (if active? active-color idle-color))
                      :editable? true
                      :masked? (boolean masked?)}}]
    (rt/build-child! rt spec area)))

(defn- do-scan! [state player-uuid rebuild!]
  (swap! state assoc :scanning? true :message ""
         :state-start-time (System/currentTimeMillis))
  (rebuild!)
  (net-client/send-to-server freq-scan-msg
    {:player-uuid player-uuid :range 4.0}
    (fn [response]
      (if (:success response)
        (let [dev (:device response)]
          (swap! state assoc
                 :phase :configure :device dev
                 :ssid (:ssid dev "") :password (:password dev "")
                 :scanning? false
                 :editing-field :ssid
                 :message (str "Found " (name (:type dev)))
                 :state-start-time (System/currentTimeMillis)))
        (swap! state assoc
               :scanning? false
               :message (or (:error response) "Scan failed")
               :state-start-time (System/currentTimeMillis)))
      (rebuild!))))

(defn- do-configure! [state player-uuid rebuild!]
  (swap! state assoc :message "Transmitting..."
         :state-start-time (System/currentTimeMillis))
  (rebuild!)
  (net-client/send-to-server freq-config-msg
    {:player-uuid player-uuid
     :ssid (:ssid @state)
     :password (:password @state)}
    (fn [response]
      (swap! state assoc :phase :result
             :message (if (:success response)
                        (or (:message response) "Configuration applied!")
                        (str "Failed: " (or (:error response) "Unknown error")))
             :state-start-time (System/currentTimeMillis))
      (rebuild!))))

(defn- build-scan-phase! [^UiRt rt state player-uuid rebuild!]
  (add-text! rt 5 "Look at a matrix or node block and click Scan.\nRange: 4 blocks.")
  (add-button! rt 60
    (if (:scanning? @state) "Scanning..." "Scan Target")
    (if (:scanning? @state) 0xFF555555 0xFF334455)
    #(when-not (:scanning? @state) (do-scan! state player-uuid rebuild!)))
  (when (and (:message @state) (not (:scanning? @state)))
    (add-text! rt 110 (:message @state) :color 0xFFFFAAAA :font-size 8.0)))

(defn- build-configure-phase! [^UiRt rt state player-uuid rebuild!]
  (when-let [dev (:device @state)]
    (let [info (str (name (:type dev))
                    (when (:ssid dev) (str " [" (:ssid dev) "]"))
                    (when (contains? dev :has-network)
                      (if (:has-network dev) " (active)" " (no network)"))
                    (when (contains? dev :linked?)
                      (if (:linked? dev) " (linked)" " (unlinked)")))]
      (add-text! rt 5 info :color 0xFFAADDFF)))
  (let [editing (:editing-field @state)
        ssid-prefix "SSID: "
        pass-prefix "Password: "
        ssid-n (add-editable-field! rt 40 ssid-prefix (:ssid @state) (= editing :ssid) false)
        pass-n (add-editable-field! rt 72 pass-prefix (:password @state) (= editing :password) true)]
    (doseq [[field prefix ^INode n] [[:ssid ssid-prefix ssid-n] [:password pass-prefix pass-n]]]
      (rt/register-event! rt (.getIdx n) :left-click
        (fn [_ _ _] (swap! state assoc :editing-field field) (rebuild!)))
      (rt/register-event! rt (.getIdx n) :confirm-input
        (fn [_ _ _] (swap! state assoc :editing-field nil) (rebuild!)))
      (rt/register-event! rt (.getIdx n) :change-content
        (fn [_ _ evt]
          (let [raw (:value evt)
                v (if (str/starts-with? (str raw) prefix)
                      (subs (str raw) (count prefix))
                      (str raw))]
            (if (= field :ssid)
              (swap! state assoc :ssid v)
              (swap! state assoc :password v)))))))
  (when (:editing-field @state)
    (add-text! rt 100 "Type to edit, Enter to confirm" :color 0xFF888888 :font-size 7.0))
  (add-button! rt 120 "Apply" 0xFF336633
    #(do (swap! state assoc :editing-field nil)
         (do-configure! state player-uuid rebuild!)))
  (add-button! rt 156 "Rescan" 0xFF443333
    #(do (swap! state assoc :phase :scan :device nil :message "" :editing-field nil
                 :state-start-time (System/currentTimeMillis))
         (rebuild!))))

(defn- build-result-phase! [^UiRt rt state rebuild!]
  (let [msg (or (:message @state) "")
        color (if (str/starts-with? msg "Failed") 0xFFFF8888 0xFF88FF88)]
    (add-text! rt 20 msg :color color :font-size 10.0)
    (add-button! rt 100 "Done" 0xFF334455
      #(do (swap! state assoc :phase :scan :device nil :message "" :editing-field nil
                   :state-start-time (System/currentTimeMillis))
           (rebuild!)))))

(defn- rebuild-content! [^UiRt rt state player-uuid]
  (clear-content! rt)
  (case (:phase @state)
    :scan (build-scan-phase! rt state player-uuid rebuild-content!)
    :configure (build-configure-phase! rt state player-uuid rebuild-content!)
    :result (build-result-phase! rt state rebuild-content!)
    nil)
  (rt/mark-tree-dirty! rt))

(defn- install-timeout-tick! [^UiRt rt state rebuild!]
  (rt/put-user-signal! rt :freq-timeout
    (sig/computed-do [(rt/clock-ms-sig rt)]
      (fn [_]
        (let [phase (:phase @state)
              elapsed (/ (- (System/currentTimeMillis) (long (:state-start-time @state))) 1000.0)]
          (when (and (> elapsed timeout-sec) (not= phase :result))
            (swap! state assoc :phase :scan :device nil :message "Timed out" :editing-field nil)
            (rebuild!)))
        nil))))

(defn create-runtime [player]
  (let [puuid (player-uuid player)
        r (rt/create-runtime)
        _ (rt/build! r (ui-xml/load-spec (modid/namespaced-path "guis/new/freq_transmitter.xml")))
        state (atom {:phase :scan :device nil :ssid "" :password "" :message ""
                     :scanning? false :editing-field nil
                     :state-start-time (System/currentTimeMillis)})]
    (install-timeout-tick! r state #(rebuild-content! r state puuid))
    (rebuild-content! r state puuid)
    r))

(defn open!
  ([player] (bridge/open-reactive-screen! (create-runtime player) "Freq Transmitter"))
  ([] (open! nil)))

(defn build-overlay-elements
  "Build overlay elements for frequency transmitter (non-modal passOn mode).
   Ported verbatim from the deleted freq_transmitter.clj."
  [_player-uuid _screen-width _screen-height]
  [{:kind :fill :x 0 :y 0 :w 640 :h 480 :color 0xC0202020}
   {:kind :text :text "Frequency Transmitter (Overlay)" :x 200 :y 10 :color 0xFFFFFFFF}
   {:kind :text :text "Press ESC to close" :x 200 :y 30 :color 0xFF888888}])
