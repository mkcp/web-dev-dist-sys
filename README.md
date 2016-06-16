# web-dev-dist-sys

This is a presentation client and server for Web Development is Distributed Systems Programming presented at Clojure/West 2016 and Abstractions 2016.

## Overview

This designed for use on a private network, so there are no authentication features. I used my phone's hotspot to connect my laptop, running the server and a client for presenting, and my phone for driving the slides.

The pngs for the slides live in `/resources/public/slides`.

The client and server each have their own sections in src files. The server serves both the slide client behavior through ws channels, as well as providing the client resources with http.

The server broadcasts the slide index to all clients every second. (= tick 1000ms)
Clients are designed to immediately override their local index replica with the server's index.

The server is available to receive and affect index events from clients regardless of when a tick happens. Events are taken in order off of sente's event queue and applied to the server's index.

## Setup

To run with cider:

```
(use 'figwheel-sidecar.repl-api)
(start-figwheel!)
(start!)
```

To get an interactive development environment run:

    lein figwheel

and open your browser at [localhost:3449](http://localhost:3449/).
This will auto compile and send all changes to the browser without the
need to reload. After the compilation process is complete, you will
get a Browser Connected REPL. An easy way to try it is:

    (js/alert "Am I connected?")

and you should see an alert in the browser window.

To clean all compiled files:

    lein clean

To create a production build run:

    lein do clean, cljsbuild once min

And open your browser in `resources/public/index.html`. You will not
get live reloading, nor a REPL. 

## License

Copyright © 2016 Mikaela Patella

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.
