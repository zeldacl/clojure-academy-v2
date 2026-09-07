(ns cn.li.ac.gui.tech-ui-tabs
  "Presentation-era TechUI left tabs (inv + wireless).

   Geometry/ids match main tech-ui-tabs-reactive; wiring uses container
   :tab-index + tabbed-gui/send-set-tab! instead of the retired generic widget runtime."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.gui.container.action-payload :as action-payload]
            [cn.li.mcmod.gui.tabbed-gui :as tabbed-gui]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(def inv-tab-id "inv")
(def wireless-tab-id "wireless")

(def default-pages
  [{:id inv-tab-id} {:id wireless-tab-id}])

(def tab-x -20.0)
(def tab-size 20.0)
(def tab-step-y 22.0)
(def ^:private tab-rgba-active (unchecked-int 0xFFFFFFFF))
(def ^:private tab-rgba-idle (unchecked-int 0x88FFFFFF))

(defn tech-tabs-enabled?
  [container]
  (boolean (:presentation-tech-tabs? container)))

(defn current-tab-index
  [container]
  (let [v (:tab-index container)]
    (int (cond
           (nil? v) 0
           (instance? clojure.lang.IDeref v) (or @v 0)
           :else v))))

(defn current-tab-id
  ([container] (current-tab-id container default-pages))
  ([container pages]
   (let [idx (current-tab-index container)
         n (count pages)]
     (when (and (pos? n) (>= idx 0) (< idx n))
       (:id (nth pages idx))))))

(defn page-visibility
  "Visibility flags for inv/wireless pages."
  ([container] (page-visibility container default-pages))
  ([container pages]
   (if-not (tech-tabs-enabled? container)
     {:inv-page-visible? true
      :wireless-page-visible? false
      :tech-tab-id inv-tab-id}
     (let [id (or (current-tab-id container pages) inv-tab-id)
           inv? (= id inv-tab-id)]
       ;; Explicit boolean — BindResolver treats nil as visible.
       {:inv-page-visible? (boolean inv?)
        :wireless-page-visible? (boolean (not inv?))
        :tech-tab-id id}))))

(defn tab-strip-items
  "Composite hit items for the left tab strip. Empty when tech-tabs disabled.

   Tabs stay at fixed geometry (main: x=-20, y=idx*22). Selection only
   changes tint — never layout-x."
  ([container] (tab-strip-items container default-pages))
  ([container pages]
   (if-not (tech-tabs-enabled? container)
     []
     (let [cur (current-tab-index container)]
       (mapv (fn [idx {:keys [id]}]
               (let [active? (= idx cur)]
                 ;; layout-x/y → composite arrange/hit; local x/y stay 0 so paint
                 ;; is not double-offset (same contract as terminal app tiles).
                 {:kind :image
                  :src (modid/asset-path "textures" (str "guis/icons/icon_" id ".png"))
                  :x 0.0
                  :y 0.0
                  :w tab-size
                  :h tab-size
                  :layout-x tab-x
                  :layout-y (double (* idx tab-step-y))
                  :tab-index (int idx)
                  :tab-id id
                  :selected? active?
                  :rgba (if active? tab-rgba-active tab-rgba-idle)}))
             (range)
             pages)))))

(defn mark-slot-anchors
  "Set each anchor :visible? only when the inv tab is active (or tabs disabled)."
  [container anchors]
  (let [visible? (if (tech-tabs-enabled? container)
                   (= inv-tab-id (or (current-tab-id container) inv-tab-id))
                   true)]
    (mapv #(assoc % :visible? visible?) (or anchors []))))

(defn switch-tab!
  "Update container :tab-index, sync to server, then optional on-switch."
  [container tab-index-or-id & [{:keys [pages on-switch]}]]
  (let [pages (or pages default-pages)
        idx (cond
              (integer? tab-index-or-id) (int tab-index-or-id)
              (string? tab-index-or-id)
              (or (first (keep-indexed (fn [i p] (when (= (:id p) tab-index-or-id) i)) pages)) 0)
              :else 0)
        idx (max 0 (min (dec (count pages)) idx))
        tab-id (:id (nth pages idx))
        tab* (:tab-index container)]
    (when (instance? clojure.lang.IDeref tab*)
      (reset! tab* idx))
    (when-let [cid (action-payload/menu-container-id container)]
      (let [owner (or (:owner container) (runtime-hooks/default-client-owner))]
        (tabbed-gui/send-set-tab! owner idx cid)))
    (when on-switch (on-switch tab-id idx))
    tab-id))

(defn snapshot-keys
  "Keys to merge into a Presentation container snapshot when tech-tabs opt-in."
  ([container] (snapshot-keys container nil))
  ([container anchors]
   (let [vis (page-visibility container)
         tabs (tab-strip-items container)]
     (cond-> (merge vis {:tech-tabs tabs})
       (some? anchors) (assoc :slot-anchors (mark-slot-anchors container anchors))))))
