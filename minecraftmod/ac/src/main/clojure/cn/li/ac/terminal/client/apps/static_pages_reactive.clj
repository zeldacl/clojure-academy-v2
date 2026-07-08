(ns cn.li.ac.terminal.client.apps.static-pages-reactive
  "Complete reactive replacement for static_pages.clj — dsl-based text page builder."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.dsl :as dsl]))

(defn create-text-page
  "Build reactive static text page. Same API as old create-text-page-gui.
   {:title str :size [w h] :lines [str...] :title-font-size n :line-font-size n}"
  [{:keys [title size lines title-font-size line-font-size]}]
  (let [r (rt/create-runtime)
        [w h] size
        line-h (if (< w 420) 13 15)
        spec (dsl/group {:id :root :w w :h h}
               ;; Background
               (dsl/image {:id :bg :x 0 :y 0 :w w :h h
                           :src (modid/asset-path "textures" "guis/data_terminal/app_back.png")})
               ;; Title
               (dsl/text {:id :title :x 0 :y 20 :w w :h 30
                          :text (or title "") :font-size (or title-font-size 12) :color 0xFFFFFFFF})
               ;; Content lines
               (dsl/group {:id :content :x 30 :y 70 :w (- w 60) :h (- h 70)}
                 (into []
                   (map-indexed
                     (fn [idx line]
                       (dsl/text {:id (keyword (str "line-" idx))
                                  :x 0 :y (* idx line-h)
                                  :w (- w 60) :h line-h
                                  :text (or line "") :font-size (or line-font-size 8)
                                  :color 0xFFFFFFFF}))
                     lines))))]
    (rt/build! r spec)
    r))
