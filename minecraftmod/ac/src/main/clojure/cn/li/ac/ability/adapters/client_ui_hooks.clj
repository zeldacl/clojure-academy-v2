(ns cn.li.ac.ability.adapters.client-ui-hooks
  "Client HUD/screen/context hook composition for AC ability platform bridge."
  (:require 
            [cn.li.ac.ability.service.command-runtime :as command-rt]
[cn.li.ac.ability.service.runtime-store :as store]
[cn.li.ac.ability.client.api :as client-api]
            [cn.li.ac.ability.client.read-model :as read-model]
            [cn.li.ac.ability.client.combat-notice :as combat-notice]
            [cn.li.ac.ability.client.delegate-state :as delegate-state]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.hud :as hud-renderer]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.ability.client.keybinds :as client-keybinds]
            [cn.li.ac.ability.client.managed-screens :as managed-screens]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.content.ability.teleporter.location-teleport-screen :as location-teleport-screen]
            [cn.li.ac.ability.client.screens.preset-editor :as preset-editor-screen]
            [cn.li.ac.ability.client.screens.skill-tree :as skill-tree-screen]
            [cn.li.ac.content.ability.electromaster.current-charging-fx :as current-charging-fx]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.context-manager :as ctx-mgr]
            [cn.li.ac.ability.model.preset :as preset-data]
            [cn.li.ac.ability.registry.category :as category]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.util.resource-check :as resource-check]
            [cn.li.ac.config.gameplay :as gameplay]
            [cn.li.ac.ability.util.toggle :as toggle]
            [cn.li.ac.ability.messages :as catalog]
            [cn.li.ac.client.toast :as toast]
            [cn.li.ac.ability.client.debug-overlay :as debug-overlay]
            [cn.li.ac.tutorial.client.notification :as tutorial-notification]
            [cn.li.ac.terminal.client.apps.freq-transmitter :as freq-tx]
            [cn.li.ac.terminal.client.install-effect :as install-fx]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.i18n :as i18n]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.client.content-actions :as content-actions]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.mcmod.runtime.owner :as owner]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.util.log :as log]))

(def ^:private default-client-ui-runtime-state
  {:vm-wave-circles {}
   :vm-wave-last-spawn-ms {}
   :slot-context-ids {}
   :slot-key-tick-ms {}
  :charge-coin-state {}
  :push-handlers-registered? false
  ;; Overlay HUD reactive cache (see docs/dev plan "Overlay/HUD 响应式重构"):
  ;; Cache A — skill-slot shape (icon/name/key-label/position), keyed on preset-data identity.
  :overlay-skill-shape-cache {}
  ;; Cache B — context-derived data (delegate-state + consumption-hint), keyed on a
  ;; context-registry snapshot token, shared between the two consumers so the
  ;; (allocating) context scan runs at most once per real context change, not per-frame.
  :overlay-context-cache {}})

;; Client UI runtime — Framework [:service :client-ui]

(def ^:private cui-path [:service :client-ui])

(defn- client-ui-runtime-state-atom []
  (if-let [fw-atom (fw/fw-atom)]
    (or (get-in @fw-atom cui-path)
        (let [a (atom default-client-ui-runtime-state)]
          (swap! fw-atom assoc-in cui-path a) a))
    (atom default-client-ui-runtime-state)))

(defn- managed-screen-runtime []
  (managed-screens/create-managed-screen-runtime))

(defn call-with-managed-screen-runtime [f] (f))

(defn create-client-ui-runtime []
  {::runtime ::client-ui
   :state* (client-ui-runtime-state-atom)})

(defn call-with-client-ui-runtime
  [_runtime f]
  (f))

(defn- active-managed-screen-owner
  [screen-key]
  (managed-screens/active-owner
    (case screen-key
      :ac/skill-tree skill-tree-screen/screen-id
      :ac/preset-editor preset-editor-screen/screen-id
      :ac/saved-position location-teleport-screen/screen-id
      :ac/location-teleport location-teleport-screen/screen-id
      nil)))

(defn- client-ui-runtime-state-snapshot
  []
  @(client-ui-runtime-state-atom))

(defn- update-client-ui-runtime!
  [f & args]
  (apply swap! (client-ui-runtime-state-atom) f args))

(defn- vm-wave-circles-snapshot
  []
  (:vm-wave-circles (client-ui-runtime-state-snapshot)))

(defn- slot-context-ids-snapshot
  []
  (:slot-context-ids (client-ui-runtime-state-snapshot)))

(defn- slot-key-tick-ms-snapshot
  []
  (:slot-key-tick-ms (client-ui-runtime-state-snapshot)))

(defn- charge-coin-state-snapshot
  []
  (:charge-coin-state (client-ui-runtime-state-snapshot)))

(defn- overlay-skill-shape-cache-snapshot
  []
  (:overlay-skill-shape-cache (client-ui-runtime-state-snapshot)))

(defn- overlay-context-cache-snapshot
  []
  (:overlay-context-cache (client-ui-runtime-state-snapshot)))

(defn- current-client-session-id
  []
  (or client-keybinds/*client-session-id* (runtime-hooks/*client-session-id*)))

(defn- require-client-owner-value
  [owner label value]
  (if (some? value)
    value
    (throw (ex-info (format "Client UI owner requires %s" label)
                    {:owner owner
                     :required label}))))

(defn client-ui-owner-key
  [owner]
  (let [owner-map (cond
                    (vector? owner) owner
                    (map? owner) owner
                    (some? owner) {:player-uuid owner}
                    :else {})]
    (if (vector? owner-map)
      owner-map
       (let [session-id (or (:client-session-id owner-map)
                            (current-client-session-id))
             player-uuid (some-> (or (:player-uuid owner-map)
                                     (:uuid owner-map))
                                 str)]
         [(require-client-owner-value owner ":client-session-id" session-id)
          (require-client-owner-value owner ":player-uuid" player-uuid)]))))

(defn- with-client-owner-bindings
  [owner f]
  (let [[session-id player-uuid] (client-ui-owner-key owner)]
    (runtime-hooks/with-client-ctx {:session-id session-id}
      (runtime-hooks/with-player-state-owner {:client-session-id session-id
                                              :player-uuid player-uuid}
        (f)))))

(defn- with-client-player-state-owner
  [player-uuid f]
  (let [player-uuid* (require-client-owner-value {:player-uuid player-uuid}
                                                 ":player-uuid"
                                                 (some-> player-uuid str))]
    (let [session-id (require-client-owner-value {} ":client-session-id" (current-client-session-id))]
      (with-client-owner-bindings {:client-session-id session-id
                                   :player-uuid player-uuid*}
        #(f session-id player-uuid*)))))

(defn- client-ui-read-owner-key
  [player-uuid]
  (let [session-id (require-client-owner-value {} ":client-session-id" (current-client-session-id))
        player-uuid* (require-client-owner-value {:player-uuid player-uuid}
                                                 ":player-uuid"
                                                 (some-> player-uuid str))]
    [session-id :client-ui-hooks player-uuid*]))

(defn- get-client-player-state
  [player-uuid]
  (read-model/get-player-state (client-ui-read-owner-key player-uuid)))

(defn- ensure-client-player-state!
  [player-uuid]
  (read-model/ensure-player-state! (client-ui-read-owner-key player-uuid)))

(defn- update-client-ability-data!
  [player-uuid ability-data]
  (with-client-player-state-owner player-uuid
    (fn [session-id player-uuid*]
      (command-rt/run-command-in-session!
       session-id
       player-uuid*
       {:command :hydrate-player-state
        :ability-data ability-data}))))

(defn- update-client-resource-data!
  [player-uuid resource-data]
  (with-client-player-state-owner player-uuid
    (fn [session-id player-uuid*]
      (command-rt/run-command-in-session!
       session-id
       player-uuid*
       {:command :hydrate-player-state
        :resource-data resource-data}))))

(defn- update-client-cooldown-data!
  [player-uuid cooldown-data]
  (with-client-player-state-owner player-uuid
    (fn [session-id player-uuid*]
      (command-rt/run-command-in-session!
       session-id
       player-uuid*
       {:command :hydrate-player-state
        :cooldown-data cooldown-data}))))

(defn- update-client-preset-data!
  [player-uuid preset-data]
  (with-client-player-state-owner player-uuid
    (fn [session-id player-uuid*]
      (command-rt/run-command-in-session!
       session-id
       player-uuid*
       {:command :hydrate-player-state
        :preset-data preset-data}))))

(defn- validate-managed-screen-payload
  [screen-key payload]
  (let [payload (or payload {})
        player-uuid (require-client-owner-value payload ":player-uuid" (:player-uuid payload))
        session-id (require-client-owner-value payload ":client-session-id"
                                               (or (:client-session-id payload)
                                                   (current-client-session-id)))]
    (when (and (:client-session-id payload)
               (current-client-session-id)
               (not= (:client-session-id payload) (current-client-session-id)))
      (throw (ex-info "Managed screen payload session does not match current client session"
                      {:screen-key screen-key
                       :payload payload
                       :current-client-session-id (current-client-session-id)})))
    {:player-uuid player-uuid
     :client-session-id session-id
     :payload payload}))

(defn- client-context-owner
  [player-uuid]
  (let [[session-id player-uuid*] (client-ui-owner-key {:player-uuid player-uuid})]
    {:logical-side :client
     :client-session-id session-id
     :player-uuid player-uuid*}))

(defn- client-context-owner-from-owner
  [owner]
  (let [[session-id player-uuid] (client-ui-owner-key owner)]
    {:logical-side :client
     :client-session-id session-id
     :player-uuid player-uuid}))

(defn- player-contexts
  [player-uuid]
  (read-model/get-player-contexts-for-player (str player-uuid)
                                             (current-client-session-id)
                                             :client-ui-hooks))

(defn- with-client-context-owner
  [player-uuid f]
  (f (client-context-owner player-uuid)))

(defn- slot-context-key [player-uuid key-idx]
  (conj (client-ui-owner-key player-uuid) key-idx))

(defn- slot-key-owner
  [slot-key]
  (subvec (vec slot-key) 0 2))

(defn client-ui-state-snapshot
  ([]
   (let [{:keys [vm-wave-circles vm-wave-last-spawn-ms slot-context-ids slot-key-tick-ms charge-coin-state]}
         (client-ui-runtime-state-snapshot)]
     {:vm-wave-circles vm-wave-circles
      :vm-wave-last-spawn-ms vm-wave-last-spawn-ms
      :slot-context-ids slot-context-ids
      :slot-key-tick-ms slot-key-tick-ms
      :charge-coin-state charge-coin-state
  :push-handlers-registered? (:push-handlers-registered? (client-ui-runtime-state-snapshot))}))
  ([owner]
   (let [owner-key (client-ui-owner-key owner)
         {:keys [vm-wave-circles vm-wave-last-spawn-ms slot-context-ids slot-key-tick-ms charge-coin-state]}
         (client-ui-runtime-state-snapshot)]
     {:vm-wave-circles (get vm-wave-circles owner-key [])
      :vm-wave-last-spawn-ms (get vm-wave-last-spawn-ms owner-key 0)
      :slot-context-ids (into {}
                              (filter (fn [[slot-key _ctx-id]]
                                        (= owner-key (slot-key-owner slot-key))))
                              slot-context-ids)
      :slot-key-tick-ms (into {}
                              (filter (fn [[slot-key _last-ms]]
                                        (= owner-key (slot-key-owner slot-key))))
                              slot-key-tick-ms)
      :charge-coin-state (get charge-coin-state owner-key)})))

(defn clear-client-ui-state!
  [owner]
  (let [owner-key (client-ui-owner-key owner)]
    (update-client-ui-runtime!
      (fn [runtime-state]
        (-> runtime-state
            (update :vm-wave-circles dissoc owner-key)
            (update :vm-wave-last-spawn-ms dissoc owner-key)
            (update :slot-context-ids
                    (fn [m]
                      (into {}
                            (remove (fn [[slot-key _ctx-id]]
                                      (= owner-key (slot-key-owner slot-key)))
                                    m))))
            (update :slot-key-tick-ms
                    (fn [m]
                      (into {}
                            (remove (fn [[slot-key _last-ms]]
                                      (= owner-key (slot-key-owner slot-key)))
                                    m))))
            (update :charge-coin-state dissoc owner-key)
            (update :overlay-skill-shape-cache dissoc owner-key)
            (update :overlay-context-cache dissoc owner-key))))
  nil))

(defn- clear-client-player-state!
  [owner]
  (with-client-owner-bindings owner
    (fn []
      (let [[session-id player-uuid] (client-ui-owner-key owner)]
        (store/remove-player-state!* session-id player-uuid))))
  nil)

(defn- clear-managed-screen-state!
  [owner]
  (call-with-managed-screen-runtime
    #(do
       (skill-tree-screen/close-screen! owner)
       (preset-editor-screen/close-screen! owner)
       (location-teleport-screen/close-screen! owner)))
  nil)

(defn- clear-client-owned-runtime-state!
  [owner]
  (clear-managed-screen-state! owner)
  (ctx/clear-owner-contexts! (client-context-owner-from-owner owner))
  (clear-client-ui-state! owner)
  (client-keybinds/clear-client-keybind-state! owner)
  (client-particles/clear-owner-particle-effects! owner)
  (client-sounds/clear-owner-sound-effects! owner)
  (hand-effects/clear-owner-camera-pitch-deltas! owner)
  (clear-client-player-state! owner)
  nil)

(defn reset-client-ui-state-for-test!
  []
  (reset! (client-ui-runtime-state-atom) default-client-ui-runtime-state)
  (call-with-managed-screen-runtime
    #(managed-screens/reset-managed-screen-state-for-test!))
  nil)

(defn- mark-client-push-handlers-registered!
  []
  (let [installed? (atom false)]
    (update-client-ui-runtime!
      (fn [runtime-state]
        (if (:push-handlers-registered? runtime-state)
          runtime-state
          (do
            (reset! installed? true)
            (assoc runtime-state :push-handlers-registered? true)))))
    @installed?))

(defn set-slot-context-for-test!
  [player-uuid key-idx ctx-id]
  (update-client-ui-runtime! assoc-in [:slot-context-ids (slot-context-key player-uuid key-idx)] ctx-id)
  nil)

(defn seed-vm-wave-state-for-test!
  ([owner circles]
   (seed-vm-wave-state-for-test! owner circles 0))
  ([owner circles last-spawn-ms]
   (let [owner-key (client-ui-owner-key owner)]
     (update-client-ui-runtime!
       (fn [runtime-state]
         (-> runtime-state
             (assoc-in [:vm-wave-circles owner-key] (vec circles))
             (assoc-in [:vm-wave-last-spawn-ms owner-key] (long last-spawn-ms)))))
     nil)))

(def ^:private toggle-primary-state-input-id :content/toggle-primary-state)
(def ^:private cycle-selection-input-id :content/cycle-selection)

(defn- handle-toggle-primary-state-input!
  [{:keys [player-uuid]} _payload]
  (when player-uuid
    (client-keybinds/trigger-mode-switch! player-uuid)))

(defn- handle-cycle-selection-input!
  [{:keys [player-uuid]} _payload]
  (when player-uuid
    (client-keybinds/switch-preset! player-uuid)))

(defn install-client-input-descriptors!
  []
  (runtime-hooks/register-client-input-descriptor!
   {:id toggle-primary-state-input-id
    :content-id "ac"
    :handler handle-toggle-primary-state-input!})
  (runtime-hooks/register-client-input-descriptor!
   {:id cycle-selection-input-id
    :content-id "ac"
    :handler handle-cycle-selection-input!})
  nil)

(defn- railgun-charge-item-max-ticks []
  (skill-config/tunable-int :railgun :charge.item-charge-ticks))

(defn- railgun-coin-active-threshold []
  (skill-config/tunable-double :railgun :qte.coin-active-threshold))

(defn- railgun-coin-window-ms []
  (skill-config/tunable-int :railgun :qte.coin-window-ms))

(defn- notify-charge-coin-throw!
  [player-uuid now-ms]
  (update-client-ui-runtime!
    assoc-in
    [:charge-coin-state (client-ui-owner-key player-uuid)]
    {:start-ms (long now-ms)
     :window-ms (max 1 (long (railgun-coin-window-ms)))}))

(defn- charge-coin-visual-state
  [player-uuid now-ms]
  (let [contexts (player-contexts player-uuid)
        railgun-ctx (some (fn [ctx-data]
                            (when (and (= :railgun (:skill-id ctx-data))
                                       (ctx/active-context? ctx-data))
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
       :charge-start-ms nil
       :charge-ratio (max 0.0 (min 1.0 (- 1.0 (/ (double charge-ticks) max-charge-ticks))))}
      (let [owner-key (client-ui-owner-key player-uuid)
            {:keys [start-ms window-ms]} (get (charge-coin-state-snapshot) owner-key)
            has-window? (and start-ms window-ms)
            elapsed (if has-window? (- (long now-ms) (long start-ms)) 0)
            progress (if has-window?
                       (/ (double (max 0 elapsed)) (double (max 1 (long window-ms))))
                       0.0)
            active-window? (and has-window? (<= progress 1.0))
            ratio (max 0.0 (min 1.0 progress))
            coin-active? (and active-window? (>= ratio (railgun-coin-active-threshold)))]
        (when (and has-window? (not active-window?))
          (update-client-ui-runtime! update :charge-coin-state dissoc owner-key))
        {:active? (boolean active-window?)
         :charge-ticks 0
         :charge-start-ms start-ms
         :coin-active? (boolean coin-active?)
         :coin-progress ratio
         :charge-ratio ratio}))))

(defn- find-player-context
  [player-uuid skill-id]
  (some (fn [ctx-data]
          (when (and (= skill-id (:skill-id ctx-data))
                     (ctx/active-context? ctx-data))
            ctx-data))
  (player-contexts player-uuid)))

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
  [player-uuid]
  (current-charging-fx/current-state player-uuid))

(defn- current-charging-overlay-elements
  [player-uuid screen-width screen-height]
  (let [{:keys [active? blending? is-item good? charge-ticks charge-ratio]} (current-charging-fx/current-state player-uuid)
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
          :text (i18n/translate (if is-item "ac.current_charging.item" "ac.current_charging.block"))
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

(defn- coin-qte-overlay-elements
  "Build golden coin-QTE timing window overlay elements.
   Rendered when railgun is in coin-QTE mode with an active timing window.
   Shows a ring/bar at screen center indicating window progress and the
   coin-active threshold marker, matching original AcademyCraft QTE UI."
  [player-uuid screen-width screen-height now-ms]
  (let [coin-state (charge-coin-visual-state player-uuid now-ms)]
    (when (and (:active? coin-state) (pos? (:coin-progress coin-state)))
      (let [cx (int (/ screen-width 2))
            cy (int (/ screen-height 2))
            progress (double (:coin-progress coin-state))
            coin-active? (boolean (:coin-active? coin-state))
            threshold (double (railgun-coin-active-threshold))
            ;; Ring geometry
            ring-radius 24
            ring-thickness 3
            segments 48
            dot-count 12
            ;; Colors
            window-color (if coin-active?
                          {:r 255 :g 215 :b 0 :a 220}   ;; gold when in active zone
                          {:r 180 :g 150 :b 50 :a 160}) ;; dim amber outside zone
            threshold-color {:r 255 :g 220 :b 80 :a 240}
            bg-color {:r 20 :g 18 :b 10 :a 100}]
        (concat
          ;; Background disc
          [{:kind :fill
            :x (- cx ring-radius) :y (- cy ring-radius)
            :w (* 2 ring-radius) :h (* 2 ring-radius)
            :color bg-color}]
          ;; Progress arc — dots around the ring showing window progress
          (for [i (range dot-count)
                :let [angle (* 2.0 Math/PI (/ i dot-count))
                      dot-active? (< (/ i dot-count) progress)
                      dx (int (* ring-radius (Math/cos angle)))
                      dy (int (* ring-radius (Math/sin angle)))
                      dot-size 3]]
            {:kind :fill
             :x (+ cx dx (- dot-size)) :y (+ cy dy (- dot-size))
             :w (* 2 dot-size) :h (* 2 dot-size)
             :color (if dot-active?
                     (update window-color :a #(int (* % (if coin-active? 1.0 0.6))))
                     (assoc window-color :a 40))})
          ;; Threshold marker — small bright dot at the threshold angle
          (let [threshold-angle (* 2.0 Math/PI threshold)
                tx (int (* ring-radius (Math/cos threshold-angle)))
                ty (int (* ring-radius (Math/sin threshold-angle)))
                marker-size 2]
            [{:kind :fill
              :x (+ cx tx (- marker-size)) :y (+ cy ty (- marker-size))
              :w (* 2 marker-size) :h (* 2 marker-size)
              :color threshold-color}])
          ;; Center text: progress percentage
          [{:kind :text
            :x (- cx 14) :y (- cy 4)
            :text (str (int (* 100.0 progress)) "%")
            :color (if coin-active?
                    {:r 255 :g 215 :b 0 :a 255}
                    {:r 180 :g 150 :b 50 :a 200})}])))))

(defn- preset-switch-state-for-overlay
  [player-uuid]
  (client-keybinds/get-preset-switch-state player-uuid))

(defn- remove-slot-context! [ctx-id]
  (update-client-ui-runtime!
    update :slot-context-ids
    (fn [m]
      (into {}
            (remove (fn [[_slot-key active-ctx-id]]
                      (= active-ctx-id ctx-id))
                    m)))))

(defn- context-id-for-slot!
  [player-uuid key-idx skill-id]
  (let [slot-key (slot-context-key player-uuid key-idx)]
    (or (get (slot-context-ids-snapshot) slot-key)
        (let [ctx-map (with-client-context-owner
                        player-uuid
                        (fn [owner]
                          (ctx-mgr/activate-context! owner player-uuid skill-id)))
              ctx-id (:id ctx-map)]
          (update-client-ui-runtime! assoc-in [:slot-context-ids slot-key] ctx-id)
          ctx-id))))

(defn- send-with-client-owner!
  [player-uuid msg-id payload & [callback]]
  (net-client/send-to-server (client-context-owner player-uuid)
                             msg-id
                             payload
                             callback))

(defn- send-slot-key-message!
  [msg-id player-uuid key-idx]
  (when-let [skill-id (client-keybinds/get-skill-id-for-slot-public player-uuid key-idx)]
    (when-let [ctx-id (context-id-for-slot! player-uuid key-idx skill-id)]
      (send-with-client-owner! player-uuid msg-id {:ctx-id ctx-id
                                                   :skill-id skill-id
                                                   :key-idx key-idx})
      ctx-id)))

(defn- send-slot-keepalive!
  [player-uuid key-idx]
  (let [slot-key (slot-context-key player-uuid key-idx)]
    (when-let [ctx-id (get (slot-context-ids-snapshot) slot-key)]
      (send-with-client-owner! player-uuid catalog/MSG-CTX-KEEPALIVE {:ctx-id ctx-id})
      ctx-id)))

(defn- send-slot-key-up-message!
  [player-uuid key-idx]
  (let [slot-key (slot-context-key player-uuid key-idx)]
    (when-let [ctx-id (get (slot-context-ids-snapshot) slot-key)]
      (send-with-client-owner! player-uuid catalog/MSG-SLOT-KEY-UP {:ctx-id ctx-id
                                                                   :key-idx key-idx})
      (update-client-ui-runtime! update :slot-context-ids dissoc slot-key)
      ctx-id)))

(defn- abort-slot-context!
  [player-uuid key-idx]
  (let [slot-key (slot-context-key player-uuid key-idx)]
    (update-client-ui-runtime! update :slot-key-tick-ms dissoc slot-key)
    (when-let [ctx-id (get (slot-context-ids-snapshot) slot-key)]
      (send-with-client-owner! player-uuid catalog/MSG-SLOT-KEY-ABORT {:ctx-id ctx-id
                                                                       :key-idx key-idx})
      (update-client-ui-runtime! update :slot-context-ids dissoc slot-key)
      (with-client-context-owner player-uuid
        (fn [_owner]
          (binding [ctx/*context-owner* (client-context-owner player-uuid)]
            (ctx/terminate-context! ctx-id nil))))
      ctx-id)))

(defn- clear-slot-key-ticks!
  [slot-key-pred]
  (update-client-ui-runtime!
    update :slot-key-tick-ms
    (fn [m]
      (into {}
            (remove (fn [[slot-key _last-ms]]
                      (slot-key-pred slot-key))
                    m)))))

(defn- abort-slot-keys!
  [slot-keys]
  (doseq [slot-key slot-keys]
    (let [[_session-id player-uuid key-idx] slot-key]
      (abort-slot-context! player-uuid key-idx))))

(defn- abort-all-slot-contexts-for-owner!
  [owner]
  (let [owner-key (client-ui-owner-key owner)
  abort-slots (->> (slot-context-ids-snapshot)
                         (filter (fn [[slot-key _ctx-id]]
                                   (= owner-key (slot-key-owner slot-key))))
                         (map first)
                         vec)]
    (abort-slot-keys! abort-slots)
    (clear-slot-key-ticks! #(= owner-key (slot-key-owner %)))))

(defn- abort-all-slot-contexts-for-session!
  [session-id]
  (let [abort-slots (->> (slot-context-ids-snapshot)
                         (filter (fn [[slot-key _ctx-id]]
                                   (= session-id (first slot-key))))
                         (map first)
                         vec)]
    (abort-slot-keys! abort-slots)
    (clear-slot-key-ticks! #(= session-id (first %)))))

(defn- runtime-sync-resets-input?
  [old-ability-data new-ability-data]
  (let [old-category (:category-id old-ability-data)
        new-category (:category-id new-ability-data)
        old-learned (or (:learned-skills old-ability-data) [])
        new-learned (or (:learned-skills new-ability-data) [])]
    (or (not= old-category new-category)
        (and (seq old-learned)
             (empty? new-learned)))))

(defn- resource-sync-disables-input?
  [old-resource-data new-resource-data]
  (let [old-activated (boolean (:activated old-resource-data))
        new-activated (boolean (:activated new-resource-data))
        old-usable (resource-check/can-use-resource-data? old-resource-data)
        new-usable (resource-check/can-use-resource-data? new-resource-data)]
    (or (and old-activated (not new-activated))
        (and old-usable (not new-usable)))))

(defn- flush-buffered-context-message!
  [player-uuid ctx-id {:keys [channel payload]}]
  (send-with-client-owner! player-uuid catalog/MSG-CTX-CHANNEL {:ctx-id ctx-id
                                                                :channel channel
                                                                :payload payload}))

(defn- send-slot-wheel-message!
  [player-uuid key-idx delta]
  (when (and (gameplay/use-mouse-wheel-enabled?)
             (number? delta)
             (not (zero? (double delta))))
    (let [slot-key (slot-context-key player-uuid key-idx)
          ctx-id (get (slot-context-ids-snapshot) slot-key)
          skill-id (client-keybinds/get-skill-id-for-slot-public player-uuid key-idx)]
      (when (and ctx-id (= skill-id :penetrate-teleport))
        (send-with-client-owner! player-uuid catalog/MSG-CTX-CHANNEL
                                 {:ctx-id ctx-id
                                  :channel :penetrate-tp/set-distance
                                  :payload {:delta (double delta)}})))))

(defn- active-flashing-context-ids
  [player-uuid]
  (let [owner-key (client-ui-owner-key player-uuid)]
    (->> (slot-context-ids-snapshot)
       (keep (fn [[slot-key ctx-id]]
               (when (= owner-key (slot-key-owner slot-key))
                 (let [ctx-data (with-client-context-owner
                                  player-uuid
                                  (fn [owner]
                                    (ctx/get-context owner ctx-id)))]
                   (when (and (= :flashing (:skill-id ctx-data))
                              (ctx/active-context? ctx-data))
                     ctx-id)))))
       distinct
       vec)))

(defn- send-flashing-movement-message!
  [player-uuid channel movement-key]
  (doseq [ctx-id (active-flashing-context-ids player-uuid)]
    (send-with-client-owner! player-uuid catalog/MSG-CTX-CHANNEL
                             {:ctx-id ctx-id
                              :channel channel
                              :payload {:key movement-key}})))

(defn- scan-vm-contexts
  "Single-pass context walk: returns reflection-active?, deviation-active?, and
   crosshair-intensity in one traversal. Replaces three separate get-all-contexts calls."
  [player-uuid]
  (reduce
    (fn [acc [_ctx-id ctx-data :as _entry]]
      (if (and (= (:player-uuid ctx-data) player-uuid)
               (ctx/active-context? ctx-data))
        (cond-> acc
          (toggle/is-toggle-active? ctx-data :vec-reflection)
          (-> (assoc :reflection-active? true)
              (assoc :reflection-intensity
                     (let [ticks (long (or (get-in ctx-data [:skill-state :toggle :vec-reflection :total-ticks]) 0))]
                       (double (min 1.0 (/ ticks 20.0))))))
          (toggle/is-toggle-active? ctx-data :vec-deviation)
          (assoc :deviation-active? true))
        acc))
    {:reflection-active? false :deviation-active? false :reflection-intensity 0.0}
    (ctx/get-all-contexts)))

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

(defn- update-vm-wave-circles! [player-uuid active? screen-width screen-height now-ms]
  (let [owner-key (client-ui-owner-key player-uuid)]
    (update-client-ui-runtime!
      (fn [runtime-state]
        (let [last-spawn-ms (long (get-in runtime-state [:vm-wave-last-spawn-ms owner-key] 0))
              needs-spawn? (and active? (>= (- now-ms last-spawn-ms) 90))
              circles (get-in runtime-state [:vm-wave-circles owner-key] [])
              alive (->> circles
                         (filter (fn [{:keys [born-ms life-ms]}]
                                   (< (- now-ms (long born-ms)) (long life-ms))))
                         vec)
              spawned (if needs-spawn?
                        (conj alive (spawn-vm-wave-circle screen-width screen-height now-ms))
                        alive)
              next-circles (if active? spawned (if (seq spawned) spawned []))
              runtime-state (if needs-spawn?
                              (assoc-in runtime-state [:vm-wave-last-spawn-ms owner-key] now-ms)
                              runtime-state)]
          (if (seq next-circles)
            (assoc-in runtime-state [:vm-wave-circles owner-key] next-circles)
            (update runtime-state :vm-wave-circles dissoc owner-key)))))))

(defn- vm-wave-elements [player-uuid now-ms tint]
  "Build VM wave circle overlay elements with optional color tint.
   tint: [r g b] vector — blue for VecReflection, green for VecDeviation."
  (->> (get (vm-wave-circles-snapshot) (client-ui-owner-key player-uuid) [])
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
                 :alpha (double (max 0.0 (min 1.0 alpha)))
                 :tint tint})))
       (filter #(pos? (:alpha %)))
       vec))

(defn- build-hud-model-from-state [player-state activated-override]
  (when player-state
    (let [resource-data (:resource-data player-state)
          ability-data (:ability-data player-state)
          preset-data-map (:preset-data player-state)
          activated (if (contains? activated-override :value)
                      (:value activated-override)
                      (boolean (:activated resource-data)))
          category-id (:category-id ability-data)
          cat (when category-id (category/get-category category-id))]
      {:cp {:cur (double (or (:cur-cp resource-data) 0.0))
            :max (double (or (:max-cp resource-data) 1.0))}
       :overload {:cur (double (or (:cur-overload resource-data) 0.0))
                  :max (double (or (:max-overload resource-data) 1.0))
                  :fine (boolean (get resource-data :overload-fine true))}
       :active-slots (vec (preset-data/get-active-slots preset-data-map))
       :activated activated
       :category-id category-id
       :category-color (:color cat)
       :category-icon (:icon cat)
       :interfered? (boolean (seq (:interferences resource-data)))})))

(defn- hud-render-data->overlay-elements [hud-render-data screen-width screen-height]
  (let [cp-bar (some-> (:cp-bar hud-render-data) (assoc :kind :bar) (dissoc :type))
        overload-bar (some-> (:overload-bar hud-render-data) (assoc :kind :bar) (dissoc :type))
        activation-indicator (some-> (:activation-indicator hud-render-data)
                                     (assoc :kind :activation-indicator
                                            :x (int (/ screen-width 2)))
                                     (dissoc :type))
      combat-notice (some-> (:combat-notice hud-render-data)
            (assoc :kind :text
                   :x (int (/ screen-width 2)))
            (dissoc :type))
         skill-slots (mapv (fn [slot]
              (-> slot
                  (assoc :kind :content-slot
                    :content-icon (:skill-icon slot)
                    :content-label (:skill-name slot)
                    :disabled? (:in-cooldown slot)
                    :status-seconds (:cooldown-seconds slot)
                    :timer-total (:cooldown-total slot)
                    :timer-remaining (:cooldown-remaining slot))
                  (dissoc :type :skill-id :skill-icon :skill-name :in-cooldown :cooldown-seconds
                          :cooldown-total :cooldown-remaining)))
                          (or (:skill-slots hud-render-data) []))
        preset-indicators (mapv (fn [p]
                        (-> p
                            (assoc :kind :selection-indicator
                                   :x (int (/ screen-width 2))
                                   :y (- screen-height 45))
                            (dissoc :type)))
                      (or (:preset-indicators hud-render-data) []))
        overload-pulse (when-let [ol-bar (:overload-bar hud-render-data)]
                         (let [pct (double (or (:percent ol-bar) 0.0))]
                           (when (> pct 0.8)
                             {:kind :overload-pulse
                              :intensity (* (- pct 0.8) 5.0)})))
        numbers-texts (or (:numbers-texts hud-render-data) [])]
                (persistent!
                  (let [out (transient [])]
                    (doseq [x (keep identity [cp-bar overload-bar activation-indicator combat-notice overload-pulse])]
                      (conj! out x))
                    (doseq [x preset-indicators] (conj! out x))
                    (doseq [x skill-slots] (conj! out x))
                    (doseq [x numbers-texts] (conj! out x))
                    out))))

(defn- tutorial-notification-elements [screen-width screen-height now-ms]
  (try
    (tutorial-notification/build-notification-elements! screen-width screen-height now-ms)
    (catch Throwable _ [])))

(defn- active-skill-cp-cost-from-contexts
  "Compute consumption-hint CP cost for the first active skill with a computable cost,
   given an already-fetched contexts collection. Iterates over active (non-terminated)
   contexts, trying common cost paths (tick, down, up, release) in order. Returns nil
   if no active skill has a cost.

   Replaces the railgun-specific coin-QTE logic with a general approach that
   works for all skills matching original AcademyCraft CPBar consumption-hint behavior."
  [contexts]
  (let [active-ctxs (filter ctx/active-context? contexts)]
    (some
      (fn [ctx-data]
        (let [skill-id (:skill-id ctx-data)
              exp (double (or (:exp ctx-data) 0.0))]
          (some
            (fn [cost-path]
              (try
                (let [cost (skill-config/lerp-double skill-id cost-path exp)]
                  (when (pos? cost) (double cost)))
                (catch Throwable _ nil)))
            [:cost.tick.cp :cost.down.cp :cost.up.cp :cost.release.cp :cost.attack.cp])))
      active-ctxs)))

(defn- cached-skill-slot-shapes
  "Cache A: skill-slot shape (icon/name/key-label/position), keyed on preset-data
   identity. Returns cached shapes when preset-data is unchanged, else rebuilds
   via hud-renderer/build-skill-slot-shape and writes the cache."
  [owner-key hud-model screen-width screen-height preset-data]
  (let [cache (get (overlay-skill-shape-cache-snapshot) owner-key)]
    (if (and cache (identical? preset-data (:last-preset-data cache)))
      (:shapes cache)
      (let [shapes (hud-renderer/build-skill-slot-shape hud-model screen-width screen-height)]
        (update-client-ui-runtime! assoc-in [:overlay-skill-shape-cache owner-key]
                                    {:last-preset-data preset-data :shapes shapes})
        shapes))))

(defn- cached-context-data
  "Cache B: active-contexts + consumption-hint, keyed on a context-registry
   snapshot token. Both the skill-slot delegate-state patch and the
   consumption-hint computation share this single (allocating) context read
   instead of each scanning contexts independently every frame."
  [owner-key player-uuid]
  (let [token (ctx/contexts-version-token)
        cache (get (overlay-context-cache-snapshot) owner-key)]
    (if (and cache (identical? token (:last-contexts-token cache)))
      cache
      (let [contexts (player-contexts player-uuid)
            hint (active-skill-cp-cost-from-contexts contexts)
            entry {:last-contexts-token token
                   :active-contexts contexts
                   :consumption-hint hint}]
        (update-client-ui-runtime! assoc-in [:overlay-context-cache owner-key] entry)
        entry))))

(defn reactive-overlay-snapshot
  "Signal-oriented HUD snapshot for reactive overlay updates.
   Reuses the same state reads as build-client-overlay-plan without building element vectors."
  [player-uuid overlay-state]
  (let [player-state (get-client-player-state player-uuid)
        resource-data (:resource-data player-state)
        activated-override {:value (if (some? (:activated-override overlay-state))
                                     (boolean (:activated-override overlay-state))
                                     (boolean (get resource-data :activated false)))}
        hud-model (build-hud-model-from-state player-state activated-override)
        now-ms (long (or (:now-ms overlay-state) (System/currentTimeMillis)))]
    (when hud-model
      (let [cp-bar (when (:activated hud-model) (hud-renderer/build-cp-bar-render-data hud-model))
            overload-bar (when (:activated hud-model)
                           (hud-renderer/build-overload-bar-render-data hud-model now-ms))]
        {:activated (:activated hud-model)
         :cp-percent (double (or (:percent cp-bar) 0.0))
         :cp-hint-percent (some-> cp-bar :hint-percent double)
         :cp-full-glow? (boolean (:full-glow? cp-bar))
         :overload-percent (double (or (:percent overload-bar) 0.0))
         :overload-scroll (double (or (:scroll-offset overload-bar) 0.0))
         :overloaded? (boolean (:overloaded overload-bar))
         :interfered? (:interfered? hud-model)}))))

(defn build-client-overlay-plan [player-uuid screen-width screen-height overlay-state]
  ;; When an overlay app is active, skip normal HUD and render overlay app elements.
  (if-let [app-kw (:active-overlay-app overlay-state)]
    (case app-kw
      :freq-tx {:elements (vec (freq-tx/build-overlay-elements player-uuid screen-width screen-height))
                :background-mask {:r 0.0 :g 0.0 :b 0.0 :a 0.45}
                :interfered? false}
      :install-fx {:elements (vec (install-fx/build-overlay-elements player-uuid screen-width screen-height))
                   :background-mask {:r 0.0 :g 0.0 :b 0.0 :a 0.4}
                   :interfered? false}
      {:elements [{:kind :text :text (str "Unknown overlay app: " (name app-kw)) :x 20 :y 20 :color 0xFFFF0000}]
       :background-mask {:r 0.0 :g 0.0 :b 0.0 :a 0.0}
       :interfered? false})
    ;; Normal HUD rendering
    (let [player-state (get-client-player-state player-uuid)
        resource-data (:resource-data player-state)
        ability-data (:ability-data player-state)
        activated-override {:value (if (some? (:activated-override overlay-state))
                                     (boolean (:activated-override overlay-state))
                                     (boolean (get resource-data :activated false)))}
        ;; BackgroundMask: compute target color from category / overload state
        category-id (:category-id ability-data)
        cat (when category-id (category/get-category category-id))
        cat-color (:color cat)
        overloaded? (not (get resource-data :overload-fine true))
        activated? (boolean (get resource-data :activated false))
        bg-mask (cond
                  overloaded? {:r 0.82 :g 0.08 :b 0.08 :a 0.65}   ;; red, original CRL_OVERRIDE
                  (and activated? cat-color) {:r (double (nth cat-color 0))
                                              :g (double (nth cat-color 1))
                                              :b (double (nth cat-color 2))
                                              :a 0.35}              ;; subtle category tint
                  :else {:r 0.0 :g 0.0 :b 0.0 :a 0.0})            ;; invisible
        ;; Interference detection
        interfered? (boolean (seq (:interferences resource-data)))
        ;; Numbers display state (was previously dropped)
        showing-numbers? (boolean (:showing-numbers? overlay-state false))
        last-show-value-change-ms (long (or (:last-show-value-change-ms overlay-state) 0))
        hud-model (build-hud-model-from-state player-state activated-override)
        now-ms (long (or (:now-ms overlay-state) (System/currentTimeMillis)))
        charge-state (charge-coin-visual-state player-uuid now-ms)
        owner-key (client-ui-owner-key player-uuid)
        preset-data (:preset-data player-state)
        {:keys [active-contexts consumption-hint]} (cached-context-data owner-key player-uuid)
        hud-model (if consumption-hint
                    (assoc hud-model :consumption-hint consumption-hint)
                    hud-model)
        cooldown-data (:cooldown-data player-state)
        activate-hint (client-keybinds/get-activate-hint player-uuid)
        preset-state (preset-switch-state-for-overlay player-uuid)
        ;; Reactive assembly (Cache A/B, see docs/dev plan "Overlay/HUD 响应式重构"):
        ;; cp-bar/overload-bar/activation-indicator/preset-indicators/numbers-texts stay
        ;; unconditional per-frame calls (cheap, no registry/context lookups). Skill-slot
        ;; shape (registry lookups) is Cache A; delegate-state/consumption-hint share the
        ;; single Cache B context read above; cooldown fields are patched fresh every frame.
        preset-indicators (hud-renderer/build-preset-indicators-data preset-state now-ms)
        preset-indicator (last preset-indicators)
        numbers-texts (hud-renderer/build-numbers-texts-data hud-model showing-numbers?
                                                             last-show-value-change-ms now-ms)
        skill-slots (when (:activated hud-model)
                      (-> (cached-skill-slot-shapes owner-key hud-model screen-width screen-height preset-data)
                          (hud-renderer/patch-skill-slot-cooldown cooldown-data)
                          (hud-renderer/patch-skill-slot-visual active-contexts player-uuid)))
        hud-render-data (when (or (:activated hud-model) preset-indicator showing-numbers?
                                  (pos? last-show-value-change-ms))
                          {:cp-bar (when (:activated hud-model) (hud-renderer/build-cp-bar-render-data hud-model))
                           :overload-bar (when (:activated hud-model)
                                           (hud-renderer/build-overload-bar-render-data hud-model now-ms))
                           :skill-slots skill-slots
                           :activation-indicator (when (:activated hud-model)
                                                    (hud-renderer/build-activation-indicator-data hud-model activate-hint))
                           :combat-notice nil
                           :preset-indicator preset-indicator
                           :preset-indicators preset-indicators
                           :numbers-texts numbers-texts})
        base-elements (hud-render-data->overlay-elements hud-render-data screen-width screen-height)
        current-charging-elements (current-charging-overlay-elements player-uuid screen-width screen-height)
        coin-qte-elements (coin-qte-overlay-elements player-uuid screen-width screen-height now-ms)
        vm (scan-vm-contexts player-uuid)
        reflection-active? (:reflection-active? vm)
        deviation-active? (:deviation-active? vm)
        vec-reflection-intensity (:reflection-intensity vm)
        vm-wave-active? (or reflection-active? deviation-active?)
        phase (double (/ (mod now-ms 1200) 1200.0))
        vm-wave-tint (cond
                       (and reflection-active? deviation-active?) [0.4 0.7 1.0]  ;; both: cyan-blue blend
                       reflection-active? [0.3 0.6 1.0]                           ;; VecReflection: blue
                       deviation-active? [0.3 1.0 0.6]                            ;; VecDeviation: green
                       :else [1.0 1.0 1.0])                                       ;; fallback: white
        vm-wave (vm-wave-elements player-uuid now-ms vm-wave-tint)
        crosshair (when reflection-active?
              {:kind :content-crosshair
                     :x (int (/ screen-width 2))
                     :y (int (/ screen-height 2))
                     :phase phase
                     :intensity (double (or vec-reflection-intensity 1.0))})]
    {:elements (persistent!
                 (let [out (transient [])]
                   (doseq [coll [base-elements current-charging-elements coin-qte-elements vm-wave]]
                     (doseq [x coll] (conj! out x)))
                   (when crosshair (conj! out crosshair))
                   ;; Lazy: only build toast elements when toasts are active
                   (when (seq (toast/active-toasts-snapshot))
                     (doseq [x (toast/build-toast-elements screen-width screen-height now-ms)]
                       (conj! out x)))
                   ;; Lazy: only build notification elements when notifications are active
                   (when (seq (tutorial-notification/active-snapshot))
                     (doseq [x (tutorial-notification-elements screen-width screen-height now-ms)]
                       (conj! out x)))
                   ;; Lazy: only build debug elements when debug state is active
                   (when (not= :none (debug-overlay/current-state))
                     (doseq [x (debug-overlay/build-debug-overlay-elements player-state)]
                       (conj! out x)))
                   out))
     :background-mask bg-mask
     :interfered? interfered?})))

(defn- on-context-channel-push! [{:keys [ctx-id channel payload]}]
  (fx-registry/dispatch-fx-channel! ctx-id channel payload)
  (when (= channel :location-teleport/ui-open)
    (call-with-managed-screen-runtime
      #(when-let [owner (active-managed-screen-owner :ac/location-teleport)]
         (location-teleport-screen/apply-server-payload! owner payload)))
    (client-bridge/open-screen! :ac/saved-position payload))
  (ctx/ctx-send-to-local! ctx-id channel payload))

(defn register-client-push-handlers!
  []
  (when (mark-client-push-handlers-registered!)
    ;; Register CGUI screen widget factories (replaces managed-screen dispatch)
    (skill-tree-screen/install-widget-factory!)
    (preset-editor-screen/install-widget-factory!)
    (location-teleport-screen/init!)
    (net-client/register-push-handler! catalog/MSG-SYNC-RUNTIME
      (fn [{:keys [uuid ability-data]}]
        (when (and uuid ability-data)
          (let [old-ability-data (get-in (get-client-player-state uuid) [:ability-data])]
            (ensure-client-player-state! uuid)
            (update-client-ability-data! uuid ability-data)
            (when (runtime-sync-resets-input? old-ability-data ability-data)
              (abort-all-slot-contexts-for-owner! uuid)
              (client-keybinds/clear-client-keybind-state! uuid)
              (client-keybinds/clear-key-group! :default))))))
    (net-client/register-push-handler! catalog/MSG-SYNC-RESOURCE
      (fn [{:keys [uuid resource-data]}]
        (when (and uuid resource-data)
          (let [old-resource-data (get-in (get-client-player-state uuid) [:resource-data])]
            (ensure-client-player-state! uuid)
            (update-client-resource-data! uuid resource-data)
            (when (resource-sync-disables-input? old-resource-data resource-data)
              (abort-all-slot-contexts-for-owner! uuid)
              (client-keybinds/clear-client-keybind-state! uuid))))))
    (net-client/register-push-handler! catalog/MSG-SYNC-COOLDOWN
      (fn [{:keys [uuid cooldown-data]}]
        (when (and uuid cooldown-data)
          (ensure-client-player-state! uuid)
          (update-client-cooldown-data! uuid cooldown-data))))
    (net-client/register-push-handler! catalog/MSG-SYNC-PRESET
      (fn [{:keys [uuid preset-data]}]
        (when (and uuid preset-data)
          (ensure-client-player-state! uuid)
          (update-client-preset-data! uuid preset-data)
          (client-keybinds/update-default-group! uuid))))
    (net-client/register-push-handler! catalog/MSG-CTX-ESTABLISH
      (fn [{:keys [ctx-id server-id]}]
        (when-let [owner (runtime-hooks/current-player-state-owner)]
          (binding [ctx/*context-owner* owner]
            (ctx/transition-to-alive! owner ctx-id server-id
                                      (fn [msg]
                                        (flush-buffered-context-message!
                                         (:player-uuid owner) ctx-id msg)))))))
    (net-client/register-push-handler! catalog/MSG-CTX-TERMINATE
      (fn [{:keys [ctx-id]}]
        (remove-slot-context! ctx-id)
        (ctx/terminate-context! ctx-id nil)))
    (net-client/register-push-handler! catalog/MSG-CTX-TERMINATED
      (fn [{:keys [ctx-id]}]
        (remove-slot-context! ctx-id)
        (ctx/terminate-context! ctx-id nil)))
    (net-client/register-push-handler! catalog/MSG-CTX-CHANNEL on-context-channel-push!)
    ;; Side-effect cleanup moved out of render path into tick hooks
    (content-actions/register-client-tick-hook!
      (fn tick-vm-wave-circles []
        (when-let [player (client-bridge/get-client-player)]
          (let [player-uuid (uuid/player-uuid player)
                now-ms (client-bridge/game-time-ms)
                [screen-w screen-h] (client-bridge/get-window-size)
                {:keys [reflection-active? deviation-active?]} (scan-vm-contexts player-uuid)]
            (update-vm-wave-circles! player-uuid (or reflection-active? deviation-active?)
                                     (int screen-w) (int screen-h) now-ms)))))
    (content-actions/register-client-tick-hook!
      (fn tick-cleanup-overlay []
        (toast/cleanup-expired!)
        (tutorial-notification/cleanup-expired!)))
    (log/info "Ability client push handlers registered")))

    (defn- build-preset-editor-draw-ops
      [owner]
      (if-let [render-data (preset-editor-screen/build-preset-editor-render-data owner)]
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
  (let [combat-notice-component (combat-notice/create-combat-notice-component
                                  {:now-ms-fn #(client-bridge/game-time-ms)})]
    {:client-get-skill-by-controllable
     (fn [cat-id ctrl-id]
       (skill-query/get-skill-by-controllable cat-id ctrl-id))

     :client-new-context
     (fn [player-uuid skill-id]
       (with-client-context-owner player-uuid (fn [owner] (ctx/new-context player-uuid skill-id owner))))

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
       ;; Reset keepalive throttle so the first hold refresh after key-down is never suppressed.
       (update-client-ui-runtime! update :slot-key-tick-ms dissoc (slot-context-key player-uuid key-idx))
       (send-slot-key-message! catalog/MSG-SLOT-KEY-DOWN player-uuid key-idx))

     :client-on-slot-key-tick!
     (fn [player-uuid key-idx]
       (let [slot-key (slot-context-key player-uuid key-idx)
             now-ms   (System/currentTimeMillis)
             last-ms  (get (slot-key-tick-ms-snapshot) slot-key 0)]
         (when (>= (- now-ms last-ms) 100)
           (update-client-ui-runtime! assoc-in [:slot-key-tick-ms slot-key] now-ms)
           (send-slot-keepalive! player-uuid key-idx))))

     :client-on-slot-key-up!
     (fn [player-uuid key-idx]
       (update-client-ui-runtime! update :slot-key-tick-ms dissoc (slot-context-key player-uuid key-idx))
       (send-slot-key-up-message! player-uuid key-idx))

     :client-on-slot-key-abort!
     (fn [player-uuid key-idx]
       (abort-slot-context! player-uuid key-idx))

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

     :client-clear-owner-state!
     (fn [owner]
       (when-let [session-id (or (:client-session-id (when (map? owner) owner))
                                 (current-client-session-id))]
         (combat-notice/clear-session! combat-notice-component session-id))
       (clear-client-owned-runtime-state! owner))

     :client-abort-all!
     (fn []
       (abort-all-slot-contexts-for-session!
        (require-client-owner-value {} ":client-session-id" (current-client-session-id))))

     :client-update-ability-data!
     (fn [player-uuid ability-data]
       (ensure-client-player-state! player-uuid)
       (update-client-ability-data! player-uuid ability-data))

     :client-update-resource-data!
     (fn [player-uuid resource-data]
       (ensure-client-player-state! player-uuid)
       (update-client-resource-data! player-uuid resource-data))

     :client-update-cooldown-data!
     (fn [player-uuid cooldown-data]
       (ensure-client-player-state! player-uuid)
       (update-client-cooldown-data! player-uuid cooldown-data))

     :client-update-preset-data!
     (fn [player-uuid preset-data]
       (ensure-client-player-state! player-uuid)
       (update-client-preset-data! player-uuid preset-data))

     :client-show-combat-notice!
     (fn [notice-id payload]
       (when-let [session-id (current-client-session-id)]
         (combat-notice/show-notice! combat-notice-component session-id notice-id payload)))

     :client-slot-visual-state
     (fn [player-uuid key-idx]
       (let [active-ctxs (player-contexts player-uuid)
             skill-id (client-keybinds/get-skill-id-for-slot-public player-uuid key-idx)]
         (:state (delegate-state/delegate-state-for-slot active-ctxs skill-id player-uuid))))

     :client-build-overlay-plan
     (fn [player-uuid screen-width screen-height overlay-state]
       (build-client-overlay-plan player-uuid screen-width screen-height overlay-state))

     :client-req-learn-skill!
     (fn [player-uuid skill-id extra callback]
       (client-api/req-learn-skill! (client-context-owner player-uuid) skill-id extra callback))

     :client-req-level-up!
     (fn [player-uuid callback]
       (client-api/req-level-up! (client-context-owner player-uuid) callback))

     :client-req-set-activated!
     (fn [player-uuid activated callback]
       (client-api/req-set-activated! (client-context-owner player-uuid) activated callback))

     :client-req-set-preset-slot!
     (fn [player-uuid preset-idx key-idx cat-id ctrl-id callback]
       (client-api/req-set-preset-slot! (client-context-owner player-uuid)
                                        preset-idx key-idx cat-id ctrl-id callback))

     :client-req-switch-preset!
     (fn [player-uuid preset-idx callback]
       (client-api/req-switch-preset! (client-context-owner player-uuid) preset-idx callback))

     :client-open-managed-screen!
     (fn [screen-key payload]
       (let [{:keys [player-uuid client-session-id payload]} (validate-managed-screen-payload screen-key payload)]
         (runtime-hooks/with-client-ctx {:session-id client-session-id}
           (call-with-managed-screen-runtime
             #(let [owner {:client-session-id client-session-id
                           :player-uuid player-uuid}]
                (condp = screen-key
                  :ac/skill-tree
                  (assoc (skill-tree-screen/open-screen! owner (:learn-context payload))
                         :title "Node Tree")

                  :ac/preset-editor
                  (assoc (preset-editor-screen/open-screen! owner)
                         :title "Preset Editor")

                  :ac/saved-position
                  (do (location-teleport-screen/open-screen! owner payload)
                      {:command :open-cgui-screen :screen-key :ac/saved-position})

                  :ac/location-teleport
                  (do (location-teleport-screen/open-screen! owner payload)
                      {:command :open-cgui-screen :screen-key :ac/saved-position})

                  nil))))))

     :client-build-managed-screen-render-data
     (fn [screen-key]
       (call-with-managed-screen-runtime
         #(when-let [owner (active-managed-screen-owner screen-key)]
            (condp = screen-key
              :ac/skill-tree (skill-tree-screen/build-screen-render-data owner)
              :ac/preset-editor (preset-editor-screen/build-preset-editor-render-data owner)
              :ac/saved-position nil
              :ac/location-teleport nil
              nil))))

     :client-build-managed-screen-draw-ops
     (fn [screen-key mouse-x mouse-y screen-w screen-h]
       (call-with-managed-screen-runtime
         #(if-let [owner (active-managed-screen-owner screen-key)]
            (condp = screen-key
              :ac/skill-tree (skill-tree-screen/build-draw-ops owner mouse-x mouse-y screen-w screen-h)
              :ac/preset-editor (build-preset-editor-draw-ops owner)
              []))))

     :client-handle-managed-screen-hover!
     (fn [screen-key mouse-x mouse-y]
       (call-with-managed-screen-runtime
         #(when-let [owner (active-managed-screen-owner screen-key)]
            (condp = screen-key
              :ac/skill-tree (skill-tree-screen/on-mouse-move owner mouse-x mouse-y)
              nil))))

     :client-handle-managed-screen-click!
     (fn [screen-key mouse-x mouse-y]
       (call-with-managed-screen-runtime
         #(if-let [owner (active-managed-screen-owner screen-key)]
            (condp = screen-key
              :ac/skill-tree (skill-tree-screen/handle-screen-click! owner mouse-x mouse-y)
              :ac/preset-editor (preset-editor-screen/handle-screen-click! owner nil mouse-x mouse-y)
              false)
            false)))

     :client-handle-managed-screen-char-typed!
     (fn [_ _] nil)

     :client-close-managed-screen!
     (fn [screen-key]
       (call-with-managed-screen-runtime
         #(when-let [owner (active-managed-screen-owner screen-key)]
            (condp = screen-key
              :ac/skill-tree (skill-tree-screen/close-screen! owner)
              :ac/preset-editor (preset-editor-screen/close-screen! owner)
              nil))))

     :client-register-push-handlers!
     (fn []
       (register-client-push-handlers!))

     :client-notify-visual-event!
     (fn [event-key payload]
       (case event-key
         :ac/charge-coin-throw (notify-charge-coin-throw! (:player-uuid payload) (:now-ms payload))
         nil))

     :client-visual-state
     (fn [state-key payload]
       (case state-key
         :ac/charge-coin (charge-coin-visual-state (:player-uuid payload) (:now-ms payload))
         :ac/body-intensify-charge (body-intensify-visual-state (:player-uuid payload))
         :ac/current-charging (current-charging-visual-state (:player-uuid payload))
         :ac.delegate-state/railgun
         (let [{:keys [active? coin-active?]}
               (charge-coin-visual-state (:player-uuid payload) (:now-ms payload))]
           (when active? (if coin-active? :active :charge)))
         nil))

     :client-trigger-mode-switch!
     (fn [player-uuid]
       (client-keybinds/trigger-mode-switch! player-uuid))

     :client-trigger-preset-switch!
     (fn [player-uuid]
       (client-keybinds/switch-preset! player-uuid))}))



