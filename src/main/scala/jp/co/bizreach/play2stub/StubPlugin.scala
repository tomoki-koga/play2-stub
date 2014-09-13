package jp.co.bizreach.play2stub

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import jp.co.bizreach.play2stub.RoutesCompiler.{Comment, Include, Route}
import org.apache.commons.io.{FilenameUtils, FileUtils}
import play.api.Play._
import play.api.mvc.{Result, RequestHeader}
import play.api.{Configuration, Logger, Application, Plugin}
import play.core.Router.RouteParams
import scala.collection.JavaConverters._


class StubPlugin(app: Application) extends Plugin {

  private lazy val logger = Logger("jp.co.bizreach.play2stub.StubPlugin")
  private val basePath = "play2stub"

  val engineConf = app.configuration.getString(basePath + ".engine").getOrElse("hbs")
  val dataPathConf = app.configuration.getString(basePath + ".data-root").getOrElse("/app/data")

  trait RouteHolder {
    val routes: Seq[StubRouteConfig]
    val engine: String = engineConf
    val dataPath: String = dataPathConf
  }


  lazy val holder = new RouteHolder {

    // TODO  Load filters

    private val routeList =
      current.configuration.getConfigList(basePath + ".routes")
        .map(_.asScala).getOrElse(Seq.empty)
    
    override val routes = routeList.map{ route =>
      val path = route.subKeys.mkString

      route.getConfig(path).map { inner =>
        StubRouteConfig(
          route = parseRoute(path.replace("~", ":")),
          template = toTemplate(inner),
          data = inner.getString("data"),
          status = inner.getInt("status"),
          headers = toMap(inner.getConfig("headers")),
          params = toMap(inner.getConfig("params"))
        )
      }.get
    }
  }


  private def parseRoute(path: String): Route = {
    RoutesCompiler.parse(path) match {
      case Right(r: Route) => r
      case Right(unexpected) =>
        throw new RuntimeException(unexpected.toString)
      case Left(err) =>
        throw new RuntimeException(err)
    }
  }

  private def toTemplate(inner: Configuration): Option[Template] =
    try {
      inner.getConfig("template").map(c =>
        Template(
          c.getString("path").getOrElse(""),
          c.getString("engine").getOrElse(holder.engine)))

    } catch {
      case ex:Throwable =>
        // TODO again
        //case ex: ConfigException.WrongType =>
        //inner.getString("template").map(s => Template(s, holder.engine))
        None
    }


  private def toMap(conf: Option[Configuration]): Map[String, String] =
    conf.map(_.entrySet
      .map(e => e._1 -> e._2.render()))
      .getOrElse(Map.empty).toMap


  override def onStart(): Unit = {
    current.configuration
    // Load application.conf

    holder
  }



  override def onStop(): Unit = super.onStop()



  override def enabled: Boolean = super.enabled
}


object Stub {

  def template(params:Map[String, String]) = {}

  def addHeaders(result:Result):Result = {
    result.withHeaders("Content-Type" -> "application/json")
  }


  /**
   *
   *
   */
  def route(request: RequestHeader):Option[StubRoute] =
    config.routes
      .find(conf => conf.route.verb.value == request.method
                 && conf.route.path(request.path).isDefined)
      .map { conf =>
        conf.route.path(request.path).map { groups =>
          StubRoute( 
            conf = conf,
            params = RouteParams(groups, request.queryString), 
            path = request.path)
        }.get
      }
      //.map(r => RouteParams(r.path, request.queryString))


  private[play2stub] def config = current.plugin[StubPlugin].map(_.holder)
    .getOrElse(throw new IllegalStateException("StubPlugin is not installed"))

}


object StubRouteConfig {



  def init(): StubRouteConfig = {

    val configRoot = current.configuration.getConfig("play2stub")


    StubRouteConfig(null)
  }

}


case class StubRoute(
  conf: StubRouteConfig,
  params: RouteParams,
  path: String) {

  def verb = conf.route.verb
  def pathPattern = conf.route.path


  /**
   * Get parameter maps from both url path part and query string part
   */
  def flatParams: Map[String, String] = {
    val fromPath = params.path
      .withFilter(_._2.isRight).map(p => p._1 -> p._2.right.get)
    val fromQuery = params.queryString
      .withFilter(_._2.length > 0).map(q => q._1 -> q._2(0))
    fromPath ++ fromQuery
  }


  /**
   *
   */
  def data(extraParams:Map[String, String] = Map.empty):Option[JsonNode] = {
    val params = flatParams ++ extraParams
    val dataPath = conf.data.getOrElse(path)
    val pathWithExtension = 
      if (FilenameUtils.getExtension(dataPath).isEmpty) dataPath + ".json" 
      else dataPath
    val file = FileUtils.getFile(this.getClass.getResource("/").getPath,
      Stub.config.dataPath, pathWithExtension)
    
    if (file.exists()) {
      val json = new ObjectMapper().readTree(FileUtils.readFileToString(file))
      json match {
        case node:ObjectNode =>
          params.foreach{ case (k, v) => node.put(k, v) }
        case _=>
      }
      Some(json)

    } else if (params.nonEmpty) {
      val node = new ObjectMapper().createObjectNode()
      params.foreach{ case (k, v) => node.put(k, v) }
      Some(node)

    } else
      None
  }

  def template:Template =
    conf.template.getOrElse(Template(path, Stub.config.engine))
}


case class Stub(
  filters: Seq[StubFilter] = Seq.empty,
  routes: Seq[StubRouteConfig] = Seq.empty
                 )

case class StubFilter(
  headers: Map[String, String] = Map.empty
                       )

case class StubRouteConfig(
  route: Route,
  template: Option[Template] = None,
  data: Option[String] = None,
  status: Option[Int] = None,
  headers: Map[String, String] = Map.empty,
  params: Map[String, String] = Map.empty)


case class Template(path:String, engine:String)