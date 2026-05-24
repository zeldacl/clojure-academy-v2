(ns cn.li.ac.ability.adapters.client-ui-hooks
  "Client HUD/screen/context hook composition for AC ability platform bridge."
  (:require [cn.li.ac.ability.client.api :as client-api]
            [cn.li.ac.ability.client.delegate-state :as delegate-state]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.hud :as hud-renderer]
            [cn.li.ac.ability.client.keybinds :as client-keybinds]
            [cn.li.ac.ability.client.screens.location-teleport :as location-teleport-screen]
            [cn.li.ac.ability.client.screens.preset-editor :as preset-editor-screen]
            [cn.li.ac.ability.client.screens.skill-tree :as skill-tree-screen]
            [cn.li.ac.content.ability.electromaster.current-charging-fx :as current-charging-fx]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.server.service.context-mgr :as ctx-mgr]
            [cn.li.ac.ability.model.preset :as preset-data]
            [cn.li.ac.ability.service.registry :as skill]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.service.player-state :as ps]
            [cn.li.ac.config.gameplay :as gameplay]
            [cn.li.ac.ability.util.toggle :as toggle]
            [cn.li.mcmod.hooks.catalog :as catalog]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log]))

(defonce ^:private client-push-handlers-registered? (atom false))
(defonce ^:private vm-wave-circles (atom []))
(defonce ^:private vm-wave-last-spawn-ms (atom 0))
(defonce ^:private slot-context-ids (atom {}))
;; Per-slot last key-tick send timestamp (ms). Limits key-tick messages to ~10/s.
(defonce ^:private slot-key-tick-ms (atom {}))
(defonce ^:private charge-coin-state (atom {}))

(defn- now-ms [] (System/currentTimeMillis))

(defn- railgun-charge-item-max-ticks []
  (skill-config/tunable-int :railgun :charge.item-charge-ticks))

(defn- railgun-coin-active-threshold []
  (skill-config/tunable-double :railgun :qte.coin-active-threshold))

(defn- railgun-coin-window-ms []
  (skill-config/tunable-int :railgun :qte.coin-window-ms))

(defn- notify-charge-coin-throw!
  [player-uuid]
  (swap! charge-coin-state assoc player-uuid
         {:start-ms (now-ms)
          :window-ms (max 1 (long (railgun-coin-window-ms)))}))

(defn- charge-coin-visual-state
  [player-uuid]
  (let [contexts (ctx/get-all-contexts-for-player player-uuid)
        railgun-ctx (some (fn [ctx-data]
                            (when (= :railgun (:skill-id ctx-data))
                              ctx-data))
                          contexts)
        skill-state (:skill-state railgun-ctx)
        mode (:mode skill-state)
        charge-ticks (max 0 (int (or (:charge-ticks skill-state) 0)))
        max-charge-ticks (max 1 (int (railgun-charge-item-max-ticks)))]
    (if (= mode :item-charge)
      {:active? true
       :charge-ticks charge-ticks
       :coin-active? false
       :coin-progress 0.0
       :charge-ratio (max 0.0 (min 1.0 (- 1.0 (/ (double charge-ticks) max-charge-ticks))))}
      (let [{:keys [start-ms window-ms]} (get @charge-coin-state player-uuid)
            has-window? (and start-ms window-ms)
            elapsed (if has-window? (- (long (now-ms)) (long start-ms)) 0)
            progress (if has-window?
                       (/ (double (max 0 elapsed)) (double (max 1 (long window-ms))))
                       0.0)
            active-window? (and has-window? (<= progress 1.0))
            ratio (max 0.0 (min 1.0 progress))
            coin-active? (and active-window? (>= ratio (railgun-coin-active-threshold)))]
        (when (and has-window? (not active-window?))
          (swap! charge-coin-state dissoc player-uuid))
        {:active? active-window?
         :charge-ticks 0
         :coin-active? coin-active?
         :coin-progress ratio
         :charge-ratio ratio}))))

(defn- find-player-context
  [player-uuid skill-id]
  (some (fn [ctx-data]
          (when (= skill-id (:skill-id ctx-data))
            ctx-data))
        (ctx/get-all-contexts-for-player player-uuid)))

(defn- hold-ticks-from-context
  [ctx-data]
  (max 0 (long (or (get-in ctx-data [:skill-state :hold-ticks])
                   (:hold-ticks ctx-data)
                   0))))

(defn- body-intensify-visual-state
  [player-uuid]
  (let [ctx-data (find-player-context player-uuid :body-intensify)
        hold-ticks (hold-ticks-from-context ctx-data)
        max-ticks (max 1 (long (skill-config/tunable-int :body-intensify :charge.max-time)))]
    {:active? (boolean ctx-data)
     :charge-ticks hold-ticks
     :charge-ratio (max 0.0 (min 1.0 (/ (double hold-ticks) (double max-ticks))))}))

(defn- current-charging-visual-state
  [_player-uuid]
  (current-charging-fx/current-state))

(defn- current-charging-overlay-elements
  [screen-width screen-height]
  (let [{:keys [active? blending? is-item good? charge-ticks charge-ratio]} (current-charging-fx/current-state)
        visible? (or active? blending? (pos? (long (or charge-ticks 0))))]
    (when visible?
      (let [bar-width 140
            bar-height 8
            x (int (/ (- screen-width bar-width) 2))
            y (- screen-height 34)
            fill-width (max 2 (int (* bar-width (double (or charge-ratio 0.0)))))
            accent (if good?
                     {:r 90 :g 210 :b 255 :a 200}
                     {:r 255 :g 190 :b 90 :a 200})
            backdrop (if is-item
                       {:r 12 :g 24 :b 48 :a 150}
                       {:r 8 :g 18 :b 36 :a 150})]
        [{:kind :fullscreen-fill
          :color {:r 8 :g 18 :b 32 :a (if active? 110 55)}}
         {:kind :fill
          :x x :y y :w bar-width :h bar-height
          :color backdrop}
         {:kind :fill
          :x x :y y :w fill-width :h bar-height
          :color accent}
         {:kind :text
          :x (- (int (/ screen-width 2)) 55)
          :y (- y 12)
          :text (if is-item "Current Charging - Item" "Current Charging - Block")
          :color {:r 255 :g 255 :b 255 :a 240}}
         {:kind :fill
          :x (- (int (/ screen-width 2)) 2)
          :y (- (int (/ screen-height 2)) 8)
          :w 4 :h 16
          :color {:r 120 :g 220 :b 255 :a (if active? 200 120)}}
         {:kind :fill
          :x (- (int (/ screen-width 2)) 8)
          :y (- (int (/ screen-height 2)) 2)
          :w 16 :h 4
          :color {:r 120 :g 220 :b 255 :a (if active? 200 120)}}]))))

(defn- slot-context-key [player-uuid key-idx]
  [player-uuid key-idx])

(defn- remove-slot-context! [ctx-id]
  (swap! slot-context-ids
         (fn [m]
           (into {}
                 (remove (fn [[_slot-key active-ctx-id]]
                           (= active-ctx-id ctx-id))
                         m)))))

(defn- context-id-for-slot!
  [player-uuid key-idx skill-id]
  (let [slot-key (slot-context-key player-uuid key-idx)]
    (or (get @slot-context-ids slot-key)
        (let [ctx-map (ctx-mgr/activate-context! player-uuid skill-id)
              ctx-id (:id ctx-map)]
          (swap! slot-context-ids assoc slot-key ctx-id)
          ctx-id))))

(defn- send-slot-key-message!
  [msg-id player-uuid key-idx]
  (when-let [skill-id (client-keybinds/get-skill-id-for-slot-public player-uuid key-idx)]
    (when-let [ctx-id (context-id-for-slot! player-uuid key-idx skill-id)]
      (net-client/send-to-server msg-id {:ctx-id ctx-id
                                         :skill-id skill-id
                                         :key-idx key-idx})
      ctx-id)))

(defn- send-slot-key-up-message!
  [player-uuid key-idx]
  (let [slot-key (slot-context-key player-uuid key-idx)]
    (when-let [ctx-id (get @slot-context-ids slot-key)]
      (net-client/send-to-server catalog/MSG-SLOT-KEY-UP {:ctx-id ctx-id
                                                          :key-idx key-idx})
      (swap! slot-context-ids dissoc slot-key)
      ctx-id)))

(defn- flush-buffered-context-message!
  [ctx-id {:keys [channel payload]}]
  (net-client/send-to-server catalog/MSG-CTX-CHANNEL {:ctx-id ctx-id
                                                      :channel channel
                                                      :payload payload}))

(defn- send-slot-wheel-message!
  [player-uuid key-idx delta]
  (when (and (gameplay/use-mouse-wheel-enabled?)
             (number? delta)
             (not (zero? (double delta))))
    (let [slot-key (slot-context-key player-uuid key-idx)
          ctx-id (get @slot-context-ids slot-key)
          skill-id (client-keybinds/get-skill-id-for-slot-public player-uuid key-idx)]
      (when (and ctx-id (= skill-id :penetrate-teleport))
        (net-client/send-to-server catalog/MSG-CTX-CHANNEL
                                   {:ctx-id ctx-id
                                    :channel :penetrate-tp/set-distance
                                    :payload {:delta (double delta)}})))))

(defn- active-flashing-context-ids
  [player-uuid]
  (->> @slot-context-ids
       (keep (fn [[[slot-player-uuid _] ctx-id]]
               (when (= slot-player-uuid player-uuid)
                 (let [ctx-data (ctx/get-context ctx-id)]
                   (when (= :flashing (:skill-id ctx-data))
                     ctx-id)))))
       distinct
       vec))

(defn- send-flashing-movement-message!
  [player-uuid channel movement-key]
  (doseq [ctx-id (active-flashing-context-ids player-uuid)]
    (net-client/send-to-server catalog/MSG-CTX-CHANNEL
                               {:ctx-id ctx-id
                                :channel channel
                                :payload {:key movement-key}})))

(defn- vec-reflection-active?
  [player-uuid]
  (boolean
    (some (fn [[_ctx-id ctx-data]]
            (and (= (:player-uuid ctx-data) player-uuid)
                 (toggle/is-toggle-active? ctx-data :vec-reflection)))
          (ctx/get-all-contexts))))

(defn- vec-deviation-active?
  [player-uuid]
  (boolean
    (some (fn [[_ctx-id ctx-data]]
            (and (= (:player-uuid ctx-data) player-uuid)
                 (toggle/is-toggle-active? ctx-data :vec-deviation)))
          (ctx/get-all-contexts))))

(defn- ease-in-out [t]
  (if (< t 0.5)
    (* 2.0 t t)
    (- 1.0 (* 2.0 (- 1.0 t) (- 1.0 t)))))

(defn- spawn-vm-wave-circle [screen-width screen-height now-ms]
  (let [cx (/ (double screen-width) 2.0)
        cy (/ (double screen-height) 2.0)
        offset-r (+ 8.0 (* (rand) 42.0))
        angle (* (rand) 2.0 Math/PI)
        life-ms (+ 520 (rand-int 260))
        start-size (+ 8.0 (* (rand) 6.0))
        end-size (+ 36.0 (* (rand) 32.0))]
    {:x (+ cx (* offset-r (Math/cos angle)))
     :y (+ cy (* offset-r (Math/sin angle)))
     :born-ms now-ms
     :life-ms life-ms
     :start-size start-size
     :end-size end-size
     :seed (rand)}))

(defn- update-vm-wave-circles! [active? screen-width screen-height now-ms]
  (swap! vm-wave-circles
         (fn [circles]
           (let [alive (->> circles
                            (filter (fn [{:keys [born-ms life-ms]}]
                                      (< (- now-ms (long born-ms)) (long life-ms))))
                            vec)
                 needs-spawn? (and active?
                                   (>= (- now-ms (long @vm-wave-last-spawn-ms)) 90))
                 spawned (if needs-spawn?
                           (conj alive (spawn-vm-wave-circle screen-width screen-height now-ms))
                           alive)]
             (when needs-spawn?
               (reset! vm-wave-last-spawn-ms now-ms))
             (if active? spawned (if (seq spawned) spawned []))))))

(defn- vm-wave-elements [now-ms]
  (->> @vm-wave-circles
       (map (fn [{:keys [x y born-ms life-ms start-size end-size seed]}]
              (let [elapsed (double (max 0 (- now-ms (long born-ms))))
                    life (double (max 1 life-ms))
                    t (min 1.0 (/ elapsed life))
                    s (+ start-size (* (- end-size start-size) t))
                    fade-in (min 1.0 (/ t 0.2))
                    fade-out (if (> t 0.6) (/ (- 1.0 t) 0.4) 1.0)
                    pulse (+ 0.85 (* 0.15 (Math/sin (+ (* t 12.0) (* seed Math/PI)))))
                    alpha (* 0.72 (ease-in-out t) fade-in fade-out pulse)
                    hs (/ s 2.0)]
                {:kind :blit-texture
                 :texture "my_mod:textures/effects/glow_circle.png"
                 :x (int (- x hs))
                 :y (int (- y hs))
                 :w (int s)
                 :h (int s)
                 :alpha (double (max 0.0 (min 1.0 alpha)))})))
       (filter #(pos? (:alpha %)))
       vec))

(defn- build-hud-model-from-state [player-state activated-override]
  (when player-state
    (let [resource-data (:resource-data player-state)
          preset-data-map (:preset-data player-state)
          activated (if (contains? activated-override :value)
                      (:value activated-override)
                      (boolean (:activated resource-data)))]
      {:cp {:cur (double (or (:cur-cp resource-data) 0.0))
            :max (double (or (:max-cp resource-data) 1.0))}
       :overload {:cur (double (or (:cur-overload resource-data) 0.0))
                  :max (double (or (:max-overload resource-data) 1.0))
                  :fine (boolean (get resource-data :overload-fine true))}
       :active-slots (vec (preset-data/get-active-slots preset-data-map))
       :activated activated})))

(defn- hud-render-data->overlay-elements [hud-render-data screen-width screen-height]
  (let [cp-bar (some-> (:cp-bar hud-render-data) (assoc :kind :bar) (dissoc :type))
        overload-bar (some-> (:overload-bar hud-render-data) (assoc :kind :bar) (dissoc :type))
        activation-indicator (some-> (:activation-indicator hud-render-data)
                                     (assoc :kind :activation-indicator)
                                     (dissoc :type))
      combat-notice (some-> (:combat-notice hud-render-data)
            (assoc :kind :text)
            (dissoc :type))
        skill-slots (mapv (fn [slot] (-> slot (assoc :kind :skill-slot) (dissoc :type)))
                          (or (:skill-slots hud-render-data) []))
        preset-indicator (some-> (:preset-indicator hud-render-data)
                                 (assoc :kind :preset-indicator
                                        :x (int (/ screen-width 2))
                                        :y (- screen-height 60))
                                 (dissoc :type))
        overload-pulse (when-let [ol-bar (:overload-bar hud-render-data)]
                         (let [pct (double (or (:percent ol-bar) 0.0))]
                           (when (> pct 0.8)
                             {:kind :overload-pulse
                              :intensity (* (- pct 0.8) 5.0)})))]
                (vec (concat (keep identity [cp-bar overload-bar activation-indicator combat-notice preset-indicator overload-pulse])
                 skill-slots))))

(defn build-client-overlay-plan [player-uuid screen-width screen-height overlay-state]
  (let [player-state (ps/get-player-state player-uuid)
        activated-override {:value (if (some? (:activated-override overlay-state))
                                     (boolean (:activated-override overlay-state))
                                     (boolean (get-in player-state [:resource-data :activated] false)))}
        hud-model (build-hud-model-from-state player-state activated-override)
        cooldown-data (:cooldown-data player-state)
        activate-hint (client-keybinds/get-activate-hint player-uuid)
        preset-state (client-keybinds/get-preset-switch-state)
        hud-render-data (hud-renderer/build-hud-render-data
                         hud-model screen-width screen-height cooldown-data
                         :player-uuid player-uuid
                         :activate-hint activate-hint
                         :preset-state preset-state
                         :now-ms (long (or (:now-ms overlay-state) (System/currentTimeMillis))))
        base-elements (hud-render-data->overlay-elements hud-render-data screen-width screen-height)
        current-charging-elements (current-charging-overlay-elements screen-width screen-height)
        reflection-active? (vec-reflection-active? player-uuid)
        deviation-active? (vec-deviation-active? player-uuid)
        vm-wave-active? (or reflection-active? deviation-active?)
        now-ms (long (or (:now-ms overlay-state) (System/currentTimeMillis)))
        phase (double (/ (mod now-ms 1200) 1200.0))
        _ (update-vm-wave-circles! vm-wave-active? screen-width screen-height now-ms)
        vm-wave (vm-wave-elements now-ms)
        crosshair (when reflection-active?
                    {:kind :vec-reflection-crosshair
                     :x (int (/ screen-width 2))
                     :y (int (/ screen-height 2))
                     :phase phase
                     :intensity 1.0})]
    {:elements (vec (concat base-elements current-charging-elements vm-wave (keep identity [crosshair])))}))

(defn- on-context-channel-push! [{:keys [ctx-id channel payload]}]
  (fx-registry/dispatch-fx-channel! ctx-id channel payload)
  (when (= channel :location-teleport/ui-open)
    (location-teleport-screen/apply-server-payload! payload)
    (client-bridge/open-location-teleport-screen! nil payload))
  (ctx/ctx-send-to-local! ctx-id channel payload))

(defn register-client-push-handlers!
  []
  (when (compare-and-set! client-push-handlers-registered? false true)
    (net-client/register-push-handler! catalog/MSG-SYNC-RUNTIME
      (fn [{:keys [uuid ability-data]}]
        (when (and uuid ability-data)
          (ps/get-or-create-player-state! uuid)
          (ps/update-ability-data! uuid (constantly ability-data)))))
    (net-client/register-push-handler! catalog/MSG-SYNC-RESOURCE
      (fn [{:keys [uuid resource-data]}]
        (when (and uuid resource-data)
          (ps/get-or-create-player-state! uuid)
          (ps/update-resource-data! uuid (constantly resource-data)))))
    (net-client/register-push-handler! catalog/MSG-SYNC-COOLDOWN
      (fn [{:keys [uuid cooldown-data]}]
        (when (and uuid cooldown-data)
          (ps/get-or-create-player-state! uuid)
          (ps/update-cooldown-data! uuid (constantly cooldown-data)))))
    (net-client/register-push-handler! catalog/MSG-SYNC-PRESET
      (fn [{:keys [uuid preset-data]}]
        (when (and uuid preset-data)
          (ps/get-or-create-player-state! uuid)
          (ps/update-preset-data! uuid (constantly preset-data)))))
    (net-client/register-push-handler! catalog/MSG-CTX-ESTABLISH
      (fn [{:keys [ctx-id server-id]}]
        (ctx/transition-to-alive! ctx-id server-id (partial flush-buffered-context-message! ctx-id))))
    (net-client/register-push-handler! catalog/MSG-CTX-TERMINATE
      (fn [{:keys [ctx-id]}]
        (remove-slot-context! ctx-id)
        (ctx/terminate-context! ctx-id nil)))
    (net-client/register-push-handler! catalog/MSG-CTX-TERMINATED
      (fn [{:keys [ctx-id]}]
        (remove-slot-context! ctx-id)
        (ctx/terminate-context! ctx-id nil)))
    (net-client/register-push-handler! catalog/MSG-CTX-CHANNEL on-context-channel-push!)
    (log/info "Ability client push handlers registered")))

    (defn- build-preset-editor-draw-ops
      []
      (if-let [render-data (preset-editor-screen/build-preset-editor-render-data)]
        (let [selected-preset (:selected-preset render-data)
          active-preset (:active-preset render-data)
          selected-skill (:selected-skill render-data)]
      (vec
       (concat
        [{:kind :text :text "Preset Editor" :x 10 :y 2 :color 0xFFFFFF}]
        (mapcat (fn [preset-idx]
          (let [x (+ 10 (* preset-idx 45))
            selected? (= preset-idx selected-preset)
            active? (= preset-idx active-preset)]
            [{:kind :fill :x x :y 10 :w 40 :h 20 :color (if selected? 0xFF4C6FFF 0xFF333333)}
             {:kind :text :text (str "P" (inc preset-idx) (when active? "*"))
              :x (+ x 10) :y 16 :color 0xFFFFFF}]))
            (:presets render-data))
        (mapcat (fn [idx]
          (let [slot (nth (:slots render-data) idx nil)
            y (+ 40 (* idx 25))]
            [{:kind :fill :x 10 :y y :w 100 :h 20 :color 0xFF252525}
             {:kind :text :text (str "Slot " (inc idx) ": " (or (:skill-name slot) "<empty>"))
              :x 14 :y (+ y 6) :color 0xFFFFFF}]))
            (range 4))
        (mapcat (fn [[idx skill-info]]
          (let [y (+ 60 (* idx 22))
            chosen? (= (:skill-id skill-info) selected-skill)]
            [{:kind :fill :x 170 :y y :w 150 :h 20 :color (if chosen? 0xFF2E6B2E 0xFF202020)}
             {:kind :text :text (:skill-name skill-info) :x 174 :y (+ y 6) :color 0xFFFFFF}]))
            (map-indexed vector (:available-skills render-data)))
        [{:kind :fill :x 10 :y 200 :w 80 :h 20 :color (if (:has-changes render-data) 0xFF4A8F4A 0xFF444444)}
          {:kind :text :text "Save" :x 35 :y 206 :color 0xFFFFFF}
          {:kind :fill :x 100 :y 200 :w 80 :h 20 :color 0xFF444488}
          {:kind :text :text "Set Active" :x 108 :y 206 :color 0xFFFFFF}])) )
        []))

(defn runtime-client-ui-hooks
  []
  {:client-get-skill-by-controllable
   (fn [cat-id ctrl-id]
     (skill/get-skill-by-controllable cat-id ctrl-id))

   :client-new-context
   (fn [player-uuid skill-id]
     (ctx/new-context player-uuid skill-id))

   :client-register-context!
   (fn [ctx-map]
     (ctx/register-context! ctx-map))

   :client-get-context
   (fn [ctx-id]
     (ctx/get-context ctx-id))

   :client-terminate-context!
   (fn [ctx-id _reason]
     (remove-slot-context! ctx-id)
     (ctx/terminate-context! ctx-id nil))

   :client-transition-to-alive!
   (fn [ctx-id server-id payload]
     (ctx/transition-to-alive! ctx-id server-id payload))

   :client-send-context-local!
   (fn [ctx-id channel payload]
     (ctx/ctx-send-to-local! ctx-id channel payload))

   :client-on-slot-key-down!
   (fn [player-uuid key-idx]
     ;; Reset tick throttle so the first key-tick after a key-down is never suppressed.
     (swap! slot-key-tick-ms dissoc [player-uuid key-idx])
     (send-slot-key-message! catalog/MSG-SLOT-KEY-DOWN player-uuid key-idx))

   :client-on-slot-key-tick!
   (fn [player-uuid key-idx]
     (let [slot-key [player-uuid key-idx]
           now-ms   (System/currentTimeMillis)
           last-ms  (get @slot-key-tick-ms slot-key 0)]
       (when (>= (- now-ms last-ms) 100)
         (swap! slot-key-tick-ms assoc slot-key now-ms)
         (send-slot-key-message! catalog/MSG-SLOT-KEY-TICK player-uuid key-idx))))

   :client-on-slot-key-up!
   (fn [player-uuid key-idx]
     (swap! slot-key-tick-ms dissoc [player-uuid key-idx])
     (send-slot-key-up-message! player-uuid key-idx))

   :client-on-movement-key-down!
   (fn [player-uuid movement-key]
     (send-flashing-movement-message! player-uuid :flashing/move-down movement-key))

   :client-on-movement-key-tick!
   (fn [player-uuid movement-key]
     (send-flashing-movement-message! player-uuid :flashing/move-tick movement-key))

   :client-on-movement-key-up!
   (fn [player-uuid movement-key]
     (send-flashing-movement-message! player-uuid :flashing/move-up movement-key))

   :client-on-slot-wheel!
   (fn [player-uuid key-idx delta]
     (send-slot-wheel-message! player-uuid key-idx delta))

   :client-abort-all!
   (fn []
     (doseq [ctx-id (vals @slot-context-ids)]
       (ctx/terminate-context! ctx-id nil))
     (reset! slot-context-ids {}))

   :client-update-ability-data!
   (fn [player-uuid ability-data]
     (ps/get-or-create-player-state! player-uuid)
     (ps/update-ability-data! player-uuid (constantly ability-data)))

   :client-update-resource-data!
   (fn [player-uuid resource-data]
     (ps/get-or-create-player-state! player-uuid)
     (ps/update-resource-data! player-uuid (constantly resource-data)))

   :client-update-cooldown-data!
   (fn [player-uuid cooldown-data]
     (ps/get-or-create-player-state! player-uuid)
     (ps/update-cooldown-data! player-uuid (constantly cooldown-data)))

   :client-update-preset-data!
   (fn [player-uuid preset-data]
     (ps/get-or-create-player-state! player-uuid)
     (ps/update-preset-data! player-uuid (constantly preset-data)))

   :client-build-hud-render-data
   (fn [hud-model screen-width screen-height cooldown-data]
     (hud-renderer/build-hud-render-data hud-model screen-width screen-height cooldown-data))

   :client-slot-visual-state
   (fn [player-uuid key-idx]
     (let [active-ctxs (ctx/get-all-contexts-for-player player-uuid)
           skill-id (client-keybinds/get-skill-id-for-slot-public player-uuid key-idx)]
       (:state (delegate-state/delegate-state-for-slot active-ctxs skill-id))))

   :client-build-overlay-plan
   (fn [player-uuid screen-width screen-height overlay-state]
     (build-client-overlay-plan player-uuid screen-width screen-height overlay-state))

   :client-req-learn-skill!
   (fn [skill-id extra callback]
     (client-api/req-learn-skill! skill-id extra callback))

   :client-req-level-up!
   (fn [callback]
     (client-api/req-level-up! callback))

   :client-req-set-activated!
   (fn [activated callback]
     (client-api/req-set-activated! activated callback))

   :client-req-set-preset-slot!
   (fn [preset-idx key-idx cat-id ctrl-id callback]
     (client-api/req-set-preset-slot! preset-idx key-idx cat-id ctrl-id callback))

   :client-req-switch-preset!
   (fn [preset-idx callback]
     (client-api/req-switch-preset! preset-idx callback))

   :client-open-skill-tree-screen!
   (fn [player-uuid learn-context]
     (skill-tree-screen/open-screen! player-uuid learn-context))

   :client-build-skill-tree-render-data
   (fn []
     (skill-tree-screen/build-screen-render-data))

   :client-build-skill-tree-draw-ops
   (fn [mouse-x mouse-y]
     (skill-tree-screen/build-draw-ops mouse-x mouse-y))

   :client-handle-skill-tree-hover!
   (fn [mouse-x mouse-y]
     (skill-tree-screen/on-mouse-move mouse-x mouse-y))

   :client-handle-skill-tree-click!
   (fn [mouse-x mouse-y]
     (skill-tree-screen/handle-screen-click! mouse-x mouse-y))

   :client-close-skill-tree-screen!
   (fn []
     (skill-tree-screen/close-screen!))

   :client-open-preset-editor-screen!
   (fn [player-uuid]
     (preset-editor-screen/open-screen! player-uuid))

   :client-build-preset-editor-render-data
   (fn []
     (preset-editor-screen/build-preset-editor-render-data))

   :client-build-preset-editor-draw-ops
   (fn []
     (build-preset-editor-draw-ops))

   :client-handle-preset-editor-click!
   (fn [mouse-x mouse-y]
     (preset-editor-screen/handle-screen-click! mouse-x mouse-y))

   :client-close-preset-editor-screen!
   (fn []
     (preset-editor-screen/close-screen!))

   :client-open-location-teleport-screen!
   (fn [player-uuid payload]
     (location-teleport-screen/open-screen! player-uuid payload))

   :client-build-location-teleport-draw-ops
   (fn [mouse-x mouse-y]
     (location-teleport-screen/build-draw-ops mouse-x mouse-y))

   :client-handle-location-teleport-hover!
   (fn [mouse-x mouse-y]
     (location-teleport-screen/on-mouse-move mouse-x mouse-y))

   :client-handle-location-teleport-click!
   (fn [mouse-x mouse-y]
     (location-teleport-screen/handle-screen-click! mouse-x mouse-y))

   :client-handle-location-teleport-char-typed!
   (fn [ch]
     (location-teleport-screen/handle-char-typed! ch))

   :client-close-location-teleport-screen!
   (fn []
     (location-teleport-screen/close-screen!))

   :client-open-saved-position-screen!
   (fn [player-uuid payload]
     (location-teleport-screen/open-screen! player-uuid payload))

   :client-build-saved-position-draw-ops
   (fn [mouse-x mouse-y]
     (location-teleport-screen/build-draw-ops mouse-x mouse-y))

   :client-handle-saved-position-hover!
   (fn [mouse-x mouse-y]
     (location-teleport-screen/on-mouse-move mouse-x mouse-y))

   :client-handle-saved-position-click!
   (fn [mouse-x mouse-y]
     (location-teleport-screen/handle-screen-click! mouse-x mouse-y))

   :client-handle-saved-position-char-typed!
   (fn [ch]
     (location-teleport-screen/handle-char-typed! ch))

   :client-close-saved-position-screen!
   (fn []
     (location-teleport-screen/close-screen!))

   :client-register-push-handlers!
   (fn []
     (register-client-push-handlers!))

   :client-notify-charge-coin-throw!
   (fn [player-uuid]
     (notify-charge-coin-throw! player-uuid))

   :client-charge-coin-visual-state
   (fn [player-uuid]
     (charge-coin-visual-state player-uuid))

   :client-body-intensify-charge-visual-state
   (fn [player-uuid]
     (body-intensify-visual-state player-uuid))

   :client-current-charging-visual-state
   (fn [player-uuid]
     (current-charging-visual-state player-uuid))

   :client-trigger-mode-switch!
   (fn [player-uuid]
     (client-keybinds/trigger-mode-switch! player-uuid))

   :client-trigger-preset-switch!
   (fn [player-uuid]
     (client-keybinds/switch-preset! player-uuid))})
