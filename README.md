# servant-demo

A more complex demo of the [Servant Library](https://github.com/MarcoPolo/Servant).
This project spawns several workers, and uses them to parallelize data {en,de}cryption, 
a computationally heavy operation.

## Usage

Run `lein do cljsbuild clean, cljsbuild once` to compile the Clojurescript.  

Then run `lein trampoline cljsbuild repl-listen` to run the webserver.

Go to `localhost:9000` and look at the console.
