package me.mpasa.vantageloops

import java.nio.charset.Charset
import java.nio.{ByteBuffer, ByteOrder}

import akka.util.ByteString
import squants.motion.{MillimetersOfMercury, UsMilesPerHour}
import squants.space.Inches
import squants.thermal.Fahrenheit

final case class Loop(
    barometer: Double,
    inTemperature: Double,
    outTemperature: Double,
    windSpeed: Double,
    windDirection: Short, // 0 => No data, 360 => North, 90 => East, 180 => South, 270 => West
    inHumidity: Byte,
    outHumidity: Byte,
    dayRain: Double,
    rainRate: Double,
    forecast: Byte
) {

  override def toString: String = {
    Seq(
      "Barometer" -> barometer,
      "Inside temperature" -> inTemperature,
      "Outside temperature" -> outTemperature,
      "Wind speed" -> windSpeed,
      "Wind direction" -> windDirection,
      "Inside humidity" -> inHumidity,
      "Out humidity" -> outHumidity,
      "Day rain" -> dayRain,
      "Rain rate" -> rainRate,
      "Forecast" -> forecast
    )
      .map { case (key, value) => s"$key: $value" }
      .mkString("\n")
  }
}

object Loop {

  private def isFirstLoop(buffer: ByteBuffer): Boolean = {
    val asString = new String(buffer.array(), Charset.forName("UTF-8"))
    asString.length == 100 && asString.slice(0, 4).trim == "LOO"
  }

  private def isLoop(buffer: ByteBuffer): Boolean = {
    val asString = new String(buffer.array(), Charset.forName("UTF-8"))
    isFirstLoop(buffer) || asString.length == 99 && asString
      .slice(0, 4)
      .trim == "LOO"
  }

  // Protocol: http://www.davisnet.com/support/weather/download/VantageSerialProtocolDocs_v261.pdf
  // Page:
  def parse(data: ByteString): Either[VantageError, Loop] = {
    val buffer: ByteBuffer = data.toByteBuffer
    val arrayBuffer = buffer.array()
    buffer.order(ByteOrder.LITTLE_ENDIAN)

    if (isLoop(buffer)) {
      val m = if (isFirstLoop(buffer)) 1 else 0
      val barometer = MillimetersOfMercury(
        Inches(buffer.getShort(7 + m).toDouble / 1000).toMillimeters
      ).toPascals / 100
      val inTemperature = Fahrenheit(
        buffer.getShort(9 + m).toDouble / 10
      ).toCelsiusScale
      val inHumidity = buffer.get(11 + m)
      val outTemperature = Fahrenheit(
        buffer.getShort(12 + m).toDouble / 10
      ).toCelsiusScale
      val windSpeed = UsMilesPerHour(buffer.get(14 + m)).toKilometersPerHour
      val windDirection = buffer.getShort(16 + m)
      val outHumidity = buffer.get(33 + m)
      val rainRate = buffer.getShort(41 + m) * 0.2
      val dayRain = buffer.getShort(50 + m) * 0.2
      val forecast = buffer.get(89 + m)

      // CRC is written in big endian order
      val loop = Loop(
        barometer,
        inTemperature,
        outTemperature,
        windSpeed,
        windDirection,
        inHumidity,
        outHumidity,
        dayRain,
        rainRate,
        forecast
      )
      val crc: Array[Byte] = Array(buffer.get(97 + m), buffer.get(98 + m))
      val calculatedCrc = CRC16.calculate(arrayBuffer.slice(0 + m, 97 + m))
      if (crc.deep == calculatedCrc.bytesCrc.deep) {
        Right(loop)
      } else {
        Left(InvalidCrc)
      }
    } else {
      Left(CannotParse)
    }
  }
}

