package me.mpasa.vantageloops

import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.SECONDS

import akka.actor.{Actor, ActorLogging, ActorSystem, Props, Terminated}
import akka.io.IO
import akka.serial.{Serial, SerialSettings}
import akka.util.ByteString
import monix.execution.Scheduler.{global => scheduler}

/** An actor that represents a Davis me.mpasa.vantageloops.Vantage
  * Once started, it asks the me.mpasa.vantageloops.Vantage for loops at a fixed rate as specified in the options
  *
  * @param options port, baud rate, loop rate and a callback function
  */
class Vantage(options: VantageOptions)
             (implicit system: ActorSystem) extends Actor with ActorLogging {

  // Maximum time to wait if the me.mpasa.vantageloops.Vantage disconnects
  val MAX_WAIT_SECONDS: Int = 30

  // Number of seconds to increase the timeout after a failure
  // The timeout starts at 0 and increases until [[MAX_WAIT_SECONDS]]
  val SECOND_INCREASE_AFTER_FAILURE: Int = 5

  var attempt: Int = 0

  /** Tries to open the me.mpasa.vantageloops.Vantage after waiting if it failed before */
  private def connect(): Unit = {
    val wait = Math.min(attempt*SECOND_INCREASE_AFTER_FAILURE, MAX_WAIT_SECONDS)
    scheduler.scheduleOnce(wait, SECONDS, () => {
      IO(Serial) ! Serial.Open(options.port, SerialSettings(options.baudRate))
      attempt += 1
    })
  }

  /** Executed after the connection with the me.mpasa.vantageloops.Vantage has been established
    * It tries to open the serial connection with the specified options
    */
  override def preStart(): Unit = {
    log.info(s"Requesting to open me.mpasa.vantageloops.Vantage (port: ${options.port}, baud: ${options.baudRate})")
    connect()
  }

  /** Executed when a message is received from the Serial actor (command has failed or port has been opened) */
  def receive: Receive = {
    case Serial.CommandFailed(_, reason) =>
      log.error(s"Connection failed, stopping vantage. Reason: $reason")
      context.stop(self)

    case Serial.Opened(port) =>
      log.info(s"me.mpasa.vantageloops.Vantage: port $port is now open.")
      val ioActor = sender()
      context.become(openReceive())
      context.watch(ioActor)
      scheduler.scheduleAtFixedRate(initialDelay = 0, options.loopEverySeconds, TimeUnit.SECONDS, () => {
        ioActor ! Serial.Write(ByteString("LOOP 1\n"))
      })
  }

  /** Executed when as message is received listening the serial port */
  def openReceive(): Receive = {
    case Serial.Received(data) =>
      options.callback(Loop.parse(data))

    case Serial.Closed =>
      log.info("me.mpasa.vantageloops.Vantage has been closed. Reconnecting")
      connect()

    case Terminated(_) =>
      log.error("me.mpasa.vantageloops.Vantage crashed unexpectedly")
      connect()
  }
}

object Vantage {
  def apply(settings: VantageOptions)(implicit system: ActorSystem): Props = {
    Props(classOf[Vantage], settings, system)
  }
}

