package sttp.tapir.server.armeria

import com.linecorp.armeria.common.multipart.{AggregatedBodyPart, Multipart}
import com.linecorp.armeria.common.stream.{StreamMessage, StreamMessages}
import com.linecorp.armeria.common.{HttpData, HttpRequest}
import com.linecorp.armeria.server.ServiceRequestContext
import java.io.ByteArrayInputStream
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.FutureConverters.CompletionStageOps
import sttp.capabilities.Streams
import sttp.model.Part
import sttp.tapir.server.interpreter.{RawValue, RequestBody}
import sttp.tapir.{FileRange, RawBodyType}

final class ArmeriaRequestBody[F[_], S <: Streams[S]](
    ctx: ServiceRequestContext,
    serverOptions: ArmeriaServerOptions[F],
    fromFuture: FromFuture[F],
    streamCompatible: StreamCompatible[S]
)(implicit ec: ExecutionContext)
    extends RequestBody[F, S] {

  private val request: HttpRequest = ctx.request()

  override val streams: Streams[S] = streamCompatible.streams

  override def toStream(): streams.BinaryStream = {
    streamCompatible
      .fromArmeriaStream(request.filter(x => x.isInstanceOf[HttpData]).asInstanceOf[StreamMessage[HttpData]])
      .asInstanceOf[streams.BinaryStream]
  }

  override def toRaw[R](bodyType: RawBodyType[R]): F[RawValue[R]] = fromFuture(bodyType match {
    case RawBodyType.StringBody(_) =>
      request.aggregate().thenApply[RawValue[R]](agg => RawValue(agg.contentUtf8())).asScala
    case RawBodyType.ByteArrayBody =>
      request.aggregate().thenApply[RawValue[R]](agg => RawValue(agg.content().array())).asScala
    case RawBodyType.ByteBufferBody =>
      request.aggregate().thenApply[RawValue[R]](agg => RawValue(agg.content().byteBuf().nioBuffer())).asScala
    case RawBodyType.InputStreamBody =>
      request
        .aggregate()
        .thenApply[RawValue[R]](agg => RawValue(new ByteArrayInputStream(agg.content().array())))
        .asScala
    case RawBodyType.FileBody =>
      val bodyStream = request.filter(x => x.isInstanceOf[HttpData]).asInstanceOf[StreamMessage[HttpData]]
      for {
        file <- serverOptions.createFile(ctx)
        _ <- StreamMessages.writeTo(bodyStream, file.toPath, ctx.eventLoop(), ctx.blockingTaskExecutor()).asScala
        fileRange = FileRange(file)
      } yield RawValue(fileRange, Seq(fileRange))
    case m: RawBodyType.MultipartBody =>
      Multipart
        .from(request)
        .aggregate()
        .asScala
        .flatMap(multipart => {
          val rawParts = multipart
            .bodyParts()
            .asScala
            .toList
            .flatMap(part => m.partType(part.name()).map(toRawPart(part, _)))

          Future
            .sequence(rawParts)
            .map(RawValue.fromParts(_))
        })
        .asInstanceOf[Future[RawValue[R]]]
  })

  private def toRawFromHttpData[R](body: HttpData, bodyType: RawBodyType[R]): Future[RawValue[R]] = {
    bodyType match {
      case RawBodyType.StringBody(_)   => Future.successful(RawValue(body.toStringUtf8))
      case RawBodyType.ByteArrayBody   => Future.successful(RawValue(body.array()))
      case RawBodyType.ByteBufferBody  => Future.successful(RawValue(body.byteBuf().nioBuffer()))
      case RawBodyType.InputStreamBody => Future.successful(RawValue(new ByteArrayInputStream(body.array())))
      case RawBodyType.FileBody =>
        for {
          file <- serverOptions.createFile(ctx)
          _ <- StreamMessages.writeTo(StreamMessage.of(body), file.toPath, ctx.eventLoop(), ctx.blockingTaskExecutor()).asScala
          fileRange = FileRange(file)
        } yield RawValue(fileRange, Seq(fileRange))
      case RawBodyType.MultipartBody(_, _) =>
        throw new UnsupportedOperationException("Nested multipart data is not supported.")
    }
  }

  private def toRawPart[R](part: AggregatedBodyPart, bodyType: RawBodyType[R]): Future[Part[R]] = {
    toRawFromHttpData(part.content(), bodyType)
      .map((r: RawValue[R]) =>
        Part(
          name = part.name,
          body = r.value,
          contentType = if (part.contentType() != null) {
            Some(HeaderMapping.fromArmeria(part.contentType()))
          } else {
            None
          },
          fileName = Option(part.filename()),
          otherHeaders = HeaderMapping.fromArmeria(part.headers())
        )
      )
  }
}
