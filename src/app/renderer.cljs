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

(defn tabs [tabs-state title-list]
  (into
   [:div#tabs>div]
   (for [title title-list]
     (let [id (hash title)]
       [:div.tab
        {:class (when (= id (:focus @tabs-state)) "focus" )
         :on-click (fn [e] (swap! tabs-state assoc :focus id))}
        [:a title]]))))

(defn tab-contents [tabs-state contents]
  (into
   [:div#tab-contents>div]
   (for [[title content] (partition 2 contents)]
     (let [id (hash title)]
       [:div.block
        {:style (when-not (= id (:focus @tabs-state))
                  {:display "none"})}
        [:h1 title]
        content]))))

(defn contents [& contents]
  (let [title-list (take-nth 2 contents)
        tabs-state (r/atom {:focus (hash (first title-list))})]
    [:div#contents
     [tabs tabs-state title-list] [tab-contents tabs-state contents]]))

(defn body []
  [:div [title-bar]
   [contents
    "全局设置" [:div "..."]
    "自动切换" [:div "..."]]])

(defn init []
  (enable-console-print!)
  (r/render body (query-selecor "#app")))
