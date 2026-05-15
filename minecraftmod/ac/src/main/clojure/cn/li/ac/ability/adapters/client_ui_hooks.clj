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
            [cn.li.ac.ability.model.preset :as preset-data]
            [cn.li.ac.ability.service.registry :as skill]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.service.player-state :as ps]
            [cn.li.ac.ability.util.toggle :as toggle]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.ability.catalog :as catalog]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log]))

(defonce-guard client-push-handlers-registered?)
(defonce ^:private vm-wave-circles (atom []))
(defonce ^:private vm-wave-last-spawn-ms (atom 0))

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
    (vec (concat (keep identity [cp-bar overload-bar activation-indicator preset-indicator overload-pulse])
                 skill-slots))))

(defn build-client-overlay-plan [player-uuid screen-width screen-height overlay-state]
  (let [player-state (ps/get-player-state player-uuid)
        activated-override {:value (if (contains? overlay-state :activated-override)
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
    {:elements (vec (concat base-elements vm-wave (keep identity [crosshair])))}))

(defn- on-context-channel-push! [{:keys [ctx-id channel payload]}]
  (fx-registry/dispatch-fx-channel! ctx-id channel payload)
  (when (= channel :location-teleport/ui-open)
    (location-teleport-screen/apply-server-payload! payload)
    (client-bridge/open-location-teleport-screen! nil payload))
  (ctx/ctx-send-to-local! ctx-id channel payload))

(defn register-client-push-handlers!
  []
  (with-init-guard client-push-handlers-registered?
    (net-client/register-push-handler! catalog/MSG-SYNC-ABILITY
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
        (ctx/transition-to-alive! ctx-id server-id nil)))
    (net-client/register-push-handler! catalog/MSG-CTX-TERMINATE
      (fn [{:keys [ctx-id]}]
        (ctx/terminate-context! ctx-id nil)))
    (net-client/register-push-handler! catalog/MSG-CTX-TERMINATED
      (fn [{:keys [ctx-id]}]
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
   (fn [ctx-id reason]
     (ctx/terminate-context! ctx-id reason))

   :client-transition-to-alive!
   (fn [ctx-id server-id payload]
     (ctx/transition-to-alive! ctx-id server-id payload))

   :client-send-context-local!
   (fn [ctx-id channel payload]
     (ctx/ctx-send-to-local! ctx-id channel payload))

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

   :client-trigger-mode-switch!
   (fn [player-uuid]
     (client-keybinds/trigger-mode-switch! player-uuid))

   :client-trigger-preset-switch!
   (fn [player-uuid]
     (client-keybinds/switch-preset! player-uuid))})
