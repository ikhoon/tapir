package sttp.tapir.server.armeria

import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.stream.StreamMessage
import io.netty.buffer.ByteBuf
import io.netty.util.concurrent.EventExecutor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousFileChannel, CompletionHandler}
import java.nio.file.{Path, StandardOpenOption}
import java.util.Objects.requireNonNull
import org.reactivestreams.{Subscriber, Subscription}
import scala.concurrent.{Future, Promise}

private final class AsyncFileWriter(
    publisher: StreamMessage[HttpData],
    path: Path,
    executor: EventExecutor
) extends Subscriber[HttpData]
    with CompletionHandler[Integer, (ByteBuffer, ByteBuf)] {

  private var subscription: Subscription = _
  private var position: Long = 0L
  private var writing: Boolean = false
  private var closing: Boolean = false

  private val promise: Promise[Unit] = Promise()
  private val fileChannel: AsynchronousFileChannel = AsynchronousFileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)

  // Start subscribing to publisher
  publisher.subscribe(this, executor)

  override def onSubscribe(subscription: Subscription): Unit = {
    requireNonNull(subscription, "subscription")
    this.subscription = subscription
    subscription.request(1)
  }

  override def onNext(httpData: HttpData): Unit = {
    if (httpData.isEmpty) {
      httpData.close()
      subscription.request(1)
    } else {
      val byteBuf: ByteBuf = httpData.byteBuf
      val byteBuffer: ByteBuffer = byteBuf.nioBuffer
      writing = true
      fileChannel.write(byteBuffer, position, (byteBuffer, byteBuf), this)
    }
  }

  override def onError(t: Throwable): Unit = {
    maybeCloseFileChannel(Some(t))
  }

  override def onComplete(): Unit = {
    if (!writing) {
      maybeCloseFileChannel(None)
    } else {
      closing = true
    }
  }

  def whenComplete(): Future[Unit] = promise.future

  override def completed(result: Integer, attachment: (ByteBuffer, ByteBuf)): Unit = {
    executor.execute(() => {
      if (result > -1) {
        position += result
        val (byteBuffer, byteBuf) = attachment

        if (byteBuffer.hasRemaining) {
          fileChannel.write(byteBuffer, position, attachment, this)
        } else {
          byteBuf.release()
          writing = false

          if (closing) {
            maybeCloseFileChannel(None)
          } else {
            subscription.request(1)
          }
        }
      } else {
        subscription.cancel()
        maybeCloseFileChannel(
          Some(
            new IOException(
              s"Unexpected exception while writing ${path}. " +
                s"result: $result"
            )
          )
        )
      }
    })
  }

  override def failed(cause: Throwable, attachment: (ByteBuffer, ByteBuf)): Unit = {
    subscription.cancel()
    attachment._2.release()
    maybeCloseFileChannel(Some(cause))
  }

  private def maybeCloseFileChannel(cause: Option[Throwable]): Unit = {
    if (promise.isCompleted) {
      // no-op
    } else {
      cause match {
        case Some(ex) =>
          promise.failure(ex)
        case None =>
          // All data was written successfully to a File
          if (fileChannel.isOpen) {
            try {
              fileChannel.close()
              promise.success(())
            } catch {
              case ex: IOException =>
                promise.failure(ex)
            }
          }
      }
    }
  }
}
