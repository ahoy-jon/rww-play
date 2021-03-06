package org.www.readwriteweb.play

import play.api.mvc._
import org.w3.banana._
import org.w3.banana.ldp._
import play.api.mvc.Results._
import concurrent.{Future, ExecutionContext}
import java.io.{StringWriter, PrintWriter}
import java.net.URLDecoder
import play.api.libs.Files.TemporaryFile
import org.www.play.rdf.IterateeSelector
import org.w3.banana.plantain.Plantain
import play.api.http.Status._
import play.api.libs.iteratee.Enumerator
import org.w3.banana.ldp.AccessDenied
import org.w3.banana.ldp.WrongTypeException
import scala.Some
import play.api.mvc.SimpleResult
import org.w3.banana.ldp.ParentDoesNotExist
import play.api.mvc.ResponseHeader

object Method extends Enumeration {
  val read = Value
  val write = Value
}

/**
 * ReadWriteWeb Controller for Play
 */
trait ReadWriteWeb[Rdf <: RDF]{

  def rwwActor: ResourceMgr[Rdf]

  implicit def rwwBodyParser: RwwBodyParser[Rdf]
  implicit def ec: ExecutionContext
  implicit def ops: RDFOps[Rdf]
  implicit def graphWriterSelector: WriterSelector[Rdf#Graph]
  implicit def solutionsWriterSelector: WriterSelector[Rdf#Solutions]
  implicit val boolWriterSelector: WriterSelector[Boolean] = BooleanWriter.selector
  implicit val sparqlUpdateSelector: IterateeSelector[Plantain#UpdateQuery]

  import org.www.readwriteweb.play.PlayWriterBuilder._

  def about = Action {
    Ok( views.html.rww.ldp() )
  }

  def stackTrace(e: Throwable) = {
    val sw = new StringWriter(1024)
    e.printStackTrace(new PrintWriter(sw))
    sw.getBuffer.toString
  }

  //    JenaRDFBlockingWriter.WriterSelector()
  //    req.accept.collectFirst {
  //      case "application/rdf+xml" =>  (writeable(JenaRdfXmlWriter),ContentTypeOf[Jena#Graph](Some("application/rdf+xml")))
  //      case "text/turtle" => (writeable(JenaTurtleWriter), ContentTypeOf[Jena#Graph](Some("text/turtle")))
  //      case m @ SparqlAnswerJson.mime => (writeable(JenaSparqlJSONWriter), ContentTypeOf[JenaSPARQL#Solutions](Some(m)))
  //    }.get

//  meta <- getMeta(ldprUriFull)
//  athzd <- getAuth(meta.acl.get,wac.Read)
//  if athzd.contains()


  def get(path: String) = Action { request =>
    getAsync(request)
  }


  def getAsync(request: play.api.mvc.Request[AnyContent]): AsyncResult = {
    Async {
      val res = for {
        namedRes <- rwwActor.get(request, request.path)
      } yield {
        val link = namedRes.acl map (acl => ("Link" -> s"<${acl}>; rel=acl"))
        namedRes match {
          case ldpr: LDPR[Rdf] =>
            writerFor[Rdf#Graph](request).map {
              wr => {
                import syntax._
                result(200, wr, Map.empty ++ link)(ldpr.relativeGraph)
              }
            } getOrElse {
              play.api.mvc.Results.UnsupportedMediaType("could not find serialiser for Accept types " +
                request.headers.get(play.api.http.HeaderNames.ACCEPT))
            }
          case bin: BinaryResource[Rdf] => {
            SimpleResult(
              header = ResponseHeader(200, Map("Content-Type" -> "todo") ++ link),
              body = bin.reader(1024 * 8)
            )
          }
        }
      }
      res recover {
        case nse: NoSuchElementException => NotFound(nse.getMessage + stackTrace(nse))
        case auth: AccessDenied => Unauthorized(auth.message)
        case e => ExpectationFailed(e.getMessage + "\n" + stackTrace(e))
      }
    }
  }

  def head(path: String) = Action { request =>
    getAsync(request).transform { res =>
    //Todo: this returns a Content-Length of 0, when it should either return none or the exact same as the original
    //see: http://stackoverflow.com/questions/3854842/content-length-header-with-head-requests
        SimpleResult[String](res.header, Enumerator(""))
    }
  }

  /**
   * http://tools.ietf.org/html/rfc4918#section-9.3
   * @param path
   * @return
   */
  def mkcol(path: String) = Action(rwwBodyParser) { request =>
    val correctedPath = if (!request.path.endsWith("/")) request.path else request.path.substring(0,request.path.length-1)
    val pathUri = new java.net.URI(correctedPath)
    val coll = pathUri.resolve(".")
    Async {
      def mk(graph: Option[Rdf#Graph]): Future[SimpleResult[String]] = {
        val path = correctedPath.toString.substring(coll.toString.length)
        for (answer <- rwwActor.makeCollection(request, coll.toString,Some(path), graph))
        yield {
          val res = Created("Created Collection at " + answer)
          if (request.path == correctedPath) res
          else res.withHeaders(("Location" -> answer.toString))
        }
      }
      val res = request.body match {
        case rww: GraphRwwContent[Rdf]  => mk(Some(rww.graph))
        case org.www.readwriteweb.play.emptyContent => mk(None)
        case _ => Future.successful(play.api.mvc.Results.UnsupportedMediaType("We only support RDF media types, for appending to collection."))
      }
      res recover {
        //case ResourceExists(e) => MethodNotAllowed(e) //no longer happens
        case ParentDoesNotExist(e) => Conflict(e)
        case AccessDenied(e) => Forbidden(e)
        case e => InternalServerError(e.toString+"\n"+stackTrace(e))
      }
    }
  }

  def put(path: String) = Action(rwwBodyParser) { request =>
    Async {
      val future = for {
        answer <- rwwActor.put(request, request.body)
      } yield {
        Ok("Succeeded")
      }
      future recover {
        case nse: NoSuchElementException => NotFound(nse.getMessage+stackTrace(nse))
        case e => ExpectationFailed(e.getMessage +"\n"+stackTrace(e))
      }
    }
  }

  def patch(path: String) = Action(rwwBodyParser) { request =>
    Async {
      val future = for {
        _ <- rwwActor.patch(request, request.body)
      } yield {
         Ok("Succeeded")
      }
      future recover {
        case nse: NoSuchElementException => NotFound(nse.getMessage+stackTrace(nse))
        case e => ExpectationFailed(e.getMessage +"\n"+stackTrace(e))
      }
    }
  }


  def post(path: String) = Action(rwwBodyParser) { request =>

    def postGraph(request: play.api.mvc.Request[RwwContent], rwwGraph: Option[Rdf#Graph]): Future[SimpleResult[Results.EmptyContent]] = {
      for {
        location <- rwwActor.postGraph(request,
          request.headers.get("Slug").map(t => URLDecoder.decode(t, "UTF-8")),
          rwwGraph
        )
      } yield {
        Created.withHeaders("Location" -> location.toString)
      }
    }

    Async {
      val future = request.body match {
        case rwwGraph: GraphRwwContent[Rdf] => {
          postGraph(request, Some(rwwGraph.graph))
        }
        case rwwQuery: QueryRwwContent[Rdf] => {
          for {
            answer <- rwwActor.postQuery(request, request.path, rwwQuery)
          } yield {
            answer.fold(
              graph => writerFor[Rdf#Graph](request).map {
                wr => result(200, wr)(graph)
              },
              sol => writerFor[Rdf#Solutions](request).map {
                wr => result(200, wr)(sol)
              },
              bool => writerFor[Boolean](request).map {
                wr => result(200, wr)(bool)
              }
            ).getOrElse(play.api.mvc.Results.UnsupportedMediaType(s"Cannot publish answer of type ${answer.getClass} as" +
              s"one of the mime types given ${request.headers.get("Accept")}"))
          }
        }
        case BinaryRwwContent(file: TemporaryFile, mime: String) => {
          for {
            location <- rwwActor.postBinary(request, request.path,
              request.headers.get("Slug").map(t => URLDecoder.decode(t, "UTF-8")),
              file,
              MimeType(mime))
          } yield {
            Created.withHeaders("Location" -> location.toString)
          }
        }
        case emptyContent => {
          postGraph(request, None)
        }
        //        case _ => Ok("received content")
      }
      future recover {
        case nse: NoSuchElementException => NotFound(nse.getMessage + stackTrace(nse))
        case e : WrongTypeException =>
          //todo: the Allow methods should not be hardcoded.
          SimpleResult(
          ResponseHeader(METHOD_NOT_ALLOWED,Map("Allow"->"GET, OPTIONS, HEAD, PUT, PATCH")),
          Enumerator(e.msg)
        )
        case e => ExpectationFailed(e.getMessage + "\n" + stackTrace(e))
      }
    }
  }

  def delete(path: String) = Action { request =>
    Async {
      val future = for {
        _ <- rwwActor.delete(request)
      } yield {
        Ok
      }
      future recover {
        case nse: NoSuchElementException => NotFound(nse.getMessage+stackTrace(nse))
        case e => ExpectationFailed(e.getMessage+"\n"+stackTrace(e))
      }
    }
  }
}







