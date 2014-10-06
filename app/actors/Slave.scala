package actors

import akka.actor.{ActorRef, Actor}
import akka.actor.Actor.Receive
import play.api.libs.ws.WS
import scala.util.{Failure, Success}

import scala.concurrent.ExecutionContext.Implicits.global

class Slave extends Actor {
  val url = "http://e.mail.ru/api/v1/user/password/restore"

  val userAgent = "Mozilla/5.0 (Windows; U; Windows NT 6.0; ru; rv:1.9.1b4pre) Gecko/20090419 SeaMonkey/2.0b1pre"

  override def receive: Receive = {
    case Ask(email) => {
      println("Ask "+email)
      val s = sender
      val result = WS.url(url).withHeaders("User-Agent" -> userAgent)
        .withQueryString(("ajax_call","1"),("x-email",""),("htmlencoded","false"),("api","1"),("token",""),("email",email)).post("")
      result.onComplete({
        case Success(r) => s ! Answer(email, r.status, r.body)
        case Failure(e) =>
      })
    }
  }

  def call(s: ActorRef, email: String) {
    val result = WS.url(url).withHeaders("User-Agent" -> userAgent)
      .withQueryString(("ajax_call","1"),("x-email",""),("htmlencoded","false"),("api","1"),("token",""),("email",email)).post("")
    result.onComplete({
      case Success(r) => s ! Answer(email, r.status, r.body)
      case Failure(e) => {
        println("Send error")
        e.printStackTrace()
        call(s, email)
      }
    })
  }
}

case class Ask(email: String)

case class Answer(email: String, status: Int, body: String) {
  def isBlock = body.contains("status\":403")

  def exists: Boolean = status == 200 && !isBlock && body.contains("status\":200")
}

