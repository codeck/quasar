package blueeyes

import blueeyes.core.http._
import blueeyes.core.service._
import blueeyes.core.service.engines._
import core.data.ChunkReader

/** Convenience trait for building a server. This server uses reflection to mix
 * in any services defined as fields.
 * <pre>
 * trait EmailServices extends BlueEyesServiceBuilder {
 *   val emailService = service("email", "1.32") {
 *     request {
 *       ...
 *     }
 *   }
 * }
 * object EmailServer extends BlueEyesServer with EmailServices
 * </pre>
 */
trait BlueEyesServer extends HttpServer[ChunkReader] with HttpReflectiveServiceList[ChunkReader] with NettyEngine {

}