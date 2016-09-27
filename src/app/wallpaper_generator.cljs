(ns app.wallpaper-generator
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :refer [>! <! put! take! timeout chan]]
            [reagent.core :as r]))

(defn- rand-range [min max]
  (let [range (- max min)]
    (+ min (rand-int range))))

(defprotocol WallpaperGenerator
  (generate [this color-group-list width height]))

(def line
  (reify WallpaperGenerator
    (generate [this color-group-list width height]
      (go
        (let [length (if (> width height) width height)
              color-group (rand-nth color-group-list)
              max-lines 5
              min-lines 1
              max-line-width 50
              min-line-width 5
              lines (for [i (range (rand-range min-lines (inc max-lines)))]
                      {:width (rand-range min-line-width (inc max-line-width))
                       :vertical? (rand-nth [true false])
                       :color (rand-nth (:foreground color-group))})
              lines (sort #(if (= (:vertical? %1) (:vertical? %2))
                             (> (:width %1) (:width %2))
                             (rand-nth [true false]))
                          lines)
              background (rand-nth (:background color-group))
              tmp (js/document.createElement "div")]
          (set! (.-id tmp) "line-tmp")
          (set! (.-display (.-style tmp)) "none")
          (.appendChild js/document.body tmp)

          (-> [:svg {:height length :width length :viewBox "0 0 100 100"}
               [:defs
                [:filter {:id "shadow" :height "200%" :width "200%" :x "-50%" :y "-50%"}
                 [:feOffset {:result "offOut" :in "SourceAlpha" :dx 0 :dy 0}]
                 [:feGaussianBlur {:in "offOut" :result "blurOut" :stdDeviation 1}]
                 [:feBlend {:in "SourceGraphic" :in2 "blurOut" :mode "normal"}]]
                [:radialGradient {:id "grad"}
                 [:stop {:offset "0%" :style {:stop-opacity 0}}]
                 [:stop {:offset "30%" :style {:stop-color "black" :stop-opacity 0.05}}]
                 [:stop {:offset "60%" :style {:stop-color "black" :stop-opacity 0.3}}]
                 [:stop {:offset "100%" :style {:stop-color "black" :stop-opacity 0.7}}]]]
               [:rect {:width "100%" :height "100%" :fill background}]

               (loop [lines lines, result [:g {:transform (str "rotate(" (* (rand-int 20) 5) " 50 50)")}]]
                 (if-let [line (first lines)]
                   (let [line-data (-> {:fill (:color line) :filter "url(#shadow)"}
                                       (assoc (if (:vertical? line) :width :height) (:width line))
                                       (assoc (if (:vertical? line) :height :width) 150)
                                       (assoc (if (:vertical? line) :x :y) (rand-int 100))
                                       (assoc (if (:vertical? line) :y :x) -25))
                         line [:rect line-data]]
                     (recur (rest lines) (conj result line)))
                   result))

               [:rect {:width "150%" :height "150%" :x "-25%" :y "-25%" :fill "url(#grad)"}]]
              (r/render tmp))

          (let [svg (.querySelector js/document "#line-tmp svg")
                svg-data (.serializeToString (js/XMLSerializer.) svg)
                img (js/document.createElement "img")
                canvas (js/document.createElement "canvas")
                ctx (.getContext canvas "2d")
                uri (str "data:image/svg+xml;base64," (js/btoa svg-data))
                result-chan (chan)]
            (.setAttribute img "src" uri)
            (.appendChild tmp img)
            (set! (.-width canvas) length)
            (set! (.-height canvas) length)
            (set! (.-onload img)
                  #(do (.drawImage ctx img 0 0)
                       (put! result-chan (.toDataURL canvas "image/png"))
                       (.removeChild js/document.body tmp)))
            (<! result-chan)))))))
