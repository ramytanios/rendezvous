package backend

import cats.syntax.all.*
import cats.effect.IO
import cats.effect.kernel.Resource
import fs2.concurrent.SignallingRef

trait Engine:

  def activeNodes: IO[Seq[Node]]

  def createNode: IO[Node]

  def removeNode(id: String): IO[Unit]

  def addInstrument(ins: Instrument): IO[Unit]

object Engine:

  def apply(): Resource[IO, Engine] =
    for
      hash <- IO.unit.as(Hash.mmh3()).toResource
      nodesRef <- SignallingRef.of[IO, Map[String, Node]](Map.empty).toResource
    yield new Engine:

      def activeNodes: IO[Seq[Node]] = nodesRef.get.map(_.values.toSeq)

      def addInstrument(ins: Instrument): IO[Unit] =
        nodesRef.get.flatMap: nodes =>
          nodes.keys
            .map(nodeId => nodeId -> hash.hash(s"${ins.id}${nodeId}"))
            .maxByOption(_(1))
            .foldMapM: (nodeId, _) =>
              nodes.get(nodeId).foldMapM: node =>
                node.add(ins, _ => IO.unit)

      def createNode: IO[Node] = ???

      def removeNode(id: String): IO[Unit] =
        nodesRef.get.flatMap: nodes =>
          nodes.get(id).foldMapM: node =>
            nodesRef.update(_ - id) *>
              fs2.Stream
                .evalSeq(node.active)
                .parEvalMapUnbounded(addInstrument)
                .compile
                .drain
