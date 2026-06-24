(ns cn.li.ac.block.ability-interferer.gui
  "CLIENT-ONLY: Ability Interferer GUI"
  (:require [clojure.string :as str]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.cgui-screen :as cgui-screen]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.gui.spec :as gui-reg]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.block.ability-interferer.config :as cfg]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.block.gui.sync :as gui-sync]
            [cn.li.ac.block.ability-interferer.schema :as interferer-schema]
            [cn.li.ac.gui.manifest :as gui-manifest]
            [cn.li.ac.gui.tech-ui-common :as tech-ui]
            [cn.li.ac.item.item-energy-base :as energy-base]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.wireless.gui.tab :as wireless-tab]
            [cn.li.mcmod.gui.container.action-payload :as action-payload]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.energy.operations :as energy]))

(def ^:private interferer-slot-schema-id :ability-interferer)
(def ^:private interferer-gui-type :ability-interferer)

(defn- interferer-slot-layout
  "Match original AcademyCraft behavior:
   machine slot + player hotbar only (no player main inventory grid)."
  []
  (let [layout (slot-schema/get-slot-layout interferer-slot-schema-id)
        tile-slot-count (slot-schema/tile-slot-count interferer-slot-schema-id)
        tile-end (dec tile-slot-count)
        hotbar-start tile-slot-count
        hotbar-end (+ hotbar-start 8)]
    (assoc layout
           :player-inventory-mode :hotbar-only
           :ranges {:tile [0 tile-end]
                    :player-main [1 0]
                    :player-hotbar [hotbar-start hotbar-end]})))

(defn- msg [action]
  (msg-registry/msg interferer-gui-type action))

(defn- sync-whitelist-edit!
  [container]
  (when-let [edit-atom (:whitelist-edit container)]
    (reset! edit-atom (str/join "," (or @(:whitelist container) [])))))

(defn- clear-pending-state!
  [container]
  (when-let [pending-range (:pending-range container)]
    (reset! pending-range nil))
  (when-let [pending-enabled (:pending-enabled container)]
    (reset! pending-enabled nil)))

(defn- refresh-whitelist-view!
  [container]
  (when-let [refresh* (:refresh-whitelist-view container)]
    (when-let [f @refresh*]
      (f))))

(defn- after-sync-or-apply!
  [container _data]
  (sync-whitelist-edit! container)
  (clear-pending-state! container)
  (refresh-whitelist-view! container))

(def ^:private interferer-sync
  (gui-sync/schema-sync-fns interferer-schema/ability-interferer-schema
                            {:after-sync! (fn [container]
                                            (after-sync-or-apply! container nil))}))

(defn- create-container [tile player]
  (let [state (or (common/get-tile-state tile) {})]
    (gui-sync/create-schema-container interferer-schema/ability-interferer-schema
                                      tile
                                      player
                                      interferer-gui-type
                                      {:gui-id (gui-manifest/gui-id :ability-interferer)
                                       :state state
                  :base {:whitelist-edit (atom (str/join "," (get state :whitelist [])))
                    :focused-whitelist-name (atom nil)
                    :whitelist-scroll-index (atom 0)
                    :pending-range (atom nil)
                    :pending-enabled (atom nil)
                        :add-input-widget (atom nil)
                    :refresh-whitelist-view (atom nil)}})))

(defn- get-slot-count [_container]
  (slot-schema/tile-slot-count interferer-slot-schema-id))

(defn- get-slot-item [container slot-index]
  (common/get-slot-item-be container slot-index))

(defn- set-slot-item! [container slot-index item-stack]
  (common/set-slot-item-be! container slot-index item-stack {:inventory [nil]} identity))

(defn- slot-changed! [_container _slot-index] nil)

(defn- can-place-item? [_container _slot-index item-stack]
  "Only accept energy_unit items in the battery slot (matching original AcademyCraft)."
  (= :energy-unit (energy-base/get-energy-item-type item-stack)))

(defn- still-valid? [_container _player] true)

(defn- request-set-whitelist! [container names]
  (net-client/send-to-server
    (msg :set-whitelist)
    (action-payload/action-payload container {:whitelist (vec names)})))

(defn- request-set-range! [container new-range]
  (let [r (cfg/clamp-range new-range)]
    (when-let [pending-range (:pending-range container)]
      (reset! pending-range r))
    (net-client/send-to-server
      (msg :change-range)
      (action-payload/action-payload container {:range r}))))

(defn- request-set-enabled! [container v]
  (let [enabled? (boolean v)]
    (when-let [pending-enabled (:pending-enabled container)]
      (reset! pending-enabled enabled?))
    (net-client/send-to-server
      (msg :toggle-enabled)
      (action-payload/action-payload container {:enabled enabled?}))))

(def ^:private server-menu-sync! (:server-menu-sync! interferer-sync))

(def ^:private on-close (:on-close interferer-sync))
(defn- handle-button-click! [_container _button-id _player] nil)

(defn- update-switch-texture!
  "Update switch button texture + lum matching original GuiAbilityInterferer:
   color.setRed/Green/Blue(if(state) 1 else 0.6f) + changes texture."
  [switch-widget enabled?]
  (when-let [dt (comp/get-drawtexture-component switch-widget)]
    (comp/set-texture!
      dt
      (if enabled?
        (modid/asset-path "textures" "guis/button/button_switch_on.png")
        (modid/asset-path "textures" "guis/button/button_switch_off.png")))
    ;; Lum adjustment matching original: ON=1.0 (255), OFF=0.6 (153)
    (let [lum (if enabled? 0xFF 0x99)]
      (swap! (:state dt) assoc :color
             (unchecked-int (bit-or (bit-shift-left lum 16)
                                   (bit-shift-left lum 8)
                                   lum
                                   0xFF000000))))))

(defn- effective-range
  [container]
  (if-let [pending-range (:pending-range container)]
    (or @pending-range @(:range container))
    @(:range container)))

(defn- effective-enabled
  [container]
  (if-let [pending-enabled (:pending-enabled container)]
    (if (nil? @pending-enabled)
      @(:enabled container)
      @pending-enabled)
    @(:enabled container)))

(defn- visible-whitelist-window
  [names scroll-index window-size]
  (let [v (vec (or names []))
        total (count v)
        window-size (max 1 (int window-size))
        max-scroll (max 0 (- total window-size))
        start (-> (int (or scroll-index 0)) (max 0) (min max-scroll))
        end (min total (+ start window-size))]
    {:start start
     :max-scroll max-scroll
     :names (if (< start end) (subvec v start end) [])}))

(defn- whitelist-window-size
  []
  4)

(defn- normalize-add-name
  [raw]
  (let [n (str/trim (str raw))]
    (when-not (str/blank? n)
      n)))

(defn- add-whitelist-name
  [names raw-name]
  (let [name (normalize-add-name raw-name)
        current (vec (or names []))]
    (if (or (nil? name) (some #(= % name) current))
      current
      (-> (conj current name)
          distinct
          sort
          vec))))

(defn- remove-focused-whitelist-name
  [names focused-name]
  (let [v (vec (or names []))]
    (if (str/blank? (str focused-name))
      v
      (vec (remove #(= % focused-name) v)))))

(defn- cleanup-whitelist-elements!
  [zone template-id]
  (doseq [child (cgui-core/get-widgets zone)]
    (let [name (cgui-core/get-name child)]
      (when (and (not= name template-id)
                 (true? (get @(:metadata child) :interferer-whitelist-item?)))
        (cgui-core/remove-widget! zone child)))))

(defn- rebuild-whitelist-zone!
  [inv-window container]
  (when-let [zone (cgui-core/find-widget inv-window "panel_whitelist/zone_whitelist")]
    (when-let [template (cgui-core/find-widget inv-window "panel_whitelist/zone_whitelist/element")]
      (cgui-core/set-visible! template false)
      (cleanup-whitelist-elements! zone (cgui-core/get-name template))
      (let [all-names (vec (or @(:whitelist container) []))
            scroll-atom (:whitelist-scroll-index container)
            focused-atom (:focused-whitelist-name container)
            {:keys [start names]} (visible-whitelist-window all-names @scroll-atom (whitelist-window-size))
            item-height (max 16 (int (second (cgui-core/get-size template))))]
        (reset! scroll-atom start)
        (when (and (some? @focused-atom)
                   (not (some #(= % @focused-atom) all-names)))
          (reset! focused-atom nil))
        (doseq [[idx player-name] (map-indexed vector names)]
          (let [item (cgui-core/copy-widget template)
                y (* idx item-height)]
            (swap! (:metadata item) assoc :interferer-whitelist-item? true)
            (cgui-core/set-visible! item true)
            (cgui-core/set-pos! item 0 y)
            (when-let [name-widget (cgui-core/find-widget item "element_name")]
              (when-let [tb (comp/get-textbox-component name-widget)]
                (comp/set-text! tb player-name)))
            (when-let [dt (comp/get-drawtexture-component item)]
              (events/on-frame item
                (fn [_]
                  (swap! (:state dt) update :color
                         (fn [_]
                           (if (= player-name @focused-atom)
                             0xFFFFFFFF
                             0xB3FFFFFF))))))
            (events/on-left-click item
              (fn [_]
                (reset! focused-atom player-name)))
            (cgui-core/add-widget! zone item)))))))

(defn- close-add-input-widget!
  [panel container]
  (when-let [input-widget* (:add-input-widget container)]
    (when-let [w @input-widget*]
      (cgui-core/remove-widget! panel w)
      (reset! input-widget* nil))))

(defn- open-add-input-widget!
  [panel container]
  (close-add-input-widget! panel container)
  (let [box (cgui-core/create-widget :name "interferer_whitelist_input" :pos [50 5] :size [40 10])
        ;; Semi-transparent white background matching original DrawTexture(null).setColor(255,255,255,50)
        _ (comp/add-component! box (comp/draw-texture nil 0x32FFFFFF))
        tb (comp/text-box :text "" :font :ac-normal :font-size 10 :color 0xFF000000)
        tb-native (comp/add-component! box tb)]
    (comp/set-editable! tb-native true)
    (events/on-confirm-input tb-native
      (fn [new-text]
        (let [merged (add-whitelist-name @(:whitelist container) new-text)]
          (when (not= merged (vec @(:whitelist container)))
            (request-set-whitelist! container merged))
          (close-add-input-widget! panel container))))
    (events/on-lost-focus box
      (fn [_]
        (close-add-input-widget! panel container)))
    (cgui-core/add-widget! panel box)
    ;; Gain focus so keyboard input reaches the textbox (matching original box.gainFocus())
    (when-let [root (get @(:metadata container) :focus-root)]
      (cgui-screen/gain-focus! root box))
    (when-let [input-widget* (:add-input-widget container)]
      (reset! input-widget* box))))

(defn- wire-xml-controls!
  [inv-window container]
  (when-let [range-text (cgui-core/find-widget inv-window "panel_config/element_range/element_text_range")]
    (when-let [tb (comp/get-textbox-component range-text)]
      (comp/set-text! tb (.toString (double (effective-range container)))))
    (events/on-frame range-text
      (fn [_]
        (when-let [tb (comp/get-textbox-component range-text)]
          (comp/set-text! tb (.toString (double (effective-range container))))))))

  (when-let [switch-btn (cgui-core/find-widget inv-window "panel_config/element_switch/element_btn_switch")]
    (update-switch-texture! switch-btn (effective-enabled container))
    (events/unlisten! switch-btn :left-click)
    (events/on-left-click switch-btn
      (fn [_]
        (request-set-enabled! container (not (effective-enabled container)))
        (update-switch-texture! switch-btn (effective-enabled container))))
    (events/on-frame switch-btn
      (fn [_]
        (update-switch-texture! switch-btn (effective-enabled container)))))

  (when-let [btn-left (cgui-core/find-widget inv-window "panel_config/element_range/element_btn_left")]
    (events/unlisten! btn-left :left-click)
    (events/on-left-click btn-left
      (fn [_]
        (request-set-range! container (- (effective-range container) 10.0)))))

  (when-let [btn-right (cgui-core/find-widget inv-window "panel_config/element_range/element_btn_right")]
    (events/unlisten! btn-right :left-click)
    (events/on-left-click btn-right
      (fn [_]
        (request-set-range! container (+ (effective-range container) 10.0)))))

  (when-let [btn-add (cgui-core/find-widget inv-window "panel_whitelist/btn_add")]
    (events/unlisten! btn-add :left-click)
    (events/on-left-click btn-add
      (fn [_]
        (when-let [panel (cgui-core/find-widget inv-window "panel_whitelist")]
          (open-add-input-widget! panel container)))))

  (when-let [btn-remove (cgui-core/find-widget inv-window "panel_whitelist/btn_remove")]
    (events/unlisten! btn-remove :left-click)
    (events/on-left-click btn-remove
      (fn [_]
        (let [focused @(:focused-whitelist-name container)
              wl @(:whitelist container)
              updated (remove-focused-whitelist-name wl focused)]
          (when (not= (vec wl) updated)
            (request-set-whitelist! container updated))))))

  (when-let [btn-up (cgui-core/find-widget inv-window "panel_whitelist/btn_up")]
    (events/unlisten! btn-up :left-click)
    (events/on-left-click btn-up
      (fn [_]
        (let [all-names (vec (or @(:whitelist container) []))
              scroll-atom (:whitelist-scroll-index container)
              max-scroll (max 0 (- (count all-names) (whitelist-window-size)))]
          (swap! scroll-atom #(max 0 (min max-scroll (dec (int %)))))
          (rebuild-whitelist-zone! inv-window container)))))

  (when-let [btn-down (cgui-core/find-widget inv-window "panel_whitelist/btn_down")]
    (events/unlisten! btn-down :left-click)
    (events/on-left-click btn-down
      (fn [_]
        (let [all-names (vec (or @(:whitelist container) []))
              scroll-atom (:whitelist-scroll-index container)
              max-scroll (max 0 (- (count all-names) (whitelist-window-size)))]
          (swap! scroll-atom #(max 0 (min max-scroll (inc (int %)))))
          (rebuild-whitelist-zone! inv-window container)))))

  (when-let [refresh* (:refresh-whitelist-view container)]
    (reset! refresh* #(rebuild-whitelist-zone! inv-window container)))
  (rebuild-whitelist-zone! inv-window container))

(defn- create-screen [container minecraft-container _player]
  (let [inv-page (tech-ui/create-rework-page "guis/rework/page_interfere.xml")
        inv-window (:window inv-page)
        wireless-window (wireless-tab/create-wireless-panel
                          {:role :ability-interferer
                           :container container
                           :menu minecraft-container})
        pages [inv-page {:id "wireless" :window wireless-window}]
        max-e (fn [] (max 1.0 (double @(:max-energy container))))
        wl-text (fn []
                  (let [wl @(:whitelist container)]
                    (if (seq wl)
                      (str/join ", " wl)
                      "<empty>")))]
    (tech-ui/create-tech-screen-container
      {:pages pages
       :container container
       :minecraft-container minecraft-container
       :bind! (fn [root-widget]
                ;; Store root for focus management (matching original box.gainFocus())
                (swap! (:metadata container) assoc :focus-root root-widget)
                (wire-xml-controls! inv-window container))
       :build-info-area!
       (fn [info-area]
         (let [y0 (tech-ui/add-histogram info-area
                                         [(tech-ui/hist-buffer (fn [] (double @(:energy container))) max-e)]
                                         0)
               y1 (tech-ui/add-sepline info-area "Interferer" y0)
               y2 (tech-ui/add-property info-area "enabled" (fn [] (if (effective-enabled container) "ON" "OFF")) y1)
                 y3 (tech-ui/add-property info-area "range" (fn [] (.toString (double (effective-range container)))) y2)
               y4 (tech-ui/add-property info-area "affected" (fn [] (str @(:affected-player-count container))) y3)
               y5 (tech-ui/add-property info-area "owner" (fn [] @(:placer-name container)) y4)]
               (tech-ui/add-property info-area "whitelist" wl-text y5)))})))

(defn- interferer-container?
  [container]
  (and (map? container)
       (= (:container-type container) interferer-gui-type)
       (contains? container :tile-entity)
       (contains? container :energy)))

(def ^:private ability-interferer-gui-guard-lock
  (Object.))

(def ^:private ^:dynamic *ability-interferer-gui-installed?*
  false)

(defn init-ability-interferer-gui!
  []
  (when-not (var-get #'*ability-interferer-gui-installed?*)
    (locking ability-interferer-gui-guard-lock
      (when-not (var-get #'*ability-interferer-gui-installed?*)
        (slot-schema/register-slot-schema!
          {:schema-id interferer-slot-schema-id
           :slots [{:id :battery :type :energy :x 139 :y 25}]})
    (gui-reg/register-block-gui!
            (gui-manifest/gui-name :ability-interferer)
            (merge (gui-manifest/gui-registration :ability-interferer)
              {:slot-layout (interferer-slot-layout)
       :container-predicate interferer-container?
       :container-fn create-container
       :screen-fn create-screen
       :server-menu-sync-fn server-menu-sync!
       :validate-fn still-valid?
       :close-fn on-close
       :button-click-fn handle-button-click!
       :slot-count-fn get-slot-count
       :slot-get-fn get-slot-item
       :slot-set-fn set-slot-item!
       :slot-can-place-fn can-place-item?
              :slot-changed-fn slot-changed!}))
        (alter-var-root #'*ability-interferer-gui-installed?* (constantly true))
        (log/info "Ability Interferer GUI initialized")))))
