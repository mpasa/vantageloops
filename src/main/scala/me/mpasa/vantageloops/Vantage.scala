package me.mpasa.vantageloops

import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.SECONDS

import akka.actor.{Actor, ActorLogging, ActorSystem, Props, Terminated}
import akka.io.IO
import akka.serial.{Serial, SerialSettings}
import akka.util.ByteString
import monix.execution.Scheduler.{global => scheduler}

/** An actor that represents a Davis me.mpasa.vantageloops.Vantage Once started,
  * it asks the me.mpasa.vantageloops.Vantage for loops at a fixed rate as
  * specified in the options
  *
  * @param options
  *   port, baud rate, loop rate and a callback function
  */
class Vantage(options: VantageOptions)(implicit system: ActorSystem)
    extends Actor
    with ActorLogging {

  // Maximum time to wait if the me.mpasa.vantageloops.Vantage disconnects
  val MAX_WAIT_SECONDS: Int = 30

  // Number of seconds to increase the timeout after a failure
  // The timeout starts at 0 and increases until [[MAX_WAIT_SECONDS]]
  val SECOND_INCREASE_AFTER_FAILURE: Int = 5

  var attempt: Int = 0
  private var rxBuffer: ByteString = ByteString.empty

  /** Tries to open the me.mpasa.vantageloops.Vantage after waiting if it failed
    * before
    */
  private def connect(): Unit = {
    val wait =
      Math.min(attempt * SECOND_INCREASE_AFTER_FAILURE, MAX_WAIT_SECONDS)
    scheduler.scheduleOnce(
      wait,
      SECONDS,
      () => {
        IO(Serial) ! Serial.Open(options.port, SerialSettings(options.baudRate))
        attempt += 1
      }
    )
  }

  /** Executed after the connection with the me.mpasa.vantageloops.Vantage has
    * been established It tries to open the serial connection with the specified
    * options
    */
  override def preStart(): Unit = {
    log.info(
      s"Requesting to open me.mpasa.vantageloops.Vantage (port: ${options.port}, baud: ${options.baudRate})"
    )
    connect()
  }

  /** Executed when a message is received from the Serial actor (command has
    * failed or port has been opened)
    */
  def receive: Receive = {
    case Serial.CommandFailed(_, reason) =>
      log.error(s"Connection failed, stopping vantage. Reason: $reason")
      context.stop(self)

    case Serial.Opened(port) =>
      log.info(s"me.mpasa.vantageloops.Vantage: port $port is now open.")
      val ioActor = sender()
      context.become(openReceive())
      context.watch(ioActor)
      scheduler.scheduleAtFixedRate(
        initialDelay = 0,
        options.loopEverySeconds,
        TimeUnit.SECONDS,
        () => {
          ioActor ! Serial.Write(ByteString("LOOP 1\n"))
        }
      )
  }

  /** Executed when as message is received listening the serial port */
  def openReceive(): Receive = {
    case Serial.Received(data) =>
      rxBuffer = rxBuffer ++ data
      var keepParsing = true
      while (keepParsing) {
        val (frameOpt, rest) = nextFrame(rxBuffer)
        frameOpt match {
          case Some(frame) =>
            options.callback(Loop.parse(frame))
            rxBuffer = rest
          case None =>
            rxBuffer = rest
            keepParsing = false
        }
      }

    case Serial.Closed =>
      log.info("me.mpasa.vantageloops.Vantage has been closed. Reconnecting")
      connect()

    case Terminated(_) =>
      log.error("me.mpasa.vantageloops.Vantage crashed unexpectedly")
      connect()
  }

  private def nextFrame(
      buffer: ByteString
  ): (Option[ByteString], ByteString) = {
    // Extract a single LOOP frame if present; otherwise return (None, bufferedRemainder).
    if (buffer.length < 99) {
      return (None, buffer)
    }

    val looIndex = findLOO(buffer)
    if (looIndex == -1) {
      // Keep the last two bytes in case "LOO" starts across a boundary.
      val keep = if (buffer.length > 2) buffer.takeRight(2) else buffer
      return (None, keep)
    }

    // First LOOP can be 100 bytes with "LOO" at offset 1
    if (looIndex == 1 && buffer.length >= 100) {
      val frame = buffer.take(100)
      return (Some(frame), buffer.drop(100))
    }

    // Normal LOOP is 99 bytes with "LOO" at offset 0
    if (buffer.length - looIndex >= 99) {
      val frame = buffer.drop(looIndex).take(99)
      val rest = buffer.drop(looIndex + 99)
      return (Some(frame), rest)
    }

    // If "LOO" isn't at a valid boundary yet, keep from it and wait for more data
    val rest = buffer.drop(looIndex)
    (None, rest)
  }

  private def findLOO(buffer: ByteString): Int = {
    var i = 0
    val end = buffer.length - 2
    while (i < end) {
      if (buffer(i) == 'L' && buffer(i + 1) == 'O' && buffer(i + 2) == 'O') {
        return i
      }
      i += 1
    }
    -1
  }
}

object Vantage {
  def apply(settings: VantageOptions)(implicit system: ActorSystem): Props = {
    Props(classOf[Vantage], settings, system)
  }
}
