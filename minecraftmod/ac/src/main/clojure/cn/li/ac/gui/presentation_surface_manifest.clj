(ns cn.li.ac.gui.presentation-surface-manifest
  "Single inventory for the logical Presentation surfaces.

   A surface is a mounted controller, not a unique artifact file. Several
   application controllers intentionally share the generic application
   artifact; container surfaces share machine-container where their content
   differs only in Menu/Slot schema."
  (:require [cn.li.ac.gui.manifest :as gui-manifest]))

(def application-surfaces
  [{:id :combat-hud :artifact :academy.app/combat-hud :controller 'cn.li.ac.ability.client.presentation-hud/mount-combat-hud!}
   {:id :terminal :artifact :academy.app/terminal :controller 'cn.li.ac.terminal.client.presentation-terminal/mount-terminal!}
   {:id :about :artifact :academy.app/application :controller 'cn.li.ac.terminal.client.apps.about-reactive/open!}
   {:id :freq-transmitter-scan :artifact :academy.app/application :controller 'cn.li.ac.terminal.client.apps.freq-transmitter-reactive/open!}
   {:id :media-player :artifact :academy.app/application :controller 'cn.li.ac.terminal.client.apps.media-reactive/open!}
   {:id :settings :artifact :academy.app/settings :controller 'cn.li.ac.terminal.client.apps.settings-reactive/open!}
   {:id :tutorial :artifact :academy.app/application :controller 'cn.li.ac.terminal.client.apps.tutorial-reactive/open!}
   {:id :ui-customize :artifact :academy.app/application :controller 'cn.li.ac.terminal.client.apps.ui-customize-reactive/open!}
   {:id :skill-tree :artifact :academy.app/application :controller 'cn.li.ac.terminal.client.apps.skill-tree/open!}
   {:id :preset-editor :artifact :academy.app/application :controller 'cn.li.ac.ability.client.screens.preset-editor-reactive/open!}
   {:id :portable-developer :artifact :academy.app/application :controller 'cn.li.ac.item.developer-portable-reactive/open!}
   {:id :install-effect :artifact :academy.app/application :controller 'cn.li.ac.terminal.client.install-effect-reactive/open!'} 
   {:id :location-teleport :artifact :academy.app/location-teleport :controller 'cn.li.ac.content.ability.teleporter.location-teleport-presentation/open!'}])

(defn- container-surface
  [[gui-key definition]]
  {:id gui-key
   :kind :container
   :artifact (case gui-key
               :wireless-matrix :academy.app/wireless-matrix
               :wireless-node :academy.app/wireless-node
               :developer :academy.app/developer
               :academy.app/machine-container)
   :controller (:screen-factory-fn-kw definition)})

(def container-surfaces
  (mapv container-surface gui-manifest/gui-definitions))

(def active-surfaces
  (vec (concat application-surfaces container-surfaces)))

(defn coverage
  "Return the quantitative acceptance counters used by the build gate."
  [loaded-artifact? controller-loaded?]
  (let [total (count active-surfaces)
        containers (count container-surfaces)
        artifacts (count (filter #(loaded-artifact? (:artifact %)) active-surfaces))
        controllers (count (filter #(controller-loaded? (:controller %)) active-surfaces))]
    {:active-surfaces [total total]
     :container-mappings [containers containers]
     :artifact-load [artifacts total]
     :controller-load [controllers total]}))
