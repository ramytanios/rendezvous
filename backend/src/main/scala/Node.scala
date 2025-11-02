package backend

import cats.effect.IO
import cats.effect.std.Supervisor
import cats.effect.kernel.Resource
import fs2.concurrent.SignallingMapRef
import cats.effect.kernel.Fiber
import cats.syntax.all.*
import cats.effect.std.MapRef
import cats.effect.kernel.Ref

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
    yield new Node:

      def id: String = uuid.toString

      def active: IO[Seq[Instrument]] = instruments.get.map(_.toSeq)

      def add(ins: Instrument, f: Instrument => IO[Unit]): IO[Unit] =
        supervisor
          .supervise(f(ins))
          .flatMap: fib =>
            fibers.getAndSetKeyValue(ins.id, fib) <* instruments.update(_ + ins)
          .flatMap(_.foldMapM(_.cancel))
