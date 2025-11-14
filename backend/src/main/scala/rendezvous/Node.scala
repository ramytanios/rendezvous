package rendezvous.backend

import cats.effect.IO
import cats.effect.kernel.Fiber
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.std.MapRef
import cats.effect.std.Queue
import cats.effect.std.Supervisor
import cats.syntax.all.*
import fs2.concurrent.SignallingMapRef

import scala.collection.immutable.ListMap
import scala.concurrent.duration.*

trait Node:

  def add(task: Task): IO[Unit]

  def remove(taskId: TaskID): IO[Unit]

  def snapshot: IO[List[Task]]

  def updates: fs2.Stream[IO, Task]

object Node:

  def resource(
      nodeId: NodeID,
      maxLife: Option[FiniteDuration] = None,
      heartbeat: PubSub[NodeID]
  ): Resource[IO, Node] =
    for
      supervisor <- Supervisor[IO]
      taskRef <- Ref.of[IO, ListMap[TaskID, Task]](ListMap.empty).toResource
      updatesQ <- Queue.unbounded[IO, Task].toResource
      fibers <- SignallingMapRef
        .ofSingleImmutableMap[IO, TaskID, Fiber[IO, Throwable, Unit]]()
        .toResource
      _ <-
        val s = fs2.Stream
          .fixedRateStartImmediately[IO](1.second)
          .evalMap(_ => heartbeat.publish(nodeId))
        maxLife
          .fold(s)(s.interruptAfter)
          .compile
          .drain
          .background
    yield new Node:

      def snapshot: IO[List[Task]] = taskRef.get.map(_.values.toList)

      def updates: fs2.Stream[IO, Task] = fs2.Stream.fromQueueUnterminated(updatesQ)

      def remove(taskId: TaskID): IO[Unit] =
        taskRef.update(_ - taskId) *> fibers(taskId).get.flatMap(_.foldMapM(_.cancel))

      def add(task: Task): IO[Unit] =
        supervisor
          .supervise:
            fs2.Stream
              .fixedRateStartImmediately[IO](3.seconds)
              .evalMap(_ => updatesQ.offer(task))
              .compile
              .drain
          .flatMap: fib =>
            fibers.getAndSetKeyValue(task.id, fib) <* taskRef.update(_ + (task.id -> task))
          .flatMap(_.foldMapM(_.cancel))
