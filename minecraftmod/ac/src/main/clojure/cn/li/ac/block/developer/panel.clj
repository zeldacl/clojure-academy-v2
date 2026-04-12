(ns cn.li.ac.block.developer.panel
  "Classic AcademyCraft `page_developer.xml`: load once, bind widgets to container + optional status poll.
  Full wireless list is embedded under `parent_right/area` from code (`gui.clj`); the Wireless tab reuses the same page."
  (:require [clojure.string :as str]
            [cn.li.mcmod.gui.cgui :as cgui]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.gui.xml-parser :as cgui-doc]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.i18n :as i18n]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.gui.tech-ui-common :as tech-ui]
            [cn.li.ac.client.platform-bridge :as client-bridge]
            [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.category :as acat]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.entity :as entity]))

(defn- dev-msg [action]
  (msg-registry/msg :developer action))

(defn- textbox-of [widget]
  (cgui/get-widget-component widget :textbox))

(defn- set-text-path! [root path text]
  (when-let [w (cgui/find-widget root path)]
    (when-let [tb (textbox-of w)]
      (comp/set-text! tb (str text)))))

(defn- set-progress-path! [root path ^double v]
  (when-let [w (cgui/find-widget root path)]
    (when-let [pb (cgui/get-widget-component w :progressbar)]
      (comp/set-progress! pb v))))

(defn- set-drawtexture-path! [root path texture-path]
  (when (string? texture-path)
    (when-let [w (cgui/find-widget root path)]
      (when-let [dt (comp/get-drawtexture-component w)]
        (comp/set-texture! dt texture-path)))))

(defn- texture-path-from-category-icon [icon-str]
  (when (string? icon-str)
    (if (str/starts-with? icon-str "textures/")
      (modid/asset-path "textures" (subs icon-str (count "textures/")))
      (modid/asset-path "textures" icon-str))))

(defn- default-ability-icon-path []
  (modid/asset-path "textures" "abilities/electromaster/icon.png"))

(defn- clamp01 ^double [^double x]
  (min 1.0 (max 0.0 x)))

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
      (cgui/create-widget :name "main" :pos [0 0] :size [400 187]))))

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
    (when-let [learn (cgui/find-widget root "parent_left/panel_ability/btn_upgrade")]
      (events/on-left-click learn
        (fn [_]
          (when (and pl (:tile-entity container))
            (let [tile (:tile-entity container)
                  uuid-str (str (entity/player-get-uuid pl))
                  pos (net-helpers/tile-pos-payload tile)
                  bid (platform-be/get-block-id tile)
                  dtype (if (= (name (or bid "")) "developer-advanced") :advanced :normal)]
              (client-bridge/open-skill-tree-screen! uuid-str (merge pos {:developer-type dtype})))))))
    (when-let [wbtn (cgui/find-widget root "parent_left/panel_machine/button_wireless")]
      (when switch-wireless-tab!
        (events/on-left-click wbtn (fn [_] (switch-wireless-tab!)))))
    (events/on-frame root
      (fn [_]
        (let [e (double (or @(:energy container) 0.0))
              me (max 1.0 (double (or @(:max-energy container) 1.0)))
              dev? (boolean (or @(:is-developing container) false))
              bw (max 1.0 (double (or @(:wireless-bandwidth container) 1.0)))
              sync-in (double (or @(:wireless-inject-last-tick container) 0.0))
              sync01 (clamp01 (/ sync-in bw))
              uuid-str (when pl (str (entity/player-get-uuid pl)))
              pstate (when uuid-str (ps/get-player-state uuid-str))
              ad (:ability-data pstate)
              cat-id (:category-id ad)
              cat (when cat-id (acat/get-category cat-id))
              ab-name (if cat
                        (i18n/translate (:name-key cat))
                        "—")
              icon-path (or (some-> cat :icon texture-path-from-category-icon)
                             (default-ability-icon-path))
              lvl (long (:level ad 1))
              thresh (when (and cat-id (not (>= lvl 5)))
                       (learning/level-up-threshold cat-id))
              level-prog (double (:level-progress ad 0.0))
              cat-prog01 (if (and thresh (pos? thresh))
                           (clamp01 (/ level-prog thresh))
                           (if (>= lvl 5) 1.0 0.0))
              exp-label (if (>= lvl 5)
                          "MAX"
                          (if thresh
                            (format "EXP %.0f%%" (* 100.0 cat-prog01))
                            "—"))
              level-label (if dev?
                            "Learning"
                            (format "Level %d" lvl))]
          (set-text-path! root "parent_left/panel_ability/text_abilityname" ab-name)
          (set-drawtexture-path! root "parent_left/panel_ability/logo_ability" icon-path)
          (set-text-path! root "parent_left/panel_ability/text_exp" exp-label)
          (set-text-path! root "parent_left/panel_ability/text_level" level-label)
          (set-progress-path! root "parent_left/panel_ability/logo_progress" cat-prog01)
          (set-progress-path! root "parent_left/panel_machine/progress_power" (clamp01 (/ e me)))
          (set-progress-path! root "parent_left/panel_machine/progress_syncrate" sync01)
          (sync-remote-node-name! root container last-net-ms))))
    root))
