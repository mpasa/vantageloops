# vantageloops
**vantageloops** is a dead simple Davis Vantage driver implemented in Scala.


## How to use
```scala
val system: ActorSystem = ActorSystem("AS")

def processLoop(loop: Either[VantageError, Loop]): Unit = {
    loop.foreach(println)
}

val options = VantageOptions("/dev/ttyUSB0", 19200, 3, processLoop)
system.actorOf(Vantage(options))
```