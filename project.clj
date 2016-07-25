(defproject web-dev-dist-sys "0.2.0"
  :description "Slides application for Web Dev is Dist Sys."
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :min-lein-version "2.6.1"

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.7.228"]
                 [org.clojure/core.async "0.2.374"
                  :exclusions [org.clojure/tools.reader]]
                 [org.clojure/test.check "0.9.0"]
                 [com.taoensso/sente "1.8.1"]
                 [com.taoensso/timbre "4.3.1"]
                 [com.stuartsierra/component "0.3.1"]
                 [http-kit "2.2.0-alpha1"]
                 [ring "1.4.0"]
                 [ring/ring-defaults "0.2.0"]
                 [compojure "1.5.0"]
                 [hiccup "1.0.5"]
                 [reagent "0.5.1"]
                 [prismatic/schema "1.1.3"]]

  :plugins [[lein-figwheel "0.5.2"]
            [lein-cljsbuild "1.1.3" :exclusions [[org.clojure/clojure]]]]

  :profiles {:dev {:dependencies [[com.cemerick/piggieback "0.2.1"]
                                  [org.clojure/tools.nrepl "0.2.10"]
                                  [figwheel-sidecar "0.5.2"]]
                   :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}}}

  :source-paths ["src"]

  :clean-targets ^{:protect false} ["resources/public/js/compiled" "target"]

  :main web-dev-dist-sys.server

  :cljsbuild {:builds
              [{:id "dev"
                :source-paths ["src"]

                :figwheel {:on-jsload "web-dev-dist-sys.client/on-js-reload"}

                :compiler {:main web-dev-dist-sys.client
                           :asset-path "js/compiled/out"
                           :output-to "resources/public/js/compiled/web_dev_dist_sys.js"
                           :output-dir "resources/public/js/compiled/out"
                           :source-map-timestamp true}}
               {:id "min"
                :source-paths ["src"]
                :compiler {:output-to "resources/public/js/compiled/web_dev_dist_sys.js"
                           :main web-dev-dist-sys.client
                           :optimizations :advanced
                           :pretty-print false}}]}

  :figwheel {:css-dirs ["resources/public/css"]})
