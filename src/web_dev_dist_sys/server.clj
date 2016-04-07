(ns web-dev-dist-sys.server
  (:require
   [clojure.string     :as str]
   [ring.middleware.defaults]
   [compojure.core     :as comp :refer [defroutes GET POST]]
   [compojure.route    :as route]
   [hiccup.core        :as hiccup]
   [clojure.core.async :as async  :refer [<! <!! >! >!! put! chan go go-loop]]
   [taoensso.encore    :as encore]
   [taoensso.timbre    :as timbre :refer [tracef debugf infof warnf errorf]]
   [taoensso.sente     :as sente]
   [org.httpkit.server :as http]
   [taoensso.sente.server-adapters.http-kit :refer [sente-web-server-adapter]]))

(defn start-selected-web-server!
  [ring-handler port]
  (infof "Starting http-kit...")
  (let [stop-fn (http/run-server ring-handler {:port port})]
    {:server  nil ; http-kit doesn't expose this
     :port    (:local-port (meta stop-fn))
     :stop-fn (fn [] (stop-fn :timeout 100))}))

(defn make-channel-socket-server []
  (sente/make-channel-socket-server! sente-web-server-adapter
                                     {:packer :edn}))

;; Make channel-socket-server then extract its to assign to global vars
(let [chsk-server (make-channel-socket-server)
      {:keys [ch-recv
              send-fn
              ajax-post-fn
              ajax-get-or-ws-handshake-fn
              connected-uids]} chsk-server]

  (def ring-ajax-post                ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk                       ch-recv) ; ChannelSocket's receive channel for the chsk router
  (def chsk-send!                    send-fn) ; ChannelSocket's send API fn
  (def connected-uids                connected-uids) ; Watchable, read-only atom
  )

(def index (atom 1))

(defn landing-pg-handler [ring-req]
  (hiccup/html
   [:div#app] ;; Figwheel mount point
   [:script {:src "js/compiled/web_dev_dist_sys.js"}] ; Include our cljs target
   ))

(defroutes ring-routes
  (GET  "/"      ring-req (landing-pg-handler            ring-req))
  (GET  "/chsk"  ring-req (ring-ajax-get-or-ws-handshake ring-req))
  (POST "/chsk"  ring-req (ring-ajax-post                ring-req))
  (route/resources "/") ; Static files, notably public/main.js (our cljs target)
  (route/not-found "<h1>Page not found</h1>"))

(def main-ring-handler
  "**NB**: Sente requires the Ring `wrap-params` + `wrap-keyword-params`
  middleware to work. These are included with
  `ring.middleware.defaults/wrap-defaults` - but you'll need to ensure
  that they're included yourself if you're not using `wrap-defaults`."
  (ring.middleware.defaults/wrap-defaults
    ring-routes ring.middleware.defaults/site-defaults))

;; Event handlers
(defmulti -event-msg-handler
  "Multimethod to handle Sente `event-msg`s"
  :id ; Dispatch on event-id
  )

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [id event]}]
  (-event-msg-handler ev-msg))

(defmethod -event-msg-handler
  :cli/next
  [{:as ev-msg :keys [event id ring-req]}]
  (let [session (:session ring-req)
        uid (:uid session)]
    (do
      (debugf "Next: %s event from %s" event uid)
      (swap! index inc))))

(defmethod -event-msg-handler
  :cli/prev
  [{:as ev-msg :keys [event id ring-req]}]
  (let [session (:session ring-req)
        uid (:uid session)]
    (do
      (debugf "Next: %s event from %s" event uid)
      (swap! index dec))))

(defmethod -event-msg-handler
  :default ; Default/fallback case (no other matching handler)
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)]
    (debugf "Unhandled event: %s" event)
    (when ?reply-fn
      (?reply-fn {:umatched-event-as-echoed-from-from-server event}))))

;; Channel socket router
(defonce router_ (atom nil))

(defn stop-router! []
  (when-let [stop-f @router_] (stop-f)))

(defn start-router!
  "Ensures the router isn't running before assigning a new channel socket router to the `router_` atom."
  []
  (stop-router!)
  (reset! router_
          (sente/start-server-chsk-router! ch-chsk
                                           event-msg-handler)))

(defn send-broadcast
  "Broadcasts index to to any connected clients."
  [ticks]
  (debugf "Broadcasting server>user: %s" @connected-uids)
  (doseq [uid (:any @connected-uids)]
    (chsk-send! uid
                [:srv/sync
                 {:index @index
                  :how-often "Every second"
                  :to-whom uid
                  :ticks ticks}])))

(defn start-broadcaster!
  "Starts the loop to send out state broadcasts."
  []
  (go-loop [i 0]
    (<! (async/timeout 1000))
    (send-broadcast i)
    (recur (inc i))))

;; Web Server
(defonce web-server_ (atom nil)) ; {:server _ :port _ :stop-fn (fn [])}

(defn stop-web-server! []
  (when-let [m @web-server_]
    ((:stop-fn m))))

(defn start-web-server! [& [port]]
  (stop-web-server!)
  (let [{:keys [stop-fn port] :as server-map}
        (start-selected-web-server! (var main-ring-handler)
                                    (or port 0) ; 0 => auto (any available) port
                                    )
        uri (format "http://localhost:%s/" port)]
    (infof "Web server is running at `%s`" uri)
    (try
      (.browse (java.awt.Desktop/getDesktop) (java.net.URI. uri))
      (catch java.awt.HeadlessException _))
    (reset! web-server_ server-map)))

(defn stop! []
  (stop-router!)
  (stop-web-server!))

(defn start!
  "Starts the router to dispatch for events
  Starts the web server to do work w/ clients.
  And starts the broadcaster to sync index w/ clients."
  []
  (start-router!)
  (start-web-server! 10002)
  (start-broadcaster!))

(defn -main "For `lein run`, etc." [] (start!))
