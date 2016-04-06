(ns web-dev-dist-sys.client-test
  (:require [web-dev-dist-sys.client :as c]
            [cljs.test :refer-macros [deftest is testing run-tests]]))

(deftest index
  (testing "Client can move to next slide")
  (testing "Client can move to previous slide")
  (testing "Client slide syncs with server"))
