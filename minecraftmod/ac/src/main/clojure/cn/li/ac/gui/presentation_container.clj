(ns cn.li.ac.gui.presentation-container
  "Container surface controller backed by the Presentation artifact runtime.
   Menu authority stays in MenuBridge; only normalized snapshots and actions
   cross into the retained UI runtime."
  (:require [cn.li.mcmod.gui.presentation-menu-bridge :as menu-bridge]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.ac.wireless.gui.container.common :as container-common]
            [cn.li.ac.gui.presentation :as presentation]
            [cn.li.ac.gui.tech-ui-tabs :as tech-tabs]
            [cn.li.ac.gui.info-area :as info-area]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.gui.container.action-payload :as action-payload]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.gui.tab.role-config :as role-config]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(def binding-ids
  {:slots 0 :slot-anchors 1 :energy-ratio 2 :progress-ratio 3 :machine-state 4
   :button-left 5 :button-right 6 :network-state 7 :network-owner 8
   :network-range 9 :network-bandwidth 10 :network-load 11 :network-ssid 12
   :network-password 13 :node-name 14})
(def action-ids {0 :container/click-slot 1 :container/quick-move 2 :container/button})

(defn- value-of [value]
  (if (instance? clojure.lang.IDeref value) @value value))

(defn- slot-value [container index]
  (try
    (if (map? (:tile-entity container))
      (container-common/get-slot-item container index)
      (container-common/get-slot-item-be container index))
    (catch Exception _ nil)))

(defn- wireless-config [container]
  (:presentation-wireless container))

(defn- wireless-state [container]
  (or (some-> (:presentation-wireless-state container) deref)
      {:linked nil :avail [] :password ""}))

(defn- wireless-role-cfg [container]
  (get role-config/role-config (:role (wireless-config container))))

(defn- wireless-items [container]
  (let [data (wireless-state container)
        name-fn (or (:name-fn (wireless-role-cfg container))
                    (fn [item] (or (:node-name item) (:ssid item) "Node")))]
    (mapv (fn [item]
            ;; Keep role connect fields (:ssid / pos-*) for connect-payload-fn.
            (merge item
                   {:label (str (name-fn item))
                    :password (str (or (:password item) ""))
                    :node-x (:pos-x item) :node-y (:pos-y item) :node-z (:pos-z item)
                    :is-encrypted? (boolean (:is-encrypted? item))
                    :is-open? (not (boolean (:is-encrypted? item)))}))
          (:avail data))))

(defn- send-wireless!
  "Send a wireless GUI message. `action` may be a keyword (:connect) or an
  explicit message-id string from role-config list-msg."
  [container action payload callback]
  (when-let [{:keys [domain]} (wireless-config container)]
    (let [owner (or (:owner container) (runtime-hooks/default-client-owner))
          message-id (if (string? action)
                       action
                       (msg-registry/msg domain action))]
      (net-client/send-to-server owner message-id
        (action-payload/action-payload container payload)
        callback))))

(defn- request-wireless-list!
  [container callback]
  (when-let [list-msg (:list-msg (wireless-role-cfg container))]
    (send-wireless! container (list-msg) {} callback)))

(defn- network-logo-src [container]
  (if-let [path (:logo-path (wireless-role-cfg container))]
    (modid/namespaced-path path)
    (modid/namespaced-path "textures/guis/icons/icon_tonode.png")))

(defn- linked-label [container]
  (let [linked (:linked (wireless-state container))
        name-fn (or (:name-fn (wireless-role-cfg container))
                    (fn [t] (or (:node-name t) (:ssid t) "-")))]
    (if linked (str (name-fn linked)) "Not Connected")))

(defn- update-wireless-state!
  "Merge list/connect panel state. Only overwrite keys present in the response
   so a bare {:success true} connect ack cannot wipe :linked/:avail."
  [container response]
  (when (and (map? response) (:presentation-wireless-state container))
    (swap! (:presentation-wireless-state container)
           (fn [st]
             (cond-> (or st {:linked nil :avail [] :password ""})
               (contains? response :linked)
               (assoc :linked (:linked response))
               (contains? response :avail)
               (assoc :avail (vec (or (:avail response) [])))
               (contains? response :password)
               (assoc :password (str (:password response)))))))
  nil)

(defn- refresh-wireless-panel!
  "Re-fetch list+linked (main tab-reactive rebuild!) then present."
  [container refresh*]
  (request-wireless-list! container
    (fn [response]
      (update-wireless-state! container response)
      (when-let [refresh @refresh*] (refresh)))))

(defn- generic-info-area [container progress]
  "Fields + load chrome only. Hist bars are owned solely by
   `info-area/shared-info-hist` (re-applied after page merge) — do not build
   hist here (would duplicate generators' hist-from-container path)."
  (let [tile (:tile-entity container)
        altitude (when (contains? #{:wind-gen-main :wind-gen-base} (:container-type container))
                   (try (some-> tile pos/block-pos pos/pos-y str)
                        (catch Exception _ nil)))
        fields (mapv (fn [m]
                       (let [id (or (:id m) (keyword (str "f-" (hash (:label m)))))]
                         {:id id
                          :label (:label m)
                          :value (:value m)
                          :editable? false
                          :readonly? true
                          :draft-key id}))
                     (cond-> []
                       (contains? container :status)
                       (conj {:id :status :label "Status" :value (str (or (value-of (:status container)) "-"))})
                       (contains? container :mode)
                       (conj {:id :mode :label "Mode" :value (str (or (value-of (:mode container)) "-"))})
                       (contains? container :wireless-mode)
                       (conj {:id :wireless :label "Wireless" :value (str (or (value-of (:wireless-mode container)) "-"))})
                       altitude
                       (conj {:id :altitude :label "Altitude" :value altitude})
                       (contains? container :fan-installed)
                       (conj {:id :fan :label "Fan" :value (if (value-of (:fan-installed container)) "YES" "NO")})
                       (contains? container :no-obstacle)
                       (conj {:id :obstacle :label "Obstacle" :value (if (value-of (:no-obstacle container)) "CLEAR" "BLOCKED")})
                       (contains? container :gen-speed)
                       (conj {:id :generation :label "Generation" :value (format "%.2f IF/T" (double (or (value-of (:gen-speed container)) 0.0)))})
                       (contains? container :work-progress)
                       (conj {:id :work :label "Work" :value (str (or (value-of (:work-progress container)) "-"))})
                       (contains? container :work-counter)
                       (conj {:id :work :label "Work" :value (str (or (value-of (:work-counter container)) "-"))})
                       (contains? container :current-recipe-liquid)
                       (conj {:id :liquid-needed :label "Liquid Needed" :value (str (or (value-of (:current-recipe-liquid container)) "-"))})
                       (contains? container :liquid-amount)
                       (conj {:id :liquid :label "Liquid" :value (str (or (value-of (:liquid-amount container)) "-"))})))
        max-progress (max 1.0 (double (or (value-of (:max-progress container)) 1.0)))]
    {:title "Machine Info"
     :sep-visible? false
     :fields fields
     :load-ratio (max 0.0 (min 1.0 (/ (double progress) max-progress)))}))
(def ^:private page-texture-by-type
  "Keys must match each GUI's `:container-type` (see create-schema-container callers)."
  {;; wireless family (dedicated artifacts also hardcode these; kept for page-composite)
   :matrix "textures/guis/ui/ui_matrix.png"
   :node "textures/guis/ui/ui_node.png"
   :wireless-matrix "textures/guis/ui/ui_matrix.png"
   :wireless-node "textures/guis/ui/ui_node.png"
   ;; machine_container surfaces
   :imag-fusor "textures/guis/ui/ui_imagfusor.png"
   :metal-former "textures/guis/ui/ui_metalformer.png"
   :phase-gen "textures/guis/ui/ui_phasegen.png"
   :phase-generator "textures/guis/ui/ui_phasegen.png"
   :wind-gen-main "textures/guis/ui/ui_windmain.png"
   :wind-gen-base "textures/guis/ui/ui_windbase.png"
   :ability-interferer "textures/guis/ui/ui_interfere.png"
   :energy-converter "textures/guis/ui/ui_node.png"
   ;; main page_solar.xml overlays ui_windbase.png (not phasegen).
   :solar "textures/guis/ui/ui_windbase.png"
   :solar-generator "textures/guis/ui/ui_windbase.png"
   :solar-gen "textures/guis/ui/ui_windbase.png"})

(defn- page-composite-for [container]
  (when-let [path (get page-texture-by-type (:container-type container))]
    [{:kind :image
      :src (str "academy:" path)
      :x 0.0 :y 0.0 :w 176.0 :h 187.0
      :rgba (unchecked-int 0xFFFFFFFF)}]))

(defn- snapshot-for [container revision slot-count]
  (let [network (wireless-state container)
        linked (:linked network)
        tabbed? (tech-tabs/tech-tabs-enabled? container)
        energy (double (or (value-of (:energy container)) 0.0))
        max-energy (max 1.0 (double (or (value-of (:max-energy container)) 1.0)))
        progress (double (or (when-let [f (:presentation-progress-fn container)] (f container))
                              (value-of (:progress container))
                              (value-of (:work-progress container))
                              (when (and (some? (value-of (:crafting-progress container)))
                                         (some? (value-of (:max-progress container))))
                                (/ (double (or (value-of (:crafting-progress container)) 0.0))
                                   (max 1.0 (double (or (value-of (:max-progress container)) 1.0)))))
                              0.0))
        max-progress (max 1.0 (double (or (value-of (:max-progress container)) 1.0)))
        tab-keys (tech-tabs/snapshot-keys container)
        ;; Info-area mini network is for non-tabbed wireless hosts only.
        info-network? (and (boolean (wireless-config container)) (not tabbed?))
        buttons (or (:presentation-buttons container) [])
        ;; Inv-page progress/button chrome is opt-in: empty :button nodes lower
        ;; to opaque white RECTs and look like a stray bar under the inventory.
        inv-buttons? (boolean (seq buttons))
        inv-bars? (boolean (:presentation-inv-bars? container))
        info-load? (boolean (or (:presentation-info-load? container)
                                (and (contains? container :max-progress)
                                     (> max-progress 1.0))))]
    {:revision @revision
     :values (merge
              {:slots (mapv #(slot-value container %) (range slot-count))
               :energy-ratio (max 0.0 (min 1.0 (/ energy max-energy)))
               :progress-ratio (max 0.0 (min 1.0 (/ progress max-progress)))
               :machine-state (or (value-of (:status container))
                                  (value-of (:machine-state container))
                                  (value-of (:mode container))
                                  "IDLE")
               :info-area (generic-info-area container progress)
               :page-composite (or (page-composite-for container) [])
               :inv-buttons-visible? inv-buttons?
               :inv-bars-visible? inv-bars?
               :info-load-visible? info-load?
               :network-visible info-network?
               :network-state (if linked "Connected" "Not connected")
               :network-linked-label (linked-label container)
               :network-owner (str "Node: " (or (:node-name linked) (:ssid linked) "-"))
               :network-range (str "Range: " (or (:range linked) "-"))
               :network-bandwidth (str "Bandwidth: " (or (:bandwidth linked) "-"))
               :network-load (let [load (double (or (:load linked) 0.0))
                                   capacity (max 1.0 (double (or (:max-capacity linked) 1.0)))]
                               (max 0.0 (min 1.0 (/ load capacity))))
               :network-nodes (wireless-items container)
               ;; Wireless-tab connect password only. Never write :network-password
               ;; here — that key is the info-area password draft (TECH_UI_SHELL);
               ;; injecting "" every snapshot wiped matrix/node password rows.
               :wireless-connect-password (str (or (:password network) ""))
               :network-disconnect {:label "Disconnect"}
               :network-available-label {:label "Available"}
               :network-logo (network-logo-src container)
               :network-logo-composite [{:kind :image
                                         :src (network-logo-src container)
                                         :x 0.0 :y 0.0 :w 16.0 :h 16.0
                                         :rgba (unchecked-int 0xFFFFFFFF)}]
               :network-connected? (boolean linked)
               :network-disconnected? (not (boolean linked))}
              tab-keys)}))

(defn- runtime-owned-action?
  "Presentation Runtime owns hover/scroll/pointer/key routing. These must never
   cross the MenuBridge allow-list gate or mouseMoved will crash containers."
  [action]
  (or (nil? action)
      (and (keyword? action)
           (= "input" (namespace action)))))

(def ^:private draft-state-keys
  "Text-field drafts that must survive snapshot rebuilds / refresh.
   Includes canonical info-area keys and form aliases so select-keys never
   drops a live :ssid while :node-name is what the field binds."
  (vec (into #{:console-input :interferer-input :wireless-connect-password}
             (mapcat identity (vals info-area/draft-alias-groups)))))

(defn- merge-drafts
  "Keep in-progress text drafts across snapshot rebuilds.

   Alias expansion (:ssid ↔ :node-name, :password ↔ :network-password) and
   info-area field overlay live in info-area — every TechUI page shares them."
  [snapshot current payload]
  (let [field (:field payload)
        value (when (contains? payload :value) (str (:value payload)))
        from-payload (when (and field (contains? payload :value))
                       (info-area/payload-drafts field value))
        current-drafts (when (map? current)
                         (merge (select-keys current draft-state-keys)
                                (info-area/expand-drafts
                                  (info-area/project-form-drafts current))))]
    (info-area/apply-drafts-to-fields
      (merge snapshot current-drafts from-payload))))

(defn- sync-view-drafts-into-form!
  "Push live text-input view keys into presentation-form-state before rebuild."
  [form current]
  (when (and form (map? current))
    (swap! form #(info-area/sync-view-into-form % current))))

(defn mount-container!
  ([runtime menu-bridge snapshot-fn dispatch-action!]
   (mount-container! runtime menu-bridge snapshot-fn dispatch-action!
                    :academy.app/machine-container))
  ([_runtime menu-bridge snapshot-fn dispatch-action! view-id]
  (let [state-fn (fn []
                   (let [snapshot (snapshot-fn)]
                     ;; Prefer flattened :values over envelope keys so draft
                     ;; fields are not shadowed by nested :values metadata.
                     (merge snapshot (:values snapshot {}))))
        vm (presentation/mount-view!
             {:view-id view-id
              :host-kind :container
              :state (state-fn)
              :dispatch-action!
              (fn [action payload current]
                ;; On focus change, push sibling drafts into form-state before any
                ;; later keystroke rebuilds the snapshot from a stale form.
                (when (and (map? current) (= action :input/focus))
                  (try (dispatch-action! :presentation/sync-drafts {} current)
                       (catch Throwable _)))
                (cond
                  (runtime-owned-action? action)
                  current

                  (contains? (:allowed-actions menu-bridge) action)
                  (do (menu-bridge/dispatch-action menu-bridge action payload dispatch-action!)
                      (merge-drafts (state-fn) current payload))

                  ;; Content-owned actions (wireless/text/custom) bypass the
                  ;; slot allow-list but still reach the container dispatcher.
                  :else
                  (do (try
                        (dispatch-action! action payload current)
                        (catch clojure.lang.ArityException _
                          (dispatch-action! action payload)))
                      (merge-drafts (state-fn) current payload))))})
        ;; Bind :snapshot before assoc — refresh! must not close over the
        ;; pre-assoc vm (where :snapshot is nil → reset! NPE every animate frame).
        snapshot* (atom (state-fn))
        refresh! (fn []
                   (let [cur (when-let [st (:state vm)] @st)
                         next (merge-drafts (state-fn) cur nil)]
                     (reset! snapshot* next)
                     (presentation/present! vm next)))]
    (assoc vm
           :snapshot snapshot*
           :refresh! refresh!
           :clear-focus! (fn [] (presentation/clear-focus! vm))))))

(defn open-screen!
  [menu-bridge snapshot-fn dispatch-action! on-close]
  (let [vm (mount-container! nil menu-bridge snapshot-fn dispatch-action!)]
    (client-bridge/call-adapter :presentation-open-screen!
                                 (:mount vm) "Container" on-close)
    vm))

(def ^:private techui-image-width 290)
(def ^:private techui-image-height 187)

(def ^:private ^:const live-sync-fallback-keys
  "When :data-slot-field-specs is absent, sample these container atoms."
  [:energy :max-energy :status :gen-speed :progress :mode
   :capacity :max-capacity :work-progress :crafting-progress
   :liquid-amount :tank-size])

(defn- deref-gui-value
  [v]
  (if (instance? clojure.lang.IDeref v) @v v))

(defn- live-sync-fingerprint
  "O(fields) sample of GUI atoms (+ optional anim signature) that feed
   snapshot projection. Compared each client frame; full present! runs
   only when this changes.

   Hist-driving keys (`info-area/hist-live-keys`) are always sampled so
   Energy/Capacity/Liquid bars rebuild on every TechUI page — pages must
   not reimplement this in `:presentation-anim-fingerprint`.

   Containers may still supply `:presentation-anim-fingerprint` for
   time-based paint (sprite frame / quantized breathe)."
  [container]
  (let [keys (if-let [specs (seq (:data-slot-field-specs container))]
               (mapv :container-key specs)
               live-sync-fallback-keys)
        atoms (mapv (fn [k] (deref-gui-value (get container k))) keys)
        ;; Always sample hist keys (may overlap DataSlot keys — intentional).
        hist (mapv (fn [k] (deref-gui-value (get container k)))
                   info-area/hist-live-keys)
        anim (when-let [f (:presentation-anim-fingerprint container)]
               (f container))
        ;; Matrix network atom is not a DataSlot — capacity hist lives there.
        network (when-let [n* (:presentation-network container)]
                  (let [n @n*]
                    [(:load n) (:max-capacity n) (:energy n)
                     (:ssid n) (:initialized n)]))
        ;; Wireless panel (Connected row + avail list) is not a DataSlot —
        ;; include a cheap signature so list/connect refreshes present even
        ;; when energy/anim fingerprints are unchanged.
        wireless (when-let [ws (:presentation-wireless-state container)]
                   (let [st @ws]
                     [(some-> st :linked :ssid)
                      (count (:avail st))
                      (:password st)]))]
    (cond-> [atoms hist]
      (some? anim) (conj anim)
      (some? network) (conj network)
      (some? wireless) (conj wireless))))

(defn- player-inventory-anchors
  "Mirror mcbase `add-player-inventory-slots!` at (6,105): 3×9 main + hotbar.
   Menu indices follow slot-schema derived-ranges after the tile slots."
  [tile-slot-count]
  (let [x0 6.0
        y0 105.0
        main (for [row (range 3)
                   col (range 9)]
               {:slot-index (+ tile-slot-count (* row 9) col)
                :x (+ x0 (* col 18.0))
                :y (+ y0 (* row 18.0))
                :width 16.0 :height 16.0 :visible? true})
        hotbar (for [col (range 9)]
                 {:slot-index (+ tile-slot-count 27 col)
                  :x (+ x0 (* col 18.0))
                  :y (+ y0 58.0)
                  :width 16.0 :height 16.0 :visible? true})]
    (into [] (concat main hotbar))))

(defn- tile-slot-anchors [layout]
  (mapv (fn [{:keys [index x y]}]
          {:slot-index index :x (double x) :y (double y)
           :width 16.0 :height 16.0 :visible? true})
        (:slots layout)))

(defn presentation-screen-data
  [container menu player schema-id template-id]
  (let [revision (atom 0)
        refresh* (atom nil)
        clear-focus* (atom nil)
        live-fp* (atom ::uninitialized)
        wireless-attached?* (atom false)
        wireless* (or (:presentation-wireless-state container) (atom {:linked nil :avail [] :password ""}))
        container (assoc container
                     :minecraft-container menu
                     :presentation-wireless-state wireless*
                     :presentation-refresh! (fn []
                       (when-let [refresh @refresh*] (refresh))))
        ;; Keep developer (and peers) live-container atom pointing at the
        ;; menu-bound map so C2S action-payload sees container-id.
        _ (when-let [live* (:presentation-live-container container)]
            (reset! live* container))
        layout (or (slot-schema/get-slot-layout schema-id) {:slots []})
        slot-count (count (:slots layout))
        inv-mode (keyword (or (:player-inventory-mode layout)
                              (:default-player-inventory-mode container)
                              :full))
        base-anchors (cond-> (vec (tile-slot-anchors layout))
                       (not= inv-mode :none)
                       (into (if (= inv-mode :hotbar-only)
                               (filterv #(>= (:slot-index %) (+ slot-count 27))
                                        (player-inventory-anchors slot-count))
                               (player-inventory-anchors slot-count))))
        bridge (menu-bridge/create (or (:container-type container) schema-id)
                                   base-anchors
                                   #{:container/click-slot :container/quick-move
                                     :container/button})
        refresh-wireless-list!
        (fn []
          (request-wireless-list! container
            (fn [response]
              (update-wireless-state! container response)
              (when-let [refresh @refresh*] (refresh)))))
        snapshot-fn (fn []
                      (swap! revision inc)
                      (let [base-values (:values (snapshot-for container revision slot-count))
                            extra-values (if-let [snapshot! (:presentation-snapshot-fn container)]
                                           (or (snapshot! container player) {})
                                           {})
                            form-state (when-let [form (:presentation-form-state container)] @form)
                            ;; Unified draft projection (ssid↔node-name, password↔
                            ;; network-password) — see info-area/project-form-drafts.
                            text-values (info-area/expand-drafts
                                          (info-area/project-form-drafts form-state))
                            button-values (into {}
                                           (mapcat (fn [{:keys [button-id label]}]
                                                     (case (int (or button-id -1))
                                                       0 [[:button-left {:label (str (or label ""))
                                                                         :button-id 0}]]
                                                       1 [[:button-right {:label (str (or label ""))
                                                                          :button-id 1}]]
                                                       []))
                                                   (or (:presentation-buttons container) [])))
                            anchors (tech-tabs/mark-slot-anchors container base-anchors)
                            values (merge base-values extra-values text-values button-values
                                          {:slot-anchors anchors})
                            ;; Sole hist owner for every TechUI shell page.
                            ;; generic-info-area / page snapshots must not build hist.
                            values (let [hist (or (info-area/shared-info-hist container)
                                                  {:histograms [] :hist-bars []})]
                                     (update values :info-area
                                             (fn [ia] (merge (or ia {}) hist))))]
                        (menu-bridge/update-snapshot! bridge @revision values)
                        (menu-bridge/snapshot bridge)))
        dispatch-action! (fn dispatch-action!
                           ([action payload] (dispatch-action! action payload nil))
                           ([action payload current]
                           (cond
                             (= action :presentation/sync-drafts)
                             (sync-view-drafts-into-form!
                               (:presentation-form-state container) current)

                             (= action :container/set-tab)
                             (when (tech-tabs/tech-tabs-enabled? container)
                               (let [item (:item payload)
                                     idx (or (:tab-index item)
                                             (:tab-index payload)
                                             0)]
                                 ;; Drop caret when leaving a page that owned focus.
                                 (when-let [cf @clear-focus*] (cf))
                                 (tech-tabs/switch-tab! container idx
                                   {:on-switch
                                    (fn [tab-id _]
                                      (when (and (= tab-id tech-tabs/wireless-tab-id)
                                                 (compare-and-set! wireless-attached?* false true))
                                        (refresh-wireless-list!)))})
                                 (when-let [refresh @refresh*] (refresh))))

                             (= action :container/wireless-password)
                             (swap! wireless* assoc :password (str (or (:value payload) "")))

                             (= action :container/wireless-row-password)
                             (let [item (:item payload)
                                   value (str (or (:value payload) ""))]
                               (swap! wireless*
                                      (fn [st]
                                        (assoc st :avail
                                               (mapv (fn [row]
                                                       (if (and (= (:ssid row) (:ssid item))
                                                                (= (:pos-x row) (:pos-x item))
                                                                (= (:pos-y row) (:pos-y item))
                                                                (= (:pos-z row) (:pos-z item))
                                                                (= (:node-name row) (:node-name item)))
                                                         (assoc row :password value)
                                                         row))
                                                     (vec (:avail st []))))))
                               (when-let [refresh @refresh*] (refresh)))

                             (= action :container/wireless-connect)
                             (let [cfg (wireless-config container)
                                   role-cfg (get role-config/role-config (:role cfg))
                                   item (:item payload)
                                   password (str (or (:password item)
                                                     (:password @wireless*)
                                                     ""))]
                               (let [payload* (if-let [build (:connect-payload-fn role-cfg)]
                                                (build {} item password)
                                                item)]
                                 (send-wireless! container :connect payload*
                                   (fn [_response]
                                     ;; Main tab-reactive always rebuilds after
                                     ;; connect/disconnect (list + Connected row).
                                     (refresh-wireless-panel! container refresh*)))))

                             (= action :container/wireless-disconnect)
                             (send-wireless! container :disconnect {}
                               (fn [_response]
                                 (refresh-wireless-panel! container refresh*)))

                             (contains? #{:container/text-change :container/text-submit} action)
                             (do (sync-view-drafts-into-form!
                                   (:presentation-form-state container) current)
                                 (when-let [handler (if (= action :container/text-submit)
                                                       (:presentation-text-submit! container)
                                                       (:presentation-text-change! container))]
                                   (let [field (or (:field payload)
                                                   ;; Fall back to bind-path tail when semantics omit :field.
                                                   (when-let [p (:path payload)]
                                                     (when (vector? p) (peek p))))
                                         field (case field
                                                 :network-password :password
                                                 :network-ssid :ssid
                                                 field)
                                         value (str (or (:value payload) ""))]
                                     ;; Pass the live screen container (has :minecraft-container)
                                     ;; so C2S actions can resolve container-id. Fall back to
                                     ;; 2-arity for older handlers.
                                     (try
                                       (handler field value container)
                                       (catch clojure.lang.ArityException _
                                         (handler field value))))))

                             :else
                             (if-let [dispatch (:presentation-dispatch-action! container)]
                               (try
                                 (dispatch action payload current)
                                 (catch clojure.lang.ArityException _
                                   (dispatch action payload)))
                               (when (= action :container/button)
                                 (when-let [button (:button-click-fn container)]
                                   (button container (:button-id payload) player)))))))]
    {:type :presentation-container-screen
     ;; Default TechUI 290×187; developer (and similar non-TechUI hosts) may
     ;; override via :presentation-image-width/height on the container so
     ;; leftPos/topPos match the Presentation design canvas.
     :image-width (int (or (:presentation-image-width container) techui-image-width))
     :image-height (int (or (:presentation-image-height container) techui-image-height))
     :template-id template-id
     :container container
     :menu menu
     :player player
     :mount-fn (fn [_]
                 (let [vm (mount-container! nil bridge snapshot-fn dispatch-action! template-id)
                       raw-refresh! (:refresh! vm)
                       refresh!
                       (fn []
                         (raw-refresh!)
                         ;; Keep fingerprint in sync after action/wireless-driven
                         ;; rebuilds so the next frame does not rebuild twice.
                         (reset! live-fp* (live-sync-fingerprint container)))]
                   (reset! refresh* refresh!)
                   (reset! clear-focus* (:clear-focus! vm))
                   (reset! live-fp* (live-sync-fingerprint container))
                   (when-let [on-mount (:presentation-on-mount! container)]
                     (on-mount container))
                   ;; Non-tabbed wireless hosts still list on mount. Tabbed hosts
                   ;; defer until first wireless tab (main attach-panel! lazy).
                   (when (and (wireless-config container)
                              (not (tech-tabs/tech-tabs-enabled? container)))
                     (refresh-wireless-list!))
                   {:mount (:mount vm)
                    :frame! (fn []
                              (when-let [frame! (:presentation-frame! container)]
                                (frame! container))
                              ;; Cheap fingerprint each frame (DataSlot atoms +
                              ;; optional anim signature). Full snapshot rebuild
                              ;; only when the fingerprint changes.
                              (let [fp (live-sync-fingerprint container)]
                                (when (not= fp @live-fp*)
                                  (refresh!))))
                    :on-close (fn []
                                (when-let [close (or (:presentation-close-fn container)
                                                     (:close-fn container))]
                                  (close container)))}))}))
