(defproject ca.orchard-labs/proprio "1.2.0"
  :description "A library for interacting with AWS Kinesis"
  :url "http://github.com/orchard-labs/proprio"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/tools.logging "0.4.0"]
                 [com.amazonaws/aws-java-sdk "1.11.289" :exclusions [joda-time]]
                 [com.amazonaws/aws-java-sdk-dynamodb "1.11.289"]
                 [com.amazonaws/amazon-kinesis-client "1.8.10" :exclusions [joda-time]]
                 [cheshire "5.8.0"] ;; json
                 ;; inline assertions, gradual typing, kinda
                 [com.taoensso/truss "1.5.0"]
                 ;; small utilities
                 [medley "1.0.0"]
                 [ca.orchard-labs/morph "1.0.1"]]

  :repl-options {:init-ns user
                 :color false}

  :eftest {:multithread?    false
           :capture-output? true
           :report          eftest.report.progress/report}

  :profiles {:dev {:source-paths   ["src" "dev/src"]
                   :test-paths     ["test"]
                   :resource-paths ["resources" "dev/resources"]

                   :dependencies [[circleci/bond "0.3.1"]
                                  [eftest "0.5.0"]
                                  [org.clojure/test.check "0.10.0-alpha2"]
                                  [com.gfredericks/test.chuck "0.2.8"]
                                  [viebel/codox-klipse-theme "0.0.5"]
                                  [environ "1.1.0"]
                                  ;; so apache commons logging (amazon) gets
                                  ;; forwarded to logback
                                  [org.slf4j/jcl-over-slf4j "1.7.25"]
                                  [ch.qos.logback/logback-classic "1.2.3"]]

                   :plugins [[test2junit "1.3.3"]
                             [lein-eftest "0.5.0"]
                             [lein-codox "0.10.3"]]

                   :codox {:metadata {:doc/format :markdown}}

                   :test2junit-output-dir "target/test2junit"
                   :test2junit-run-ant    true

                   :jvm-opts ["-XX:-OmitStackTraceInFastThrow"
                              "-Dcom.amazonaws.sdk.disableCbor=true"]}}

  :repositories [["snapshots" {:url "https://clojars.org/repo"
                               :username "j0ni"
                               :password :env}]
                 ["releases" {:url "https://clojars.org/repo"
                              :creds :gpg}]]

  :deploy-repositories [["releases" :clojars]])
