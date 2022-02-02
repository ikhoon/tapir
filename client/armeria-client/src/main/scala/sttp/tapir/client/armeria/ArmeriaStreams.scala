package sttp.tapir.client.armeria

import com.linecorp.armeria.common.HttpData
import org.reactivestreams.{Processor, Publisher}
import sttp.capabilities.Streams

// TODO(ikhoon): Remove this file when https://github.com/softwaremill/sttp-shared/pull/154 is merged.
trait ArmeriaStreams extends Streams[ArmeriaStreams] {
  override type BinaryStream = Publisher[HttpData]
  override type Pipe[A, B] = Processor[A, B]
}

object ArmeriaStreams extends ArmeriaStreams
