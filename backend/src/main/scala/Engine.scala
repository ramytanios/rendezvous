package rendezvous.backend

import cats.effect.IO
import cats.effect.kernel.Fiber
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.std.Supervisor
import cats.syntax.all.*
import fs2.concurrent.SignallingRef

import java.util.UUID
import scala.collection.immutable.SortedMap

trait Engine:

  def createNode: IO[Unit]

  def removeNode(nodeId: UUID): IO[Unit]

  def addData(data: Data): IO[Unit]

  def nodes: fs2.Stream[IO, Map[UUID, Node]]

  def updates: fs2.Stream[IO, (UUID, Data)]

object Engine:

  case class NoNodesAvailable() extends RuntimeException("no nodes available")

  def apply(): Resource[IO, Engine] =
    for
      supervisor <- Supervisor[IO]
      hash <- IO.unit.as(Hash.mmh3()).toResource
      nodesRef <- SignallingRef.of[IO, SortedMap[UUID, Node]](SortedMap.empty).toResource
      scoresRef <- SignallingRef.of[IO, Map[UUID, List[UUID]]](Map.empty).toResource
      fibers <- Ref.of[IO, Map[UUID, Fiber[IO, Throwable, Unit]]](Map.empty).toResource
    yield new Engine:

      def nodes: fs2.Stream[IO, Map[UUID, Node]] =
        scoresRef.discrete.switchMap: _ =>
          nodesRef.discrete

      def updates: fs2.Stream[IO, (UUID, Data)] =
        nodesRef.discrete.switchMap:
          fs2.Stream.emit(_)
            .map(_.toList)
            .flatMap(fs2.Stream.emits(_))
            .map: (nodeId, node) =>
              node.updates.tupleLeft(nodeId)
            .parJoinUnbounded

      def addDataImpl(data: Data): IO[Unit] =
        nodesRef.get.flatMap: nodes =>
          scoresRef.get.flatMap: scores =>
            scores.get(data.id).foldMapM: sortedNodes =>
              sortedNodes.lastOption.foldMapM: nodeId =>
                nodes.get(nodeId).foldMapM: node =>
                  node.add(data)

      def shuffleData(): IO[Unit] =
        fs2.Stream.eval(nodesRef.get)
          .flatMap(nodes => fs2.Stream.emits(nodes.toList))
          .parEvalMapUnbounded: (_, node) =>
            fs2.Stream
              .evalSeq(node.snapshot)
              .parEvalMapUnbounded(addDataImpl)
              .compile
              .drain
          .compile
          .drain

      def addData(data: Data): IO[Unit] =
        nodesRef.get.flatMap: nodes =>
          val scores = nodes.keys.toList
            .map(nodeId => nodeId -> hash.hash(s"${data.id}${nodeId}"))
            .sortBy(_(1)).map(_(0))
          IO.raiseWhen(scores.length == 0)(throw new NoNodesAvailable) *>
            scoresRef
              .update(_ + (data.id -> scores))
              .flatMap(_ => addDataImpl(data))

      def createNode: IO[Unit] =
        IO.randomUUID.flatMap: nodeId =>
          supervisor.supervise:
            Node.resource(nodeId).use: node =>
              nodesRef.update(_ + (nodeId -> node)) *>
                IO.never.as(())
          .flatMap: fib =>
            fibers.update(_ + (nodeId -> fib))
          .flatMap: _ =>
            scoresRef.update: scores =>
              scores.map: (dataId, nodes) =>
                dataId -> nodes.map(nodeId => nodeId -> hash.hash(s"$dataId$nodeId"))
                  .sortBy(_(1)).map(_(0))
          .flatMap: _ =>
            shuffleData()

      def removeNode(nodeId: UUID): IO[Unit] =
        nodesRef.get
          .flatMap: nodes =>
            nodes.get(nodeId).foldMapM: node =>
              fs2.Stream
                .evalSeq(node.snapshot)
                .parEvalMapUnbounded: data =>
                  scoresRef
                    .update(_.updatedWith(data.id)(_.map(_.dropRight(1))))
                    .flatMap(_ => addDataImpl(data))
                .compile
                .drain
          .flatMap: _ =>
            nodesRef.update(_ - nodeId)
          .flatMap: _ =>
            fibers.get.flatMap(_.get(nodeId).foldMapM(_.cancel))
          .flatMap: _ =>
            IO.println(s"Node $nodeId is removed")
