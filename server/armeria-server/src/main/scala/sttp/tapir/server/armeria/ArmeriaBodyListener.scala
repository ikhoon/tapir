package sttp.tapir.server.armeria

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}
import sttp.tapir.server.interpreter.BodyListener

final class ArmeriaBodyListener(implicit ec: ExecutionContext) extends BodyListener[Future, ArmeriaResponseType] {
  override def onComplete(body: ArmeriaResponseType)(cb: Try[Unit] => Future[Unit]): Future[ArmeriaResponseType] = {
    // TODO(ikhoon): handle?
    cb(Success(())).map(_ => body)
  }
}
