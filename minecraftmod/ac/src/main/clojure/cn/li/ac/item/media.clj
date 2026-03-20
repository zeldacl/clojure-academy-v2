(ns cn.li.ac.item.media
  "Media items - information storage devices for Wireless Matrix
  
  Media items store information/music that can be transmitted through wireless network."
  (:require [cn.li.mcmod.item.dsl :as idsl]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Media Items - Music/Information storage
;; ============================================================================

(idsl/defitem media-0
  :id "media_0"
  :max-stack-size 1
  :creative-tab :misc
  :properties {:display-name "Sisters' Noise"
               :tooltip ["音乐: 妹妹的噪音 (Sisters\\'s Noise)"
                         "来自某某动画的BGM"]
               :model-texture "media_sisters_noise"})

(idsl/defitem media-1
  :id "media_1"
  :max-stack-size 1
  :creative-tab :misc
  :properties {:display-name "Only My Railgun"
               :tooltip ["音乐: 仅有我的超电磁炮 (Only My Railgun)"
                         "来自某某动画的OP曲"]
               :model-texture "media_only_my_railgun"})

(idsl/defitem media-2
  :id "media_2"
  :max-stack-size 1
  :creative-tab :misc
  :properties {:display-name "Level5 Judgelight"
               :tooltip ["音乐: 5级审判之光 (Level5 Judgelight)"
                         "来自某某动画的ED曲"]
               :model-texture "media_level5_judgelight"})

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init-media! []
  (log/info "Media items initialized: media-0, media-1, media-2"))
