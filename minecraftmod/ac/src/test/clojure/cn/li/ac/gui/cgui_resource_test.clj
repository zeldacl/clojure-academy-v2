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
