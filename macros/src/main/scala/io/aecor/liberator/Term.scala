package io.aecor.liberator

import cats.kernel.Monoid
import cats.{ Apply, CoflatMap, Monad, ~> }
import io.aecor.liberator.Term.{ FlatMap, Pure }

import scala.annotation.tailrec

trait Term[M[_[_]], A] { outer =>
  def apply[F[_]](ops: M[F])(implicit F: Monad[F]): F[A] =
    F.tailRecM(this)(_.step match {
      case Pure(a) => F.pure(Right(a))
      case FlatMap(c, g) => F.map(c(ops))(cc => Left(g(cc)))
      case other => F.map(other(ops))(cc => Right(cc))
    })

  @tailrec
  final def step: Term[M, A] = this match {
    case FlatMap(FlatMap(c, f), g) => c.flatMap(cc => f(cc).flatMap(g)).step
    case FlatMap(Pure(a), f) => f(a).step
    case x => x
  }

  final def flatMap[B](f: A => Term[M, B]): Term[M, B] = Term.FlatMap(this, f)

  final def map[B](f: A => B): Term[M, B] = flatMap(a => Term.pure(f(a)))

  final def contramapK[G[_[_]]](f: FunctionKK[G, M]): Term[G, A] = new Term[G, A] {
    override def apply[F[_]](ops: G[F])(implicit F: Monad[F]): F[A] =
      outer(f(ops))
  }

  final def ap[B](fab: Term[M, A => B]): Term[M, B] = new Term[M, B] {
    override def apply[F[_]](ops: M[F])(implicit F: Monad[F]): F[B] =
      F.ap(fab(ops))(outer(ops))
  }
}

object Term extends TermInstances {
  case class FlatMap[M[_[_]], A, B](fa: Term[M, A], f: A => Term[M, B]) extends Term[M, B]
  case class Pure[M[_[_]], A](a: A) extends Term[M, A]
  case class Effect[M[_[_]], A](value: Invoke[M, A]) extends Term[M, A]

  trait Invoke[M[_[_]], A] {
    def apply[F[_]](mf: M[F]): F[A]
  }

  def pure[M[_[_]], A](a: A): Term[M, A] = Pure(a)
  def transpile[M[_[_]], N[_[_]], F[_]: Monad](mtn: M[Term[N, ?]],
                                               nf: N[F])(implicit ev: Algebra[M]): M[F] =
    ev.mapK(mtn)(λ[Term[N, ?] ~> F](_(nf)))

}

final class TermSyntaxIdOps[M[_[_]], N[_[_]]](val self: M[Term[N, ?]]) extends AnyVal {
  def transpile[F[_]: Monad](nf: N[F])(implicit ev: Algebra[M]): M[F] =
    Term.transpile(self, nf)
}

trait TermSyntax {
  implicit def toTermSyntaxIdOps[M[_[_]], N[_[_]]](a: M[Term[N, ?]]): TermSyntaxIdOps[M, N] =
    new TermSyntaxIdOps(a)
}

private[liberator] trait TermInstances {
  implicit def catsMonadInstance[M[_[_]]]: Monad[Term[M, ?]] with CoflatMap[Term[M, ?]] =
    new Monad[Term[M, ?]] with CoflatMap[Term[M, ?]] {
      override def tailRecM[A, B](a: A)(f: (A) => Term[M, Either[A, B]]): Term[M, B] =
        f(a).flatMap {
          case Left(x) => tailRecM(x)(f)
          case Right(b) => pure(b)
        }

      override def ap[A, B](ff: Term[M, (A) => B])(fa: Term[M, A]): Term[M, B] = fa.ap(ff)

      override def flatMap[A, B](fa: Term[M, A])(f: (A) => Term[M, B]): Term[M, B] =
        fa.flatMap(f)

      override def pure[A](x: A): Term[M, A] = Term.pure(x)

      override def coflatMap[A, B](fa: Term[M, A])(f: (Term[M, A]) => B): Term[M, B] =
        pure(f(fa))
    }

  implicit def catsMonoidInstanceForTerm[M[_[_]], A: Monoid]: Monoid[Term[M, A]] =
    new Monoid[Term[M, A]] {
      override def empty: Term[M, A] = Term.pure(Monoid[A].empty)

      override def combine(x: Term[M, A], y: Term[M, A]): Term[M, A] =
        Apply[Term[M, ?]].map2(x, y)(Monoid[A].combine)

    }

  implicit def liftGeneric[M[_[_]], N[_[_]]](implicit extract: Extract[M, N],
                                             algebra: Algebra[N]): N[Term[M, ?]] =
    algebra.fromFunctionK(new (algebra.Out ~> Term[M, ?]) {
      override def apply[A](fa: algebra.Out[A]): Term[M, A] =
        new Term[M, A] {
          override def apply[F[_]](alg: M[F])(implicit F: Monad[F]): F[A] =
            algebra.toFunctionK(extract(alg))(fa)
        }
    })
}
