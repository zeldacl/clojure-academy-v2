(ns cn.li.ac.gui.cgui-resource-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.xml :as xml]))

(def ^:private cgui-xml-test-paths
  ["assets/my_mod/guis/about.xml"
   "assets/my_mod/guis/life_record.xml"
   "assets/my_mod/guis/loctele.xml"
   "assets/my_mod/guis/loctele_new.xml"
   "assets/my_mod/guis/media_player.xml"
   "assets/my_mod/guis/media_player_aux.xml"
   "assets/my_mod/guis/preset_edit.xml"
   "assets/my_mod/guis/rework/page_config.xml"
   "assets/my_mod/guis/rework/page_developer.xml"
   "assets/my_mod/guis/rework/page_imagfusor.xml"
   "assets/my_mod/guis/rework/page_interfere.xml"
   "assets/my_mod/guis/rework/page_inv.xml"
   "assets/my_mod/guis/rework/page_med_synth.xml"
   "assets/my_mod/guis/rework/page_metalformer.xml"
   "assets/my_mod/guis/rework/page_solar.xml"
   "assets/my_mod/guis/rework/page_windbase.xml"
   "assets/my_mod/guis/rework/page_wireless.xml"
   "assets/my_mod/guis/rework/pageselect.xml"
   "assets/my_mod/guis/settings.xml"
   "assets/my_mod/guis/terminal.xml"
   "assets/my_mod/guis/terminal_installing.xml"
   "assets/my_mod/guis/tutorial.xml"
   "assets/my_mod/guis/tutorial_windows.xml"
   "assets/my_mod/guis/ui_edit.xml"])

(defn verification-ok? []
  (boolean
   (and (every? #(some? (io/resource %)) cgui-xml-test-paths)
        (every? (fn [p]
                  (when-let [r (io/resource p)]
                    (not (str/includes? (slurp r) "cn.lambdalib2"))))
                cgui-xml-test-paths)
        (every? (fn [p]
                  (when-let [r (io/resource p)]
                    (try (boolean (xml/parse (io/input-stream r)))
                         (catch Exception _ false))))
                cgui-xml-test-paths))))

(deftest cgui-xml-migration-resources-test
  (is (true? (verification-ok?))))

(def ^:private developer-widget-path-contract
  #{"main"
    "main/parent_left"
    "main/parent_left/panel_ability"
    "main/parent_left/panel_ability/btn_upgrade"
    "main/parent_left/panel_machine"
    "main/parent_left/panel_machine/button_wireless"
    "main/parent_left/panel_machine/button_wireless/text_nodename"
    "main/parent_left/panel_machine/progress_power"
    "main/parent_left/panel_machine/progress_syncrate"
    "main/parent_right"
    "main/parent_right/area"})

(defn- widget-node? [node]
  (and (map? node)
       (= :Widget (:tag node))
       (string? (get-in node [:attrs :name]))))

(defn- widget-children [node]
  (->> (:content node)
       (filter map?)))

(defn- collect-widget-paths
  ([node]
   (collect-widget-paths node nil))
  ([node parent-path]
   (let [this-name (when (widget-node? node)
                     (get-in node [:attrs :name]))
         this-path (when this-name
                     (if parent-path
                       (str parent-path "/" this-name)
                       this-name))
         child-parent (or this-path parent-path)
         child-paths (mapcat #(collect-widget-paths % child-parent)
                             (widget-children node))]
     (if this-path
       (cons this-path child-paths)
       child-paths))))

(deftest page-developer-widget-path-contract-test
  (let [res (io/resource "assets/my_mod/guis/rework/page_developer.xml")
        parsed (xml/parse (io/input-stream res))
        paths (set (collect-widget-paths parsed))]
    (is (every? paths developer-widget-path-contract))))

(def ^:private interferer-widget-path-contract
  #{"main"
    "main/panel_whitelist"
    "main/panel_whitelist/btn_add"
    "main/panel_whitelist/btn_remove"
    "main/panel_whitelist/btn_up"
    "main/panel_whitelist/btn_down"
    "main/panel_whitelist/zone_whitelist"
    "main/panel_whitelist/zone_whitelist/element"
    "main/panel_whitelist/zone_whitelist/element/element_name"
    "main/panel_config"
    "main/panel_config/element_switch"
    "main/panel_config/element_switch/element_btn_switch"
    "main/panel_config/element_range"
    "main/panel_config/element_range/element_text_range"})

(deftest page-interfere-widget-path-contract-test
  (let [res (io/resource "assets/my_mod/guis/rework/page_interfere.xml")
        parsed (xml/parse (io/input-stream res))
        paths (set (collect-widget-paths parsed))]
    (is (every? paths interferer-widget-path-contract))))
