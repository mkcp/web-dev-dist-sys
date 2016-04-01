(ns web-dev-dist-sys.client
  (:require [clojure.string :as str]
            [taoensso.encore :as encore]
            [taoensso.timbre :as timbre :refer-macros (tracef debugf infof warnf errorf)]
            [taoensso.sente  :as sente  :refer (cb-success?)]
            [reagent.core :as r :refer [atom]]))

;; Debug
(comment
  (enable-console-print!)

  (println "Edits to this text should show up in your developer console."))

(defonce app-state (atom {:text "Hello world!"}))

(comment
  (def output-el (.getElementById js/document "output")))

(defn ->output! [fmt & args]
  (let [msg (apply encore/format fmt args)]
    (timbre/debug msg)
    (comment
      (aset output-el "value" (str "• " (.-value output-el) "\n" msg))
      (aset output-el "scrollTop" (.-scrollHeight output-el)))
    ))

(->output! "ClojureScript appears to have loaded correctly.")

;; State setup on client
(defonce state_
  (let [;; For this example, select a random protocol:

        ;; Serializtion format, must use same val for client + server:
        ;; (sente-transit/get-flexi-packer :edn) ; Experimental, needs Transit dep

        {:keys [chsk ch-recv send-fn state]}
        (sente/make-channel-socket-client!
         "/chsk" ; Must match server Ring routing URL
         {:type   :auto
          :packer :edn})]

    (def chsk       chsk)
    (def ch-chsk    ch-recv) ; ChannelSocket's receive channel
    (def chsk-send! send-fn) ; ChannelSocket's send API fn
    (def chsk-state state)   ; Watchable, read-only atom
    ))

;;;; Sente event handlers
(defmulti -event-msg-handler
  "Multimethod to handle Sente `event-msg`s"
  :id ; Dispatch on event-id
  )

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [id ?data event]}]
  (-event-msg-handler ev-msg))

(defmethod -event-msg-handler
  :default ; Default/fallback case (no other matching handler)
  [{:as ev-msg :keys [event]}]
  (->output! "Unhandled event: %s" event))

(defmethod -event-msg-handler :chsk/state
  [{:as ev-msg :keys [?data]}]
  (if (= ?data {:first-open? true})
    (->output! "Channel socket successfully established!")
    (->output! "Channel socket state change: %s" ?data)))

(defmethod -event-msg-handler :chsk/recv
  [{:as ev-msg :keys [?data]}]
  (->output! "Push event from server: %s" ?data))

(defmethod -event-msg-handler :chsk/handshake
  [{:as ev-msg :keys [?data]}]
  (let [[?uid ?csrf-token ?handshake-data] ?data]
    (->output! "Handshake: %s" ?data)))

(defonce router_ (atom nil))

(defn stop-router! []
  (when-let [stop-f @router_] (stop-f)))

(defn start-router! []
  (stop-router!)
  (reset! router_
          (sente/start-client-chsk-router!
           ch-chsk event-msg-handler)))


(comment
  "GROSS EVENT HANDLING LOL"
  (when-let [target-el (.getElementById js/document "btn1")]
    (.addEventListener target-el "click"
                       (fn [ev]
                         (->output! "Button 1 was clicked (won't receive any reply from server)")
                         (chsk-send! [:example/button1 {:had-a-callback? "nope"}]))))

  (when-let [target-el (.getElementById js/document "btn2")]
    (.addEventListener target-el "click"
                       (fn [ev]
                         (->output! "Button 2 was clicked (will receive reply from server)")
                         (chsk-send! [:example/button2 {:had-a-callback? "indeed"}] 5000
                                     (fn [cb-reply] (->output! "Callback reply: %s" cb-reply))))))

  (when-let [target-el (.getElementById js/document "btn-login")]
    (.addEventListener target-el "click"
                       (fn [ev]
                         (let [user-id (.-value (.getElementById js/document "input-login"))]
                           (if (str/blank? user-id)
                             (js/alert "Please enter a user-id first")
                             (do
                               (->output! "Logging in with user-id %s" user-id)

            ;;; Use any login procedure you'd like. Here we'll trigger an Ajax
            ;;; POST request that resets our server-side session. Then we ask
            ;;; our channel socket to reconnect, thereby picking up the new
            ;;; session.

                               (sente/ajax-lite "/login"
                                                {:method :post
                                                 :headers {:X-CSRF-Token (:csrf-token @chsk-state)}
                                                 :params  {:user-id (str user-id)}}

                                                (fn [ajax-resp]
                                                  (->output! "Ajax login response: %s" ajax-resp)
                                                  (let [login-successful? true ; Your logic here
                                                        ]
                                                    (if-not login-successful?
                                                      (->output! "Login failed")
                                                      (do
                                                        (->output! "Login successful")
                                                        (sente/chsk-reconnect! chsk)))))))))))))

(defn ping-server []
  (chsk-send! [:cli/meowmix {:test "pass"}]))

(defn start! [] (start-router!))

(defonce _start-once (start!))

(defn hello-world []
  [:div
   [:h1 (:text @app-state)]
   [:h2 "hache too"]
   [:button {:on-click ping-server} "Ping Server"]
   [:img {:src "slides/title-card.png"}]
   ])

(r/render-component [hello-world]
                    (. js/document (getElementById "app")))

(defn on-js-reload []
  ;; optionally touch your app-state to force rerendering depending on
  ;; your application
  ;; (swap! app-state update-in [:__figwheel_counter] inc)
  )
