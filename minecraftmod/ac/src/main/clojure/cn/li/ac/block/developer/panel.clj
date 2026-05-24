(ns cn.li.ac.block.developer.panel
  "Classic AcademyCraft `page_developer.xml`: load once, bind widgets to container + optional status poll.
  Full wireless list is embedded under `parent_right/area` from code (`gui.clj`); the Wireless tab reuses the same page."
  (:require [clojure.string :as str]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.cgui-widget-model :as cgui-model]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.gui.xml-parser :as cgui-doc]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.i18n :as i18n]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.gui.tech-ui-common :as tech-ui]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.ac.ability.service.player-state :as ps]
            [cn.li.ac.ability.registry.category :as acat]
            [cn.li.ac.ability.domain.developer :as developer]
            [cn.li.ac.ability.util.balance :as bal]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.ability.server.service.learning :as learning]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.mcmod.platform.be :as platform-be]))

(defn- dev-msg [action]
  (msg-registry/msg :developer action))

(defn- textbox-of [widget]
  (cgui-model/get-widget-component widget :textbox))

(defn- set-text-path! [root path text]
  (when-let [w (cgui-core/find-widget root path)]
    (when-let [tb (textbox-of w)]
      (comp/set-text! tb (str text)))))

(defn- set-progress-path! [root path ^double v]
  (when-let [w (cgui-core/find-widget root path)]
    (when-let [pb (cgui-model/get-widget-component w :progressbar)]
      (comp/set-progress! pb v))))

(defn- set-drawtexture-path! [root path texture-path]
  (when (string? texture-path)
    (when-let [w (cgui-core/find-widget root path)]
      (when-let [dt (comp/get-drawtexture-component w)]
        (comp/set-texture! dt texture-path)))))

(defn- set-visible-path!
  [root path visible?]
  (when-let [w (cgui-core/find-widget root path)]
    (cgui-core/set-visible! w visible?)))

(defn- texture-path-from-category-icon [icon-str]
  (when (string? icon-str)
    (if (str/starts-with? icon-str "textures/")
      (modid/asset-path "textures" (subs icon-str (count "textures/")))
      (modid/asset-path "textures" icon-str))))

(defn- default-ability-icon-path []
  (modid/asset-path "textures" "abilities/electromaster/icon.png"))

(defn- normalize-tier
  [tier]
  (let [k (keyword (or tier :normal))]
    (if (developer/developer-type? k) k :normal)))

(defn- current-developer-type
  [container]
  (let [tile (:tile-entity container)
        block-tier (when tile
                     (some-> (platform-be/get-block-id tile)
                             developer/developer-type-for-block-id))
        state-tier (some-> (:tier container)
                           deref
                           normalize-tier)]
    (or block-tier state-tier :normal)))

(defn- category-ui-model
  [{:keys [ad cat dev? developer-type energy max-energy sync-in bandwidth]}]
  (let [cat-id (:category-id ad)
        has-category? (boolean cat)
        lvl (long (:level ad 1))
        level-prog (double (:level-progress ad 0.0))
        thresh (when (and cat-id (not (>= lvl 5)))
                 (learning/level-up-threshold cat-id ad))
        cat-prog01 (if has-category?
                     (if (and thresh (pos? thresh))
                       (bal/clamp01 (/ level-prog thresh))
                       (if (>= lvl 5) 1.0 0.0))
                     0.0)
        can-upgrade? (and has-category?
                          (< lvl 5)
                          (developer/gte? developer-type (developer/min-for-level (inc lvl))))
        ability-name (if has-category?
                       (i18n/translate (:name-key cat))
                       "—")
        icon-path (if has-category?
                    (or (some-> cat :icon texture-path-from-category-icon)
                        (default-ability-icon-path))
                    (default-ability-icon-path))
        exp-label (if has-category?
                    (if (>= lvl 5)
                      "MAX"
                      (if thresh
                        (format "EXP %.0f%%" (* 100.0 cat-prog01))
                        "—"))
                    "—")
        level-label (cond
                      dev? "Learning"
                      (not has-category?) "No Category"
                      :else (format "Level %d" lvl))]
    {:has-category? has-category?
     :can-upgrade? can-upgrade?
     :ability-name ability-name
     :icon-path icon-path
     :exp-label exp-label
     :level-label level-label
     :cat-prog01 cat-prog01
     :power01 (bal/clamp01 (/ energy max-energy))
     :sync01 (bal/clamp01 (/ sync-in bandwidth))}))

(defn- current-ui-model
  [container player]
  (let [energy (double (or @(:energy container) 0.0))
        max-energy (max 1.0 (double (or @(:max-energy container) 1.0)))
        dev? (boolean (or @(:is-developing container) false))
        bandwidth (max 1.0 (double (or @(:wireless-bandwidth container) 1.0)))
        sync-in (double (or @(:wireless-inject-last-tick container) 0.0))
        uuid-str (when player (uuid/player-uuid player))
        pstate (when uuid-str (ps/get-player-state uuid-str))
        ad (:ability-data pstate)
        cat-id (:category-id ad)
        cat (when cat-id (acat/get-category cat-id))
        developer-type (current-developer-type container)]
    (category-ui-model {:ad ad
                        :cat cat
                        :dev? dev?
                        :developer-type developer-type
                        :energy energy
                        :max-energy max-energy
                        :sync-in sync-in
                        :bandwidth bandwidth})))

(defn- upgrade-context
  [container player]
  (let [model (current-ui-model container player)]
    (when (and (:can-upgrade? model)
               (not (boolean (or @(:is-developing container) false)))
               (:tile-entity container)
               player)
      (let [tile (:tile-entity container)
            uuid-str (uuid/player-uuid player)
            pos (net-helpers/tile-pos-payload tile)
            dtype (current-developer-type container)]
        {:player-uuid uuid-str
         :learn-context (merge pos {:developer-type dtype})}))))

(defn load-classic-developer-page
  "Return root `main` from `guis/rework/page_developer.xml`, with nested `ui_*` breathe like the original."
  []
  (try
    (let [doc (cgui-doc/read-xml (modid/namespaced-path "guis/rework/page_developer.xml"))
          root (cgui-doc/get-widget doc "main")]
      (tech-ui/apply-breathe-to-ui-descendants! root)
      root)
    (catch Exception e
      (log/error "developer classic XML:" (ex-message e))
      (cgui-core/create-widget :name "main" :pos [0 0] :size [400 187]))))

(defn- sync-remote-node-name!
  [root container last-net-ms]
  (let [now (long (System/currentTimeMillis))
        tile (:tile-entity container)]
    (when (and tile (> (- now ^long @last-net-ms) 500))
      (reset! last-net-ms now)
      (net-client/send-to-server (dev-msg :get-status) (net-helpers/tile-pos-payload tile)
        (fn [resp]
          (when (map? resp)
            (set-text-path! root "parent_left/panel_machine/button_wireless/text_nodename"
              (if-let [n (:linked resp)]
                (or (:node-name n) "—")
                "—"))))))))

(defn attach-classic-developer-bindings!
  "`switch-wireless-tab!` — thunk (e.g. `tabbed-gui/switch-tab!` to the `:wireless` page)."
  [root container {:keys [switch-wireless-tab!]}]
  (let [pl (:player container)
        last-net-ms (atom 0)]
    (when-let [learn (cgui-core/find-widget root "parent_left/panel_ability/btn_upgrade")]
      (events/on-left-click learn
        (fn [_]
          (when-let [{:keys [player-uuid learn-context]} (upgrade-context container pl)]
            (client-bridge/open-skill-tree-screen! player-uuid learn-context)))))
    (when-let [wbtn (cgui-core/find-widget root "parent_left/panel_machine/button_wireless")]
      (when switch-wireless-tab!
        (events/on-left-click wbtn (fn [_] (switch-wireless-tab!)))))
    (events/on-frame root
      (fn [_]
        (let [{:keys [ability-name
                      icon-path
                      exp-label
                      level-label
                      cat-prog01
                      power01
                      sync01
                      can-upgrade?]} (current-ui-model container pl)]
          (set-text-path! root "parent_left/panel_ability/text_abilityname" ability-name)
          (set-drawtexture-path! root "parent_left/panel_ability/logo_ability" icon-path)
          (set-text-path! root "parent_left/panel_ability/text_exp" exp-label)
          (set-text-path! root "parent_left/panel_ability/text_level" level-label)
          (set-progress-path! root "parent_left/panel_ability/logo_progress" cat-prog01)
          (set-progress-path! root "parent_left/panel_machine/progress_power" power01)
          (set-progress-path! root "parent_left/panel_machine/progress_syncrate" sync01)
          (set-visible-path! root "parent_left/panel_ability/btn_upgrade" can-upgrade?)
          (sync-remote-node-name! root container last-net-ms))))
    root))
