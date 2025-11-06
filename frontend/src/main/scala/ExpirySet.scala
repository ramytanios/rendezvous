package frontend

import cats.effect.Resource
import cats.effect.Temporal
import cats.effect.implicits.*
import cats.effect.kernel.Fiber
import cats.effect.std.Mutex
import cats.effect.std.Supervisor
import cats.syntax.all.*
import fs2.concurrent.SignallingRef

import scala.concurrent.duration.*

trait ExpirySet[F[_], V]:

  /** sets an element with an expiry, no-op if element exists */
  def set(elem: V, expiresIn: FiniteDuration): F[Unit]

  /** remove an element */
  def remove(elem: V): F[Unit]

  /** reset the expiry of an element, no-op if element does not exist */
  def resetExpiry(elem: V, expiresIn: FiniteDuration): F[Unit]

  /** current snapshot of the set */
  def snapshot: F[Set[V]]

  /** updates of the set */
  def updates: fs2.Stream[F, Set[V]]

object ExpirySet:
  def apply[F[_], V](using F: Temporal[F]): Resource[F, ExpirySet[F, V]] =
    for
      _          <- Resource.pure[F, Unit](())
      supervisor <- Supervisor[F]
      cache      <- SignallingRef.of(Set.empty[V]).toResource
      fibers     <- F.ref(Map.empty[V, Fiber[F, Throwable, Unit]]).toResource
      mutex      <- Mutex[F].toResource
    yield new ExpirySet[F, V]:

      override def set(elem: V, expiresIn: FiniteDuration): F[Unit] =
        mutex.lock.use { _ =>
          cache.get.flatMap(c =>
            if c.contains(elem) then
              F.unit
            else
              supervisor
                .supervise(
                  for
                    _ <- cache.update(_ + elem)
                    _ <- F.sleep(expiresIn)
                    _ <- cache.update(_ - elem)
                  yield ()
                )
                .flatMap(fib => fibers.update(_ + (elem -> fib)))
          )
        }

      override def remove(elem: V): F[Unit] = mutex.lock.use { _ =>
        cache
          .update(_ - elem)
          .flatMap(_ => fibers.getAndUpdate(_ - elem))
          .flatMap(c => c.get(elem).foldMapM(_.cancel))
      }

      override def resetExpiry(elem: V, expiresIn: FiniteDuration): F[Unit] =
        mutex.lock.use { _ =>
          cache.get.flatMap(c =>
            if c.contains(elem) then F.unit // no-op
            else
              fibers
                .getAndUpdate(_ - elem)
                .flatMap(_.get(elem).foldMapM(_.cancel))
                .flatMap(_ =>
                  supervisor.supervise(F.sleep(expiresIn) *> cache.update(_ - elem))
                )
                .flatMap(fib => fibers.update(_ + (elem -> fib)))
          )
        }

      override def snapshot: F[Set[V]] = cache.get

      override def updates: fs2.Stream[F, Set[V]] = cache.discrete
