(ns app.wallpaper-generator
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :refer [<! >! timeout]]))

(defprotocol WallpaperGenerator
  (generate [this width height]))

(def line
  (reify WallpaperGenerator
    (generate [this width height]
      (go
        (<! (timeout 3000))
        "images/background.png"))))
