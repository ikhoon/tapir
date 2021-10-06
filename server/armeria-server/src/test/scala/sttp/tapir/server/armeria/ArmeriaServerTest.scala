package sttp.tapir.server.armeria

import cats.effect.{IO, Resource}
import sttp.monad.FutureMonad
import sttp.tapir.server.tests._
import sttp.tapir.tests.{Test, TestSuite}

class ArmeriaServerTest extends TestSuite {

  override def tests: Resource[IO, List[Test]] = backendResource.map { backend =>
    implicit val m: FutureMonad = new FutureMonad()

    val interpreter = new ArmeriaTestServerInterpreter()
    val createServerTest = new DefaultCreateServerTest(backend, interpreter)

    new ServerBasicTests(createServerTest, interpreter, supportsUrlEncodedPathSegments = false).tests() ++
      new ServerFileMultipartTests(createServerTest).tests() ++
      new ServerAuthenticationTests(createServerTest).tests() ++
      new ServerMetricsTest(createServerTest).tests()
  }
}
