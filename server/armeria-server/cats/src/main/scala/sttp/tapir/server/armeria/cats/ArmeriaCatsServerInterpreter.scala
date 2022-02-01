package sttp.tapir.server.armeria.cats

import cats.effect.Async
import cats.effect.std.Dispatcher
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.server.HttpServiceWithRoutes
import fs2._
import fs2.interop.reactivestreams.{PublisherOps, StreamUnicastPublisher}
import org.reactivestreams.Publisher
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.armeria._

trait ArmeriaCatsServerInterpreter[F[_]] {

  implicit def fa: Async[F]

  def armeriaServerOptions: ArmeriaCatsServerOptions[F]

  def toRoute(serverEndpoint: ServerEndpoint[Fs2Streams[F], F]): HttpServiceWithRoutes =
    toRoute(List(serverEndpoint))

  def toRoute(serverEndpoints: List[ServerEndpoint[Fs2Streams[F], F]]): HttpServiceWithRoutes =
    new TapirCatsService(serverEndpoints, armeriaServerOptions)
}

object ArmeriaCatsServerInterpreter {
  def apply[F[_]](dispatcher: Dispatcher[F])(implicit _fa: Async[F]): ArmeriaCatsServerInterpreter[F] = {
    new ArmeriaCatsServerInterpreter[F] {
      override implicit def fa: Async[F] = _fa
      override def armeriaServerOptions: ArmeriaCatsServerOptions[F] = ArmeriaCatsServerOptions.default[F](dispatcher)(fa)
    }
  }

  def apply[F[_]](serverOptions: ArmeriaCatsServerOptions[F])(implicit _fa: Async[F]): ArmeriaCatsServerInterpreter[F] = {
    new ArmeriaCatsServerInterpreter[F] {
      override implicit def fa: Async[F] = _fa
      override def armeriaServerOptions: ArmeriaCatsServerOptions[F] = serverOptions
    }
  }
}

private object Fs2StreamCompatible {
  def apply[F[_]: Async](dispatcher: Dispatcher[F]): StreamCompatible[Fs2Streams[F]] = {
    new StreamCompatible[Fs2Streams[F]] {
      override val streams: Fs2Streams[F] = Fs2Streams[F]

      override def asStreamMessage(stream: Stream[F, Byte]): Publisher[HttpData] =
        StreamUnicastPublisher(
          stream.chunks
            .map { chunk =>
              val bytes = chunk.compact
              HttpData.wrap(bytes.values, bytes.offset, bytes.length)
            },
          dispatcher
        )

      override def fromArmeriaStream(publisher: Publisher[HttpData]): Stream[F, Byte] =
        publisher.toStreamBuffered[F](4).flatMap(httpData => Stream.chunk(Chunk.array(httpData.array())))
    }
  }
}

private class CatsFromFuture[F[_]: Async](implicit ec: ExecutionContext) extends FromFuture[F] {
  override def apply[A](f: => Future[A]): F[A] = {
    Async[F].async_ { cb =>
      f.onComplete {
        case Failure(exception) => cb(Left(exception))
        case Success(value)     => cb(Right(value))
      }
      ()
    }
  }
}
