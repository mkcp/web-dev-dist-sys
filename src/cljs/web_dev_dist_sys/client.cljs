(ns web-dev-dist-sys.client
  (:require [clojure.string :as str]
            [goog.events :as events]
            [goog.events.KeyCodes :as KeyCodes]
            [taoensso.encore :as encore]
            [taoensso.timbre :as timbre :refer-macros [tracef debugf infof warnf errorf]]
            [taoensso.sente  :as sente  :refer [cb-success?]]
            [reagent.core :as r :refer [atom]]))


;; TODO Add a socket-closed state.
(defn now [] (.getTime (js/Date.)))

;; Dev tooling
(enable-console-print!)
(timbre/debugf "Client is running at %s" (now))

;; Database, init with some scratch vals.
(defonce db (atom {:index 0
                   :max 0}))

;; Channel socket setup
(defn make-chsk-client
  "Creates a socket connection with server at /chsk"
  []
  (sente/make-channel-socket-client! "/chsk"
                                     {:type :auto
                                      :packer :edn}))

;; Defines the client's channel and defines vars for various properties
(defonce state_
  (let [{:keys [chsk ch-recv send-fn state]} (make-chsk-client)]

    (def chsk       chsk)
    (def ch-chsk    ch-recv) ; ChannelSocket's receive channel
    (def chsk-send! send-fn) ; ChannelSocket's send API fn
    (def chsk-state state)   ; Watchable, read-only atom
    ))

(defn update-db
  [state {:keys [index max] :as new-state}]
  (let [index (or index 0)
        max (or max 1)]
    (assoc state
           :index index
           :max max)))

;;;; Sente event handlers
(defmulti -event-msg-handler :id)

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [id ?data event]}]
  (timbre/debugf "%s" event)
  (-event-msg-handler ev-msg))

(defmethod -event-msg-handler :chsk/state
  [{:as ev-msg :keys [?data]}]
  (if (= ?data {:first-open? true})
    (timbre/debugf "Channel socket successfully established!")))

(defmethod -event-msg-handler :chsk/handshake
  [{:as ev-msg :keys [?data]}]
  ;; TODO Get the db state on client startup.
  )

(defmethod -event-msg-handler :chsk/recv
  [{:as ev-msg :keys [event ?data]}]
  (let [[id body] ?data]
    (case id
      :srv/sync (swap! db update-db body)
      :srv/push (swap! db update-db body))))

(defmethod -event-msg-handler
  :default ; Default/fallback case (no other matching handler)
  [{:as ev-msg :keys [event]}]
  (timbre/debugf "Unhandled event: %s" event))


(defonce router_ (atom nil))

(defn stop-router! []
  (when-let [stop-f @router_] (stop-f)))

(defn start-router! []
  (stop-router!)
  (reset! router_
          (sente/start-client-chsk-router! ch-chsk event-msg-handler)))

(defn send-event
  "Wraps event submissions with logging."
  ([id]
   (send-event id {}))
  ([id body]
   (chsk-send! [id body])
   (timbre/debugf "[%s %s] event sent" id body)))

(defn slide-prev
  "Send next event to server. Either commit change locally on correct response, or sync w/ heartbeat."
  []
  (let [{:keys [index]} @db]
    (if-not (zero? index)
      (send-event :cli/prev {:index index :send-time (now)}))))

(defn slide-next
  "Send next event to server. Either commit change locally on correct response, or sync w/ heartbeat."
  []
  (let [{:keys [index max]} @db]
    (if-not (>= index max)
      (send-event :cli/next {:index index :send-time (now)}))))

;; Input handling
(def input-prev #{40 37})
(def input-next #{38 39})

(defn handle-keyboard
  "Maps keyCode propery to correct handler."
  [e]
  (let [key-code (.-keyCode e)]
    (cond
      (input-prev key-code) (slide-prev)
      (input-next key-code) (slide-next))))

(defn listen-keyboard []
  (events/listen js/window "keydown" handle-keyboard))

(defn start! [] (start-router!))

;; Run these functions once on startup.
(defonce _start-once
  (do
    (listen-keyboard)
    (start!)))

(defn get-slide []
  (let [prefix "slides/web-dev-dist-sys"
        index (:index @db)
        extension ".png"]
    (str prefix index extension)))

(defn layout []
  [:div.app-container
   [:div.click-container
    [:div.click-left {:on-click slide-prev}]
    [:div.click-right {:on-click slide-next}]]
   [:div.slide-container
    [:img#slide.noselect {:src (get-slide)}]]])

(r/render-component [layout]
                    (.getElementById js/document "app"))
