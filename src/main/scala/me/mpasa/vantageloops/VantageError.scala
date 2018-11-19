package me.mpasa.vantageloops

sealed trait VantageError

case object InvalidCrc extends VantageError
case object CannotParse extends VantageError
