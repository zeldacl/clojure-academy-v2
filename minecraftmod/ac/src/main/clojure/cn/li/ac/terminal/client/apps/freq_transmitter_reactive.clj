(ns cn.li.ac.terminal.client.apps.freq-transmitter-reactive
  "Frequency Transmitter state machine on Presentation Runtime.

   The same typed network operations are used by the modal Screen and the
   non-modal world interaction stage."
  (:require [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.client.toast :as toast]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.gui.presentation-application :as application]
            [cn.li.ac.terminal.freq-network :as freq-net]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.i18n :as i18n]
            [cn.li.mcmod.network.client :as net-client]))

(def ^:private normal-timeout-ms 20000)
(def ^:private transmitting-timeout-ms 3000)
(defonce ^:private overlay-sessions (atom {}))

(defn- net-owner [] (runtime-hooks/default-client-owner))
(defn- player-uuid [player]
  (or (uuid/player-uuid player)
      (:player-uuid (runtime-hooks/default-client-owner))))
(defn- local [key fallback]
  (or (i18n/translate (str "app." modid/MOD-ID ".freq_transmitter." key)) fallback))

(defn- show-messages! [response]
  (doseq [m (:messages response)]
    (toast/show-toast! {:message-key (:key m) :args (:args m)})))

(defn- notice! [state message return-phase]
  (swap! state assoc :phase :notice :message message :return-phase return-phase
         :phase-start-ms (System/currentTimeMillis)
         :timeout-ms (if return-phase 700 1000)))

(defn- finish-overlay! [puuid]
  (swap! overlay-sessions dissoc (str puuid))
  (bridge/set-active-overlay-app! nil puuid))

(defn- state-lines [state]
  (let [{:keys [phase source-type source-name message]} @state]
    (case phase
      :start [{:label (local "s0_0" "Aim at a wireless matrix or node.")}]
      :authorize [{:label (str (if (= :matrix source-type) "SSID: " "NAME: ") source-name)}
                  {:label (local "s1_0" "Enter the wireless password")}
                  {:label (str "Password: " (or (:password @state) ""))}]
      :link [{:label (if (= :matrix source-type)
                       (local "s2_0" "Aim at a wireless node to link.")
                       (local "s3_0" "Aim at a wireless user to link."))}]
      :transmitting [{:label (or message (local "s1_1" "Transmitting..."))}]
      :notice [{:label (or message "")}]
      [])))

(defn- refresh-state! [state refresh!]
  (refresh! {:lines (state-lines state)
             :status (name (:phase @state))
             :password (:password @state)}))

(defn- start-link-overlay! [puuid state]
  (swap! overlay-sessions assoc (str puuid)
         {:phase :link :source-type (:source-type @state)
          :source-pos (:source-pos @state) :password (:password @state)
          :last-click? false :phase-start-ms (System/currentTimeMillis)
          :timeout-ms normal-timeout-ms})
  (bridge/set-active-overlay-app! :freq-tx puuid)
  (bridge/close-screen!))

(defn- scan-source! [state puuid refresh!]
  (swap! state assoc :phase :transmitting :message (local "s1_1" "Transmitting...")
         :phase-start-ms (System/currentTimeMillis)
         :timeout-ms transmitting-timeout-ms)
  (refresh-state! state refresh!)
  (net-client/send-to-server (net-owner) freq-net/freq-scan-msg
    {:player-uuid puuid :range 4.0}
    (fn [response]
      (if (:success response)
        (let [{:keys [type pos ssid node-name]} (:device response)]
          (if (contains? #{:matrix :node} type)
            (swap! state assoc :phase :authorize :source-type type :source-pos pos
                   :source-name (or ssid node-name "") :password ""
                   :phase-start-ms (System/currentTimeMillis)
                   :timeout-ms normal-timeout-ms)
            (notice! state (local "e4" "Invalid wireless target") nil)))
        (notice! state (or (:error response) (local "e4" "Invalid wireless target")) nil))
      (refresh-state! state refresh!))))

(defn- authorize! [state puuid refresh!]
  (swap! state assoc :phase :transmitting :message (local "s1_1" "Transmitting...")
         :phase-start-ms (System/currentTimeMillis)
         :timeout-ms transmitting-timeout-ms)
  (refresh-state! state refresh!)
  (net-client/send-to-server (net-owner) freq-net/freq-config-msg
    {:operation :authorize :source-type (:source-type @state)
     :source-pos (:source-pos @state) :password (:password @state)}
    (fn [response]
      (if (:success response)
        (start-link-overlay! puuid state)
        (notice! state (local "e1" "Authorization failed") nil))
      (refresh-state! state refresh!))))

(defn- link-target! [state puuid refresh!]
  (swap! state assoc :phase :transmitting :message (local "e5" "Transmitting...")
         :phase-start-ms (System/currentTimeMillis)
         :timeout-ms transmitting-timeout-ms)
  (refresh-state! state refresh!)
  (net-client/send-to-server (net-owner) freq-net/freq-config-msg
    {:operation :link-target :source-type (:source-type @state)
     :source-pos (:source-pos @state) :password (:password @state)}
    (fn [response]
      (show-messages! response)
      (if (:success response)
        (notice! state (local "e6" "Link successful") :link)
        (notice! state (or (:error response)
                           (if (= :matrix (:source-type @state))
                             (local "e2" "Failed to link node")
                             (local "e3" "Failed to link wireless user"))) nil))
      (refresh-state! state refresh!))))

(defn open!
  ([player]
   (let [puuid (player-uuid player)
         state (atom {:phase :start :password ""
                      :phase-start-ms (System/currentTimeMillis)
                      :timeout-ms normal-timeout-ms})
         refresh-fn* (atom nil)
         refresh-ui! (fn []
                       (when-let [refresh! @refresh-fn*]
                         (refresh! {:lines (state-lines state)
                                    :status (name (:phase @state))
                                    :input (or (:password @state) "")})))
         vm (application/mount!
              (str "application/freq/" puuid)
              "Frequency Transmitter"
              {:lines (state-lines state) :status "start" :input ""
               :button-left {:label "Select Target" :visible? true}}
              (fn [action current]
                (when (= action :application/input)
                  (swap! state assoc :password (str (:input current ""))))
                (case [(:phase @state) action]
                  [:start :application/activate] (scan-source! state puuid (fn [_] (refresh-ui!)))
                  [:authorize :application/activate] (authorize! state puuid (fn [_] (refresh-ui!)))
                  [:link :application/activate] (link-target! state puuid (fn [_] (refresh-ui!)))
                  nil)
                {:lines (state-lines state) :status (name (:phase @state))})
              #(bridge/close-screen!))]
     (reset! refresh-fn* (:refresh! vm))
     vm))
  ([] (open! nil)))

(defn build-overlay-elements [player-uuid screen-width screen-height]
  (when-let [{:keys [phase source-type message]} (get @overlay-sessions (str player-uuid))]
    (let [prompt (case phase
                   :transmitting (local "e5" "Transmitting...")
                   :notice message
                   (if (= source-type :matrix)
                     (local "s2_0" "Aim at a wireless node and right-click.")
                     (local "s3_0" "Aim at a wireless user and right-click.")))
          title (or (i18n/translate (str "app." modid/MOD-ID ".freq_transmitter"))
                    "Frequency Transmitter")
          box-w 180 x (+ (/ screen-width 2.0) 10) y (+ (/ screen-height 2.0) 10)]
      [{:kind :fill :x 15 :y 15 :w 30 :h 18 :color 0x77272727}
       {:kind :texture :src (modid/asset-path "textures/guis/apps" "freq_transmitter/icon.png")
        :x 17 :y 15 :w 18 :h 18 :color 0xFFFFFFFF}
       {:kind :text :text title :x 39 :y 19 :color 0xFFFFFFFF}
       {:kind :fill :x x :y y :w box-w :h 28 :color 0x77272727}
       {:kind :text :text prompt :x (+ x 5) :y (+ y 8) :color 0xFFFFFFFF}])))

(defn overlay-active? [player-uuid]
  (contains? @overlay-sessions (str player-uuid)))

(defn- overlay-link! [player-uuid session]
  (let [key (str player-uuid)]
    (swap! overlay-sessions assoc key
           (assoc session :phase :transmitting
                  :phase-start-ms (System/currentTimeMillis)
                  :timeout-ms transmitting-timeout-ms))
    (net-client/send-to-server (net-owner) freq-net/freq-config-msg
      {:operation :link-target :source-type (:source-type session)
       :source-pos (:source-pos session) :password (:password session)}
      (fn [response]
        (show-messages! response)
        (swap! overlay-sessions assoc key
               (assoc session :phase :notice
                      :message (if (:success response)
                                 (local "e6" "Link successful")
                                 (or (:error response) (local "e2" "Link failed")))
                      :return-phase (when (:success response) :link)
                      :phase-start-ms (System/currentTimeMillis)
                       :timeout-ms (if (:success response) 700 1000)))))))

(defn tick-overlay-input! [player-uuid right-click-down?]
  (let [key (str player-uuid)]
    (when-let [session (get @overlay-sessions key)]
      (let [now (System/currentTimeMillis)
            elapsed (- now (:phase-start-ms session))
            expired? (> elapsed (:timeout-ms session))]
        (cond
          (and (= :notice (:phase session)) expired? (:return-phase session))
          (swap! overlay-sessions assoc key
                 (assoc session :phase :link :return-phase nil
                        :phase-start-ms now :timeout-ms normal-timeout-ms))
          (and (= :notice (:phase session)) expired?) (finish-overlay! player-uuid)
          (and (not= :notice (:phase session)) expired?) (finish-overlay! player-uuid)
          (and (= :link (:phase session)) right-click-down?
               (not (:last-click? session))) (overlay-link! player-uuid session)
          :else (swap! overlay-sessions assoc-in [key :last-click?]
                       (boolean right-click-down?)))))))
