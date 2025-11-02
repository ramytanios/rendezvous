package backend

import cats.effect.IO

trait Engine:

  def addNode(node: Node): IO[Unit]

  def removeNode(id: String): IO[Node]
