(ns web-dev-dist-sys.client
  (:require [clojure.string :as str]
            [taoensso.encore :as encore]
            [taoensso.timbre :as timbre :refer-macros [tracef debugf infof warnf errorf]]
            [taoensso.sente  :as sente  :refer [cb-success?]]
            [reagent.core :as r :refer [atom]]))

(enable-console-print!)

(timbre/debugf "Client is running at %s" (.getTime (js/Date.)))

(defonce app-state (atom {:text "Hello world!"
                          :index 0}))

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

(defn sync [state index]
  (assoc state :index (:index index)))

;;;; Sente event handlers
(defmulti -event-msg-handler :id)

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [id ?data event]}]
  (-event-msg-handler ev-msg))

(defmethod -event-msg-handler
  :default ; Default/fallback case (no other matching handler)
  [{:as ev-msg :keys [event]}]
  (timbre/debugf "Unhandled event: %s" event))

(defmethod -event-msg-handler :chsk/state
  [{:as ev-msg :keys [?data]}]
  (if (= ?data {:first-open? true})
    (timbre/debugf "Channel socket successfully established!")
    (timbre/debugf "Channel socket state change: %s" ?data)))

(defmethod -event-msg-handler :chsk/recv
  [{:as ev-msg :keys [?data]}]
  (let [event (first ?data)
        index (second ?data)]
    (case event
      :srv/sync (do
                  (swap! app-state #(sync % index))
                  (timbre/debugf "App state is now: %s" @app-state)))))

(defmethod -event-msg-handler :chsk/handshake
  [{:as ev-msg :keys [?data]}]
  (let [[?uid ?csrf-token ?handshake-data] ?data]
    (timbre/debugf "Handshake: %s" ?data)))

(defonce router_ (atom nil))

(defn stop-router! []
  (when-let [stop-f @router_] (stop-f)))

(defn start-router! []
  (stop-router!)
  (reset! router_
          (sente/start-client-chsk-router!
           ch-chsk event-msg-handler)))

(defn login []
  (let [user-id "kit"]
    (do
      (timbre/debugf "Logging in with user-id %s" user-id)

            ;;; Use any login procedure you'd like. Here we'll trigger an Ajax
            ;;; POST request that resets our server-side session. Then we ask
            ;;; our channel socket to reconnect, thereby picking up the new
            ;;; session.
      (sente/ajax-lite "/login"
                       {:method :post
                        :headers {:X-CSRF-Token (:csrf-token @chsk-state)}
                        :params  {:user-id (str user-id)}}

                       (fn [ajax-resp]
                         (timbre/debugf "Ajax login response: %s" ajax-resp)
                         (do
                           (timbre/debugf "Login successful")
                           (sente/chsk-reconnect! chsk)))))))

(defn slide-prev
  "Send next event to server. Either commit change locally on correct response, or sync w/ heartbeat."
  []
  (do
    (chsk-send! [:cli/prev])
    (timbre/debugf "Prev event sent")
    ))

(defn slide-next
  "Send next event to server. Either commit change locally on correct response, or sync w/ heartbeat."
  []
  (do
    (chsk-send! [:cli/next])
    (timbre/debugf "Next event sent")))

(defn start! [] (start-router!))

(defonce _start-once (start!))

(defn hello-world []
  [:div
   [:img {:src "slides/title-card.png"}]
   [:button {:on-click slide-prev} "Prev"]
   [:button {:on-click slide-next} "Next"]
   ])

(r/render-component [hello-world]
                    (. js/document (getElementById "app")))

(defn on-js-reload []
  ;; optionally touch your app-state to force rerendering depending on
  ;; your application
  ;; (swap! app-state update-in [:__figwheel_counter] inc)
  )
