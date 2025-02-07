package caliban.wrappers

import caliban.CalibanError.ValidationError
import caliban.Value.{ NullValue, StringValue }
import caliban.wrappers.Wrapper.OverallWrapper
import caliban.{ CalibanError, GraphQLRequest, GraphQLResponse, InputValue }
import zio.{ Has, Layer, Ref, UIO, ZIO }

import java.nio.charset.StandardCharsets
import scala.collection.mutable

object ApolloPersistedQueries {

  type ApolloPersistence = Has[Service]

  trait Service {
    def get(hash: String): UIO[Option[String]]
    def add(hash: String, query: String): UIO[Unit]
  }

  object Service {
    val live: UIO[Service] = Ref.make[Map[String, String]](Map()).map { cache =>
      new Service {
        override def get(hash: String): UIO[Option[String]]      = cache.get.map(_.get(hash))
        override def add(hash: String, query: String): UIO[Unit] = cache.update(_.updated(hash, query))
      }
    }
  }

  val live: Layer[Nothing, ApolloPersistence] = Service.live.toLayer

  /**
   * Returns a wrapper that persists and retrieves queries based on a hash
   * following Apollo Persisted Queries spec: https://github.com/apollographql/apollo-link-persisted-queries.
   */
  val apolloPersistedQueries: OverallWrapper[ApolloPersistence] =
    new OverallWrapper[ApolloPersistence] {
      def wrap[R1 <: ApolloPersistence](
        process: GraphQLRequest => ZIO[R1, Nothing, GraphQLResponse[CalibanError]]
      ): GraphQLRequest => ZIO[R1, Nothing, GraphQLResponse[CalibanError]] =
        (request: GraphQLRequest) =>
          readHash(request) match {
            case Some(hash) =>
              ZIO
                .accessM[ApolloPersistence](_.get.get(hash))
                .flatMap {
                  case Some(query) => UIO(request.copy(query = Some(query)))
                  case None        =>
                    request.query match {
                      case Some(value) if checkHash(hash, value) =>
                        ZIO.accessM[ApolloPersistence](_.get.add(hash, value)).as(request)
                      case Some(_)                               => ZIO.fail(ValidationError("Provided sha does not match any query", ""))
                      case None                                  => ZIO.fail(ValidationError("PersistedQueryNotFound", ""))
                    }

                }
                .flatMap(process)
                .catchAll(ex => UIO(GraphQLResponse(NullValue, List(ex))))
            case None       => process(request)
          }
    }

  private def readHash(request: GraphQLRequest): Option[String] =
    request.extensions
      .flatMap(_.get("persistedQuery"))
      .flatMap {
        case InputValue.ObjectValue(fields) =>
          fields.get("sha256Hash").collectFirst { case StringValue(hash) =>
            hash
          }
        case _                              => None
      }

  private def hex(bytes: Array[Byte]): String = {
    val builder = new mutable.StringBuilder(bytes.length * 2)
    bytes.foreach(byte => builder.append(f"$byte%02x".toLowerCase))
    builder.mkString
  }

  private def checkHash(hash: String, query: String): Boolean = {
    val sha256 = java.security.MessageDigest.getInstance("SHA-256")
    val digest = sha256.digest(query.getBytes(StandardCharsets.UTF_8))
    hex(digest) == hash
  }
}
