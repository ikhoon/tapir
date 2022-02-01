package sttp.tapir.server.armeria.cats

import cats.effect.{IO, Resource}
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.integ.cats.CatsMonadError
import sttp.tapir.server.tests._
import sttp.tapir.tests.{Test, TestSuite}

class ArmeriaCatsServerTest extends TestSuite {

  override def tests: Resource[IO, List[Test]] = backendResource.map { backend =>
    implicit val m: CatsMonadError[IO] = new CatsMonadError[IO]

    val interpreter = new ArmeriaCatsTestServerInterpreter(dispatcher)
    val createServerTest = new DefaultCreateServerTest(backend, interpreter)

    new AllServerTests(createServerTest, interpreter, backend, basic = false, reject = false).tests() ++
      new ServerBasicTests(createServerTest, interpreter, supportsUrlEncodedPathSegments = false).tests() ++
      new ServerRejectTests(createServerTest, interpreter, useMethodNotAllowedForUnsupportedMethod = true).tests() ++
      new ServerStreamingTests(createServerTest, Fs2Streams[IO]).tests()
  }
}
