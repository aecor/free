package io.aecor.liberator.tests

import cats.kernel.laws.discipline.GroupTests
import cats.laws.discipline.{ MonadTests, SerializableTests }
import cats.{ Eq, Id, Monad }
import io.aecor.liberator.Term
import org.scalacheck.{ Arbitrary, Cogen, Gen }

class TermTests extends LiberatorSuite with TermInstances {
  trait M[F[_]]
  implicit def mf: M[Id] = new M[Id] {}
  checkAll("Term[M, Int]", GroupTests[Term[M, Int]].group)
  checkAll("Term[M, ?]", MonadTests[Term[M, ?]].monad[Int, Int, Int])
  checkAll("Monad[Term[M, ?]]", SerializableTests.serializable(Monad[Term[M, ?]]))
}

sealed trait TermInstances {
  private def termGen[M[_[_]], A](maxDepth: Int)(implicit A: Arbitrary[A]): Gen[Term[M, A]] = {
    val noFlatMapped =
      Gen.oneOf(A.arbitrary.map(Term.pure[M, A]), A.arbitrary.map(Term.pure[M, A]))

    val nextDepth = Gen.chooseNum(1, math.max(1, maxDepth - 1))

    def withFlatMapped =
      for {
        fDepth <- nextDepth
        freeDepth <- nextDepth
        f <- Arbitrary
              .arbFunction1[A, Term[M, A]](
                Arbitrary(termGen[M, A](fDepth)),
                Cogen[Unit].contramap(_ => ())
              )
              .arbitrary
        freeFA <- termGen[M, A](freeDepth)
      } yield freeFA.flatMap(f)

    if (maxDepth <= 1) noFlatMapped
    else Gen.oneOf(noFlatMapped, withFlatMapped)
  }

  implicit def termArbitrary[M[_[_]], A](implicit A: Arbitrary[A]): Arbitrary[Term[M, A]] =
    Arbitrary(termGen[M, A](4))

  implicit def termEq[M[_[_]], A, F[_]](implicit mf: M[F],
                                        eqA: Eq[F[A]],
                                        F: Monad[F]): Eq[Term[M, A]] =
    new Eq[Term[M, A]] {
      override def eqv(x: Term[M, A], y: Term[M, A]): Boolean =
        eqA.eqv(x(mf), y(mf))
    }
}
