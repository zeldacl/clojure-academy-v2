(ns cn.li.ac.terminal.client.apps.static-pages-reactive
  "Reactive static text page — replaces create-text-page-gui widget builder."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.dsl :as dsl]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.ac.config.modid :as modid]))

(defn create-text-page
  "Build reactive static text page.
   {:title str :size [w h] :lines [str...] :title-font-size n :line-font-size n}"
  [{:keys [title size lines title-font-size line-font-size]}]
  (let [r (rt/create-runtime)
        [w h] size
        children (concat
                  [{:kind :image :id :bg :props {:x 0 :y 0 :size [w h]
                         :src (modid/asset-path "textures" "guis/data_terminal/app_back.png")}}
                   {:kind :text :id :title :props {:x 0 :y 20 :text title
                         :font-size (or title-font-size 12) :color 0xFFFFFFFF}}]
                  (map-indexed
                    (fn [idx line]
                      {:kind :text :id (keyword (str "line-" idx))
                       :props {:x 30 :y (+ 70 (* idx (if (< w 420) 13 15)))
                               :text line :font-size (or line-font-size 10) :color 0xFFCCCCCC}})
                    lines))]
    (rt/build! r {:kind :group :id :root :props {:w w :h h} :children (vec children)})
    r))
