package backend

import cats.effect.IO
import cats.effect.kernel.Fiber
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.std.MapRef
import cats.effect.std.Supervisor
import cats.syntax.all.*
import fs2.concurrent.SignallingMapRef

import scala.concurrent.duration.*

trait Node:

  def id: String

  def add(ins: Instrument, f: Instrument => IO[Unit]): IO[Unit]

  def active: IO[Seq[Instrument]]

object Node:

  def apply(): Resource[IO, Node] =
    for
      uuid <- IO.randomUUID.toResource
      supervisor <- Supervisor[IO]
      instruments <- Ref.of[IO, Set[Instrument]](Set.empty).toResource
      fibers <- SignallingMapRef
        .ofSingleImmutableMap[IO, String, Fiber[IO, Throwable, Unit]]()
        .toResource
      _ <- supervisor.supervise:
        fs2.Stream // makes this stream tied to the supervisor life and not the calling fiber
          .fixedRateStartImmediately[IO](3.seconds)
          .evalMap: _ =>
            IO.println(s"Node $uuid is up and running")
          .compile
          .drain
      .toResource
    yield new Node:

      def id: String = uuid.toString

      def active: IO[Seq[Instrument]] = instruments.get.map(_.toSeq)

      def add(ins: Instrument, f: Instrument => IO[Unit]): IO[Unit] =
        supervisor
          .supervise(f(ins))
          .flatMap: fib =>
            fibers.getAndSetKeyValue(ins.id, fib) <* instruments.update(_ + ins)
          .flatMap(_.foldMapM(_.cancel))
