(ns cn.li.ac.item.media
  "Media items - information storage devices for Wireless Matrix
  
  Media items store information/music that can be transmitted through wireless network."
  (:require [cn.li.mcmod.item.dsl :as idsl]
            [cn.li.mcmod.util.log :as log]))

(defonce ^:private media-installed? (atom false))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init-media! []
  (when (compare-and-set! media-installed? false true)
    (idsl/register-item!
      (idsl/create-item-spec
        "media_0"
        {:max-stack-size 1
         :creative-tab :misc
         :properties {:display-name "Sisters' Noise"
                      :tooltip ["音乐: 妹妹的噪音 (Sisters\\'s Noise)"
                                "来自某某动画的BGM"]
                      :model-texture "media_sisters_noise"}}))
    (idsl/register-item!
      (idsl/create-item-spec
        "media_1"
        {:max-stack-size 1
         :creative-tab :misc
         :properties {:display-name "Only My Railgun"
                      :tooltip ["音乐: 仅有我的超电磁炮 (Only My Railgun)"
                                "来自某某动画的OP曲"]
                      :model-texture "media_only_my_railgun"}}))
    (idsl/register-item!
      (idsl/create-item-spec
        "media_2"
        {:max-stack-size 1
         :creative-tab :misc
         :properties {:display-name "Level5 Judgelight"
                      :tooltip ["音乐: 5级审判之光 (Level5 Judgelight)"
                                "来自某某动画的ED曲"]
                      :model-texture "media_level5_judgelight"}}))
    (log/info "Media items initialized: media-0, media-1, media-2")))
