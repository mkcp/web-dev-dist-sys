# web-dev-dist-sys

This is a presentation client and server for Web Development is Distributed Systems Programming given at Clojure/West 2016.

## Overview

This is meant to be used on a private network. There are no security features of any kind.

Slides live in /resources/public/slides

Client and server are simple enough to each have their own single source file.

The mechanisms for keeping clients in sync are: 

#### Watcher
When a client submits a prev/next event, the server's index gets updated. After a transaction completes on the server's index, a watcher on the server's atom fires a function to broadcast the new index value to all clients.

#### Heartbeat
Networks are unreliable and drop messages, so the simplest way to correct clients' index drifting from missed watch broadcasts is for the server to broadcast the slide index to all clients each second. (= tick 1000ms)
Clients update their local slide index based on this heartbeat, and if there is no difference there is no change. This prevents corrects drift from dropped watch updates.

## Setup

Run from cider:

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
