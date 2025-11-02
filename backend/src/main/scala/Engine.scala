package backend

import cats.effect.IO

trait Engine:

  def activeNodes: IO[Seq[Node]]

  def createNode: IO[Node]

  def removeNode(id: String): IO[Node]

  def addInstrument(ins: Instrument): IO[Node]
