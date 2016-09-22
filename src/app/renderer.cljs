(ns app.renderer
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [reagent.core :as r]
            [cljs.core.async :refer [<! >! put! take!]]
            [app.wallpaper-generator :as w]))

(def electron    (js/require "electron"))
(def ipcRenderer (.-ipcRenderer electron))
(def remote      (.-remote electron))

(def current-window (.getCurrentWindow remote))

(defn query-selector [q]
  (.querySelector js/document q))

(defn query-selecor-all [q]
  (.querySelectorAll js/document q))

(defn title-bar []
  [:div#title-bar
   [:div.toolbar
    [:button.mini-size {:on-click #(.minimize current-window)}]
    [:button.close {:on-click #(.close current-window)}]]])

(defn scroll-to-tab [id]
  (let [element (query-selector (str "#tab" id))
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
       [:div content]]))))

(defn contents [& contents]
  (let [title-list (take-nth 2 contents)
        tabs-state (r/atom {:focus 0})]
    [:div.contents
     [tabs tabs-state title-list] [tab-contents tabs-state contents]]))

(def state (r/atom {:working? false
                    :wallpapers ["images/background.png"
                                 "./images/background.png"
                                 "file:./images/background.png"]}))

(defn preview []
  [:div#preview
   [:div#wallpaper-history>div
    (for [src (:wallpapers @state)]
      ^{:key (hash src)}
      [:img {:src src}])]
   [:div.toolbar
    [:button#new-wallpaper
     {:on-click (fn [e]
                  (swap! state assoc :working? true)
                  (take! (w/generate w/line 1920 1920)
                         (fn [result]
                           (swap! state assoc :working? false)
                           (swap! state update-in [:wallpapers] #(cons result %)))))
      :disabled (:working? @state)}
     [:div "新壁纸"]]]])

(defn body []
  [:div [title-bar]
   [contents
    "壁纸设置" [preview]
    "生成器设置" [:div
                  [:p "生成器选择"]
                  [:label "line" [:input {:type "checkbox"}]]]
    "自动刷新" [:div
                [:label "启用" [:input {:type "checkbox"}]]
                [:p "时间间隔" [:input]]
                [:div "壁纸设置命令:"
                 [:div "预置:"
                  [:button "GNOME"] [:button "KDE"]
                  [:button "Xfce"] [:button "Cinnamon"]]
                 [:textarea]]]
    "关于" [:div "..."]]])

(defn init []
  (enable-console-print!)
  (r/render body (query-selector "#app")))
