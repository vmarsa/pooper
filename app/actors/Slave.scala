package actors

import akka.actor.{ActorRef, Actor}
import akka.actor.Actor.Receive
import play.api.libs.ws.WS
import scala.util.{Failure, Success}
import play.api.Logger

import scala.concurrent.ExecutionContext.Implicits.global

class Slave extends Actor {
  val url = "http://e.mail.ru/api/v1/user/password/restore"
  val mrimUrl = "http://e.mail.ru/api/v1/user/access/support"

  val userAgent = "Mozilla/5.0 (Windows; U; Windows NT 6.0; ru; rv:1.9.1b4pre) Gecko/20090419 SeaMonkey/2.0b1pre"

  override def receive: Receive = {
    case Ask(method, email) => {
      Logger.info("Ask "+method.id+" "+email)
      val s = sender
      call(method, s, email)
    }
  }

  def call(method: Method, s: ActorRef, email: String) {
    val methodUrl = method match {
      case Recovery => url
      case Access => mrimUrl
    }
    val result = WS.url(methodUrl).withHeaders("User-Agent" -> userAgent)
      .withQueryString(("ajax_call","1"),("x-email",""),("htmlencoded","false"),("api","1"),("token",""),("email",email)).post("")
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
}

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

