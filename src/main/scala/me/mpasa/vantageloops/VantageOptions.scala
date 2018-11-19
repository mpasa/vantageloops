package me.mpasa.vantageloops

case class VantageOptions(port: String, baudRate: Int, loopEverySeconds: Int, callback: Either[VantageError, Loop] => Unit)
