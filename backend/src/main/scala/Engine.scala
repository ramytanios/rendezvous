package backend

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.syntax.all.*
import fs2.concurrent.SignallingRef

trait Engine:

  def activeNodes: IO[Seq[Node]]

  def createNode: IO[Unit]

  def removeNode(id: String): IO[Unit]

  def addInstrument(ins: Instrument): IO[Unit]

object Engine:

  def apply(): Resource[IO, Engine] =
    for
      hash <- IO.unit.as(Hash.mmh3()).toResource
      nodesRef <- SignallingRef.of[IO, Map[String, Node]](Map.empty).toResource
      nodeScoreByIns <- Ref.of[IO, Map[String, List[String]]](Map.empty).toResource
    yield new Engine:

      def activeNodes: IO[Seq[Node]] = nodesRef.get.map(_.values.toSeq)

      def addImpl(ins: Instrument): IO[Unit] =
        nodesRef.get.flatMap: nodes =>
          nodeScoreByIns.get.flatMap: scores =>
            scores.get(ins.id).foldMapM: sortedNodes =>
              sortedNodes.lastOption.foldMapM: nodeId =>
                nodes.get(nodeId).foldMapM: node =>
                  node.add(ins, _ => IO.unit)

      def addInstrument(ins: Instrument): IO[Unit] =
        nodesRef.get.flatMap: nodes =>
          val scores = nodes.keys.toList
            .map(nodeId => nodeId -> hash.hash(s"${ins.id}${nodeId}"))
            .sortBy(_(1))
          nodeScoreByIns
            .update(_ + (ins.id -> scores.map(_(0))))
            .flatMap(_ => addImpl(ins))

      def createNode: IO[Unit] =
        Node()
          .evalTap: node =>
            nodesRef.update(_ + (node.id -> node))
          .useForever

      def removeNode(id: String): IO[Unit] = // TODO: cancel running node
        nodesRef.get.flatMap: nodes =>
          nodes.get(id).foldMapM: node =>
            nodesRef.update(_ - id) *>
              fs2.Stream
                .evalSeq(node.active)
                .parEvalMapUnbounded: ins =>
                  nodeScoreByIns.update(_.updatedWith(ins.id)(_.map(_.dropRight(1))))
                    .flatMap(_ => addImpl(ins))
                .compile
                .drain
