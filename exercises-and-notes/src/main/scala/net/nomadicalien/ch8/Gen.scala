package net.nomadicalien.ch8

import java.util.concurrent.{ExecutorService, Executors}

import net.nomadicalien.ch5.Stream
import net.nomadicalien.ch6.{RNG, State}
import net.nomadicalien.ch7.Nonblocking.Par
import net.nomadicalien.ch8.Prop._

import scala.annotation.tailrec

// Exercise 8.3
object Prop1 {

  trait Prop {
    def check: Boolean

    def &&(p: Prop): Prop = new Prop { self =>
      def check: Boolean = self.check && p.check
    }
  }

}

case class Prop(run: (MaxSize,TestCases,RNG) => Result) {
  // Exercise 8.9
  def &&(p: Prop): Prop = Prop {
    (maxSize,numCases, rng) =>
      run(maxSize,numCases, rng) match {
        case Passed | Proved => p.run(maxSize,numCases, rng)
        case f => f
      }
  }
  def ||(p: Prop): Prop = Prop {
    (maxSize,numCases, rng) =>
      run(maxSize,numCases, rng) match {
        case Falsified(failureMessage,_) => p.tag(failureMessage).run(maxSize,numCases,rng)
        case p => p
      }
  }

  def tag(msg: String) = Prop {
    (maxSize,numCases,rng) => run(maxSize,numCases,rng) match {
      case Falsified(e, c) => Falsified(msg + "\n" + e, c)
      case x => x
    }
  }
}

sealed trait Result {
  def isFalsified: Boolean
}
case object Passed extends Result {
  def isFalsified = false
}
case object Proved extends Result {
 def isFalsified: Boolean = false
}
case class Falsified(failure: FailedCase,
                     successes: SuccessCount) extends Result {
  def isFalsified = true
}

object Prop {
  type MaxSize = Int
  type SuccessCount = Int
  type FailedCase = String
  type TestCases = Int
 // type Result = Option[(FailedCase, SuccessCount)]

 def forAll[A](as: Gen[A])(f: A => Boolean): Prop = Prop {
   (n,rng) => randomStream(as)(rng).zip(Stream.from(0)).take(n).map {
     case (a, i) => try {
       if (f(a)) Passed else Falsified(a.toString, i)
     } catch { case e: Exception => Falsified(buildMsg(a, e), i) }
   }.find(_.isFalsified).getOrElse(Passed)
 }
  def randomStream[A](g: Gen[A])(rng: RNG): Stream[A] =
    Stream.unfold(rng)(rng => Some(g.sample.run(rng)))

  def buildMsg[A](s: A, e: Exception): String =
    s"test case: $s\n" +
      s"generated an exception: ${e.getMessage}\n" +
      s"stack trace:\n ${e.getStackTrace.mkString("\n")}"

  def apply(f: (TestCases,RNG) => Result): Prop =
    Prop { (_,n,rng) => f(n,rng) }

  def forAll[A](g: SGen[A])(f: A => Boolean): Prop =
    forAll(g(_))(f)

  def forAll[A](g: Int => Gen[A])(f: A => Boolean): Prop = Prop {
    (max,n,rng) =>
      val casesPerSize = (n + (max - 1)) / max
      val props: Stream[Prop] =
        Stream.from(0).take((n min max) + 1).map(i => forAll(g(i))(f))
      val prop: Prop =
        props.map(p => Prop { (max, _, rng) =>
          p.run(max, casesPerSize, rng)
        }).toList.reduce(_ && _)
      prop.run(max,n,rng)
  }

  def run(p: Prop,
          maxSize: Int = 100,
          testCases: Int = 100,
          rng: RNG = RNG.Simple(System.currentTimeMillis)): Unit =
    p.run(maxSize, testCases, rng) match {
      case Falsified(msg, n) =>
        println(s"! Falsified after $n passed tests:\n $msg")
      case Passed =>
        println(s"+ OK, passed $testCases tests.")
      case Proved =>
        println(s"+ OK, proved property.")
    }

}

object Gen {
  // Exercise 8.4
  def choose(start: Int, stopExclusive: Int): Gen[Int] = {
    @tailrec def c(r: RNG): (Int, RNG) = {
      val nextPair = r.nextInt
      val generatedInt: Int = nextPair._1
      val updatedRNG: RNG = nextPair._2
      if(generatedInt >= start && generatedInt < stopExclusive) {
        nextPair
      } else {
        c(updatedRNG)
      }
    }

    Gen[Int](
      State(c)
    )
  }
  // Exercise 8.5
  def unit[A](a: => A): Gen[A] = Gen[A](State.unit(a))
  def boolean: Gen[Boolean] = Gen(State(RNG.boolean))
  def listOfN[A](n: Int, a: Gen[A]): Gen[List[A]] =
    Gen(State.sequence(List.fill(n)(a.sample)))

  // Exercise 8.7
  def union[A](g1: Gen[A], g2: Gen[A]): Gen[A] = boolean flatMap { useLeft =>
  if(useLeft)
    g1
  else
    g2
  }

  // Exercise 8.8
  def weighted[A](g1: (Gen[A],Double), g2: (Gen[A],Double)): Gen[A] = {
    val cutoff = g1._2 / (g1._2 + g2._2)
    Gen(State(RNG.double)).flatMap { d =>
      if(d > cutoff)
        g2._1
      else
        g1._1

    }
  }

  // Exercise 8.12
  def listOf[A](g: Gen[A]): SGen[List[A]] =
    SGen(i => listOfN(i, g))

  lazy val smallInt = Gen.choose(-10,10)
  lazy val maxProp = forAll(listOf(smallInt)) { l =>
    val max = l.max
    !l.exists(_ > max) // No value greater than `max` should exist in `l`
  }

  // Exercise 8.13
  def listOf1[A](g: Gen[A]): SGen[List[A]] =
    SGen(n => g.listOfN(n max 1))

  lazy val maxProp1 = forAll(listOf1(smallInt)) { l =>
    val max = l.max
    !l.exists(_ > max) // No value greater than `max` should exist in `l`
  }

  // Exercise 8.14
  lazy val sortedProp = forAll(listOf(smallInt)) { ns =>
    val nss = ns.sorted
    // We specify that every sorted list is either empty, has one element,
    // or has no two consecutive elements `(a,b)` such that `a` is greater than `b`.
    (ns.isEmpty || nss.tail.isEmpty || !ns.zip(ns.tail).exists {
      case (a,b) => a > b
    })
  }

  lazy val ES: ExecutorService = Executors.newCachedThreadPool
  lazy val p1 = Prop.forAll(Gen.unit(Par.unit(1)))(i =>
    Par.run(ES)(Par.map(i)(_ + 1)) == Par.run(ES)(Par.unit(2)))

  def check(p: => Boolean): Prop = Prop { (_, _, _) =>
    if (p) Passed else Falsified("()", 0)
  }

/*  val p2 = check {
    val p = Par.map(Par.unit(1))(_ + 1)
    val p2 = Par.unit(2)
    p(ES).get == p2(ES).get
  }*/

  def equal[A](p: Par[A], p2: Par[A]): Par[Boolean] =
    Par.map2(p,p2)(_ == _)

  lazy val p3 = check {
    Par.run(ES)(equal(
      Par.map(Par.unit(1))(_ + 1),
      Par.unit(2)
    ))
  }

  lazy val S = weighted(
    choose(8,32).map(Executors.newFixedThreadPool) -> .75,
    unit(Executors.newCachedThreadPool) -> .25)
/*

  def forAllPar[A](g: Gen[A])(f: A => Par[Boolean]): Prop =
    forAll(S.map2(g)((_,_))) { case (s,a) => f(a)(s).get }
*/

  /*def forAllPar[A](g: Gen[A])(f: A => Par[Boolean]): Prop =
    forAll(S ** g) { case (s,a) => f(a)(s).get }*/

  def forAllPar[A](g: Gen[A])(f: A => Par[Boolean]): Prop =
    forAll(S ** g) { case s ** a => Par.run(s)(f(a)) }

  def checkPar(p: Par[Boolean]): Prop =
    forAllPar(Gen.unit(()))(_ => p)

  lazy val p2 = checkPar {
    equal (
      Par.map(Par.unit(1))(_ + 1),
      Par.unit(2)
    )
  }

  lazy val pint = Gen.choose(0,10) map Par.unit
  lazy val p4 =
    forAllPar(pint)(n => equal(Par.map(n)(y => y), n))

  lazy val pint2: Gen[Par[Int]] = choose(-100,100).listOfN(choose(0,20)).map(l =>
    l.foldLeft(Par.unit(0))((p,i) =>
      Par.fork { Par.map2(p, Par.unit(i))(_ + _) }))

  // Exercise 8.17
  lazy val forkProp = forAllPar(pint2)(i => equal(Par.fork(i), i)) tag "fork"


  // Exercise 8.18
  lazy val isEven = (i: Int) => i%2 == 0
  lazy val takeWhileProp =
    Prop.forAll(Gen.listOf(Gen.choose(0, 100)))(ns => ns.takeWhile(isEven).forall(isEven))

  lazy val takeWhileDropWhileProp =
    Prop.forAll(Gen.listOf(Gen.choose(0, 100)))(ns => ns.takeWhile(isEven).dropWhile(isEven).isEmpty)
}

object ** {
  def unapply[A,B](p: (A,B)) = Some(p)
}

case class Gen[+A](sample: State[RNG,A]) {
  def map[B](f: A => B): Gen[B] =
    Gen(sample.map(f))

  def map2[B,C](g: Gen[B])(f: (A,B) => C): Gen[C] =
    Gen(sample.map2(g.sample)(f))

  // Exercise 8.6
  def flatMap[B](f: A => Gen[B]): Gen[B] =
    Gen(sample.flatMap(a => f(a).sample))

  def listOfN(size: Gen[Int]): Gen[List[A]] =
    size flatMap {n => this.listOfN(n)}


  def listOfN(size: Int): Gen[List[A]] =
    Gen.listOfN(size, this)

  // Exercise 8.10
  def unsized: SGen[A] = SGen(_ => this)

  def **[B](g: Gen[B]): Gen[(A,B)] =
    (this map2 g)((_,_))
}

case class SGen[+A](forSize: Int => Gen[A]) {
  def apply(n: Int): Gen[A] = forSize(n)

  def flatMap[B](f: A => Gen[B]): SGen[B] =
    SGen(forSize andThen (_ flatMap f))
}
