package actors

import akka.actor.{Actor, ActorRef}
import akka.actor.Actor.Receive
import play.api.libs.ws.WS
import scala.util.{Failure, Success}
import play.api.{Play, Logger}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.io.Source

trait SlaveHeritage extends Actor {
  val emailReg = """[_a-z0-9-]+(\.[_a-z0-9-]+)*(\.)*@[a-z0-9-]+(\.[a-z0-9-]+)*(\.[a-z]{2,4})""".r
  var userAgents: Iterator[String] = Iterator.empty
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
}

class Slave extends SlaveHeritage {
  val url = "http://e.mail.ru/api/v1/user/password/restore"
  val mrimUrl = "http://e.mail.ru/api/v1/user/access/support"



  def call(method: Method, s: ActorRef, email: String) {
    emailReg.findFirstIn(email.toLowerCase) match {
      case Some(extractedEmail) => {
        Logger.info("Extracted email: "+ extractedEmail)
        val methodUrl = method match {
          case Recovery => url
          case Access => mrimUrl
        }


        //System.setProperty("http.proxyHost", proxy)
        //System.setProperty("http.proxyPort", "80")

        val result = WS.url(methodUrl).withHeaders("User-Agent" -> userAgent)
              .withQueryString(("ajax_call","1"),("x-email",""),("htmlencoded","false"),("api","1"),("token",""),("email",extractedEmail)).post("")

        result.onComplete({
          case Success(r) => {
            s ! Answer(method, email, r.status, r.body)
          }
          case Failure(e) => {
            Logger.error("Send error for email" + email)
            e.printStackTrace()
            call(method, s, email)
          }
        })
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
        val result = WS.url(remoteUrl).withHeaders("User-Agent" -> userAgent).get()

        result.onComplete({
          case Success(r) => {
            s ! Answer(method, email, r.status, r.body)
          }
          case Failure(e) => {
            Logger.error("Send error")
            e.printStackTrace()
            call(method, s, email)
          }
        })
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

case class Answer(method: Method, email: String, status: Int, body: String) {
  def isBlock = body.contains("status\":403")

  def isMrim = body.contains("error\":\"not_available_for_mrim")

  def exists: Boolean = status == 200 && !isBlock && body.contains("status\":200")
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

