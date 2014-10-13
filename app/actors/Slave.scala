package actors

import akka.actor.{Actor, ActorRef}
import akka.actor.Actor.Receive
import play.api.libs.ws.{Response, WS}
import scala.util.{Failure, Success}
import play.api.{Play, Logger}
import java.net._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.io.Source
import scala.concurrent.Future
import play.api.mvc.Request
import com.ning.http.client.{ProxyServer, AsyncHttpClientConfig}
import javax.net.ssl.{SSLContext, X509TrustManager, TrustManager, HttpsURLConnection}
import sun.net.www.protocol.https.HttpsURLConnectionImpl
import java.io.{InputStreamReader, BufferedReader, OutputStreamWriter}
import java.security.cert.X509Certificate
import java.security.SecureRandom
import java.util.Date

case class Resp(status: Int, body: String)

trait SlaveHeritage extends Actor {
  val emailReg = """[_a-z0-9-]+(\.[_a-z0-9-]+)*(\.)*@[a-z0-9-]+(\.[a-z0-9-]+)*(\.[a-z]{2,4})""".r
  var userAgents: Iterator[String] = Iterator.empty
  val bodyExtractor = """\{"body.*"htmlencoded":false\}""".r
  import play.api.Play.current
  def userAgent = {
    if(!userAgents.hasNext) userAgents = Source.fromInputStream(Play.classloader.getResourceAsStream("userAgents.csv")).getLines()
    userAgents.next()
  }

  override def receive: Actor.Receive = {
    case Ready => sender ! Next
    case Ask(method, email) => {
      Logger.info("Ask "+method.id+" "+email)
      val s = sender
      call(method, s, email)
    }
  }

  def call(method: Method, sender: ActorRef, email: String)

  def processResult(result: Future[Resp], s: ActorRef, email: String, method: Method) = result.onComplete({
    case Success(r) => {
      val body = bodyExtractor.findFirstIn(r.body)
      if (r.body.contains("status\":403")) s ! BlockAnswer(method, email)
      else if (r.body.contains("error\":\"not_available_for_mrim")) s ! MrimAnswer(email)
      else if (r.body.contains("\"body\":{\"retry_after\"")) s ! BlockAnswer(method, email)
      else
        body match {
          case Some(b) => s ! Answer(method, email, r.status, b)
          case None => {
            Logger.error("UNEXTRACTED BODY")
            s ! GiveAnotherSlave(email)
          }
        }
    }
    case Failure(e) => {
      Logger.error("Send error")
      e.printStackTrace()
      s ! GiveAnotherSlave(email)
    }
  })
}

object MailRuUrls {
  val url = "http://e.mail.ru/api/v1/user/password/restore"
  val mrimUrl = "http://e.mail.ru/api/v1/user/access/support"
}

class Slave extends SlaveHeritage {




  def call(method: Method, s: ActorRef, email: String) {
    emailReg.findFirstIn(email.toLowerCase) match {
      case Some(extractedEmail) => {
        Logger.info("Extracted email: "+ extractedEmail)
        val methodUrl = method match {
          case Recovery => MailRuUrls.url
          case Access => MailRuUrls.mrimUrl
        }

        val result = WS.url(methodUrl).withHeaders("User-Agent" -> userAgent)
              .withQueryString(("ajax_call","1"),("x-email",""),("htmlencoded","false"),("api","1"),("token",""),("email",extractedEmail)).post("")

        processResult(result.map(r => Resp(r.status, r.body)), s, email, method)
      }
      case None => {
        Logger.info("Can't extract email")
        s ! Answer(method, email, 904, "can't parse string")
      }
    }


  }
}

class RemoteSlave(remoteUrl: String) extends SlaveHeritage {
  def call(method: Method, s: ActorRef, email: String) {
    emailReg.findFirstIn(email.toLowerCase) match {
      case Some(extractedEmail) => {
        Logger.info("Extracted email: "+ extractedEmail)
        val methodUrl = method match {
          case Recovery => remoteUrl +"?email="+extractedEmail
          case Access => remoteUrl + "?mrim=1&email="+extractedEmail
        }
        val result = WS.url(methodUrl).withHeaders("User-Agent" -> userAgent).get()

        processResult(result.map(r => Resp(r.status, r.body)), s, email, method)
      }
      case None => {
        Logger.info("Can't extract email")
        s ! Answer(method, email, 904, "can't parse string")
      }
    }
  }
}

case object Ready

case class Ask(method: Method, email: String)

case class GiveAnotherSlave(email: String)

case class MrimAnswer(email: String)

case class BlockAnswer(method: Method, email: String)

case class Answer(method: Method, email: String, status: Int, body: String) {
  def notExists: Boolean = body.contains("\"error\":\"not_exists\"")
  def exists: Boolean = status == 200 && body.contains("status\":200")
}

sealed trait Method {
  def id: String
}

case object Recovery extends Method {
  val id = "recovery"
}
case object Access extends Method {
  val id = "access"
}

class ProxySlave(proxy: String) extends SlaveHeritage {

  val trustAllCerts = List[TrustManager](new X509TrustManager(){

    override def getAcceptedIssuers: Array[X509Certificate] = List[X509Certificate]().toArray

    override def checkClientTrusted(p1: Array[X509Certificate], p2: String): Unit = {}

    override def checkServerTrusted(p1: Array[X509Certificate], p2: String): Unit = {}

  }).toArray

  // Install the all-trusting trust manager
  try {
    val sc = SSLContext.getInstance("TLS");
    sc.init(null, trustAllCerts, new SecureRandom());
    HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
  } catch {
    case e => {}
  }

  def call(method: Method, s: ActorRef, email: String) {
    emailReg.findFirstIn(email.toLowerCase) match {
      case Some(extractedEmail) => {
        Logger.info("Extracted email: "+ extractedEmail)
        val result = Future {
          val host = proxy.split(":")(0)
          val port = proxy.split(":")(1).toInt

          val proxyAddress = new InetSocketAddress(host, port)
          val javaProxy = new Proxy(Proxy.Type.HTTP, proxyAddress)

          val methodUrl = (method match {
            case Recovery => MailRuUrls.url
            case Access => MailRuUrls.mrimUrl
          }).replaceAll("http:","https:")

          val connection = new URL(methodUrl).openConnection(javaProxy).asInstanceOf[HttpsURLConnection]

          connection.setDoOutput(true)
          connection.setDoInput(true)
          connection.setRequestMethod("POST")
          connection.setRequestProperty("User-Agent", userAgent)
          val out = connection.getOutputStream()
          val owriter = new OutputStreamWriter(out)

          owriter.write("ajax_call=1&x-email=&htmlencoded=false&api=1&token=&email=" + extractedEmail);
          owriter.flush()
          owriter.close()

          val in = new BufferedReader(
            new InputStreamReader(connection.getInputStream))
          val response = new StringBuffer

          while (in.readLine != null) {
            response.append(in.readLine)
          }
          val r = response.toString
          println(r)
          Resp(200, r)
        }
        processResult(result, s, email, method)
      }
      case None => {
        Logger.info("Can't extract email")
        s ! Answer(method, email, 904, "can't parse string")
      }
    }
  }
}

