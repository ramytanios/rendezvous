package backend

import cats.effect.IO
import cats.effect.kernel.Fiber
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.std.Supervisor
import cats.syntax.all.*
import fs2.concurrent.SignallingRef

import java.util.UUID
import fs2.concurrent.Signal

trait Engine:

  def nodes: Signal[IO, Map[UUID, Node]]

  def createNode: IO[Unit]

  def removeNode(nodeId: UUID): IO[Unit]

  def addData(data: Data): IO[Unit]

object Engine:

  def apply(): Resource[IO, Engine] =
    for
      supervisor <- Supervisor[IO]
      hash <- IO.unit.as(Hash.mmh3()).toResource
      nodesRef <- SignallingRef.of[IO, Map[UUID, Node]](Map.empty).toResource
      nodeScoreByData <- Ref.of[IO, Map[UUID, List[UUID]]](Map.empty).toResource
      fibers <- Ref.of[IO, Map[UUID, Fiber[IO, Throwable, Unit]]](Map.empty).toResource
    yield new Engine:

      def nodes: Signal[IO, Map[UUID, Node]] = nodesRef

      def addImpl(data: Data): IO[Unit] =
        nodesRef.get.flatMap: nodes =>
          nodeScoreByData.get.flatMap: scores =>
            scores.get(data.id).foldMapM: sortedNodes =>
              sortedNodes.lastOption.foldMapM: nodeId =>
                nodes.get(nodeId).foldMapM: node =>
                  node.add(data, _ => IO.unit)

      def addData(data: Data): IO[Unit] =
        nodesRef.get.flatMap: nodes =>
          val scores = nodes.keys.toList
            .map(nodeId => nodeId -> hash.hash(s"${data.id}${nodeId}"))
            .sortBy(_(1))
          nodeScoreByData
            .update(_ + (data.id -> scores.map(_(0))))
            .flatMap(_ => addImpl(data))

      def createNode: IO[Unit] =
        IO.randomUUID.flatMap: nodeId =>
          supervisor.supervise:
            fs2.Stream.resource(
              Node().evalTap: node =>
                nodesRef.update(_ + (nodeId -> node))
            ).compile.drain
          .flatMap: fib =>
            fibers.update(_ + (nodeId -> fib))

      def removeNode(nodeId: UUID): IO[Unit] =
        nodesRef.get
          .flatMap: nodes =>
            nodes.get(nodeId).foldMapM: node =>
              fs2.Stream
                .evalSeq(node.data)
                .parEvalMapUnbounded: data =>
                  nodeScoreByData.update(_.updatedWith(data.id)(_.map(_.dropRight(1))))
                    .flatMap(_ => addImpl(data))
                .compile
                .drain
          .flatMap: _ =>
            nodesRef.update(_ - nodeId)
          .flatMap: _ =>
            fibers.get.flatMap(_.get(nodeId).foldMapM(_.cancel))
          .flatMap: _ =>
            IO.println(s"Node $nodeId is removed")
