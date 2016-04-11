(ns web-dev-dist-sys.client
  (:require [clojure.string :as str]
            [goog.events :as events]
            [goog.events.KeyCodes :as KeyCodes]
            [taoensso.encore :as encore]
            [taoensso.timbre :as timbre :refer-macros [tracef debugf infof warnf errorf]]
            [taoensso.sente  :as sente  :refer [cb-success?]]
            [reagent.core :as r :refer [atom]]))

(enable-console-print!)

(timbre/debugf "Client is running at %s" (.getTime (js/Date.)))

(def input-next #{KeyCodes/UP KeyCodes/RIGHT})
(def input-prev #{KeyCodes/DOWN KeyCodes/LEFT})

(def map-controls {input-next :next
                   input-prev :prev})

(defn handle-keyboard [e]
  (pr-str (.keyCode e)))

(defn listen-keyboard []
  (events/listen js/window "keydown" handle-keyboard))

(defonce app-state (atom {:index 0
                          :count 0}))

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

(defn update-index
  "Assocs the index that the server provided so long as it's not nil."
  [state {:keys [index]}]
  (do
    (if index
      (assoc state :index index))))

(defn handle-sync [state]
  (do
    (timbre/debugf "[:srv/sync] event received: %s" state)
    (swap! app-state update-index state)))

(defn handle-push [state]
  (do
    (timbre/debugf "[:srv/push] event received: %s" state)
    (swap! app-state update-index state)))

;;;; Sente event handlers
(defmulti -event-msg-handler :id)

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [id ?data event]}]
  (-event-msg-handler ev-msg))

(defmethod -event-msg-handler :chsk/state
  [{:as ev-msg :keys [?data]}]
  (if (= ?data {:first-open? true})
    (timbre/debugf "Channel socket successfully established!")
    (timbre/debugf "Channel socket state change: %s" ?data)))

(defmethod -event-msg-handler :chsk/handshake
  [{:as ev-msg :keys [?data]}]
  (let [[?uid ?csrf-token ?handshake-data] ?data]
    (timbre/debugf "Handshake: %s" ?data)))

(defmethod -event-msg-handler :chsk/recv
  [{:as ev-msg :keys [?data]}]
  (let [id (first ?data)
        body (second ?data)]
    (case id
      :srv/sync (handle-sync body)
      :srv/push (handle-push body))))

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
   (do
     (chsk-send! [id])
     (timbre/debugf "[%s] event sent" id)))
  ([id body]
   (do
     (chsk-send! [id body])
     (timbre/debugf "[%s %s] event sent" id body))))

(defn slide-prev
  "Send next event to server. Either commit change locally on correct response, or sync w/ heartbeat."
  []
  (if-not (zero? (:index @app-state))
    (send-event :cli/prev)))

(defn slide-next
  "Send next event to server. Either commit change locally on correct response, or sync w/ heartbeat."
  []
  (send-event :cli/next)

  #_(let [{:keys [count index]} @app-state]
    (if-not (= count index)
      (do
        (chsk-send! [:cli/next])
        (timbre/debugf ":cli/next event sent")))))

(defn start! [] (start-router!))

(defonce _start-once
  (do
    (listen-keyboard)
    (start!)))

(defn get-slide []
  (let [index (:index @app-state)]
    (str "slides/web-dev-dist-sys" index ".png")))

(defn main []
  [:div
   [:div.slide-container
    [:img#slide {:src (get-slide)}]]
   [:div.nav
    [:button {:on-click slide-prev} "Prev"]
    [:button {:on-click slide-next} "Next"]]])

(r/render-component [main]
                    (. js/document (getElementById "app")))

(defn on-js-reload []
  ;; optionally touch your app-state to force rerendering depending on
  ;; your application
  ;; (swap! app-state update-in [:__figwheel_counter] inc)
  )
