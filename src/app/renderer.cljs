(ns app.renderer
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [reagent.core :as r]
            [cljs-http.client :as http]
            [cljs.core.async :refer [<!]]
            [clojure.string :as string]
            [html2canvas.core :refer [html2canvas]]))

(def electron    (js/require "electron"))
(def ipcRenderer (.-ipcRenderer electron))
(def remote      (.-remote electron))

(def current-window (.getCurrentWindow remote))

(defn query-selecor [q]
  (.querySelector js/document q))

(defn query-selecor-all [q]
  (.querySelectorAll js/document q))

(defn title-bar []
  [:div#title-bar
   [:div.toolbar
    [:button.mini-size {:on-click #(.minimize current-window)}]
    [:button.close {:on-click #(.close current-window)}]]])

(defn scroll-to-tab [id]
  (let [element (query-selecor (str "#tab" id))
        offset-top (.-offsetTop element)
        parent (.-parentElement element)
        parent-offset-top (.-offsetTop parent)
        relative-top (- offset-top parent-offset-top)
        distance (- relative-top (.-scrollTop parent))
        move (if (> distance 0) + -)
        tick 10
        speed 20
        step (quot (if (> distance 0) distance (- distance)) speed)]
    (doall
     (for [i (range (inc step))]
       (js/setTimeout
        #(set! (.-scrollTop parent)
               (if (= i step)
                 relative-top
                 (move (.-scrollTop parent) speed)))
        (* tick i))))))

(defn tabs [tabs-state title-list]
  (into
   [:div.tabs>div]
   (map-indexed
    (fn [id title]
      [:div.tab
       {:class (when (= id (:focus @tabs-state)) "focus")
        :on-click (fn [e] (swap! tabs-state assoc :focus id))}
       [:a title]]) title-list)))

(defn tab-contents [tabs-state contents]
  (into
   [:div.tab-contents>div
    {:style {:transform (str "translateY(-" (* (:focus @tabs-state) 100) "%)")}}]
   (doall
    (for [[title content] (partition 2 contents)]
      [:div.block>div
       [:h1 title]
       content]))))

(defn contents [& contents]
  (let [title-list (take-nth 2 contents)
        tabs-state (r/atom {:focus 0})]
    [:div.contents
     [tabs tabs-state title-list] [tab-contents tabs-state contents]]))

(defn body []
  [:div [title-bar]
   [contents
    "全局设置" [:div "..."]
    "自动切换" [:div "..."]]])

(defn init []
  (enable-console-print!)
  (r/render body (query-selecor "#app")))
