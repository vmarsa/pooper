package controllers

import akka.pattern.ask
import play.api._
import play.api.mvc._
import play.libs.Akka
import akka.actor.Props
import actors._
import akka.util.Timeout
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.ExecutionContext.Implicits.global


import scala.concurrent.duration._
import actors.StatusResp

object Application extends Controller {

  val master = Akka.system.actorOf(Props[Master], "master")

  implicit val timeout = Timeout(10 seconds)

  def index = {
    Action.async{
      (master ? StatusReq).mapTo[StatusResp].map(msg => Ok(views.html.index(msg)))
    }
  }

  def upload = Action(parse.multipartFormData) { request =>
    request.body.file("emailsFile").map { file =>
      import java.io.File
      val filename = file.filename
      val contentType = file.contentType
      file.ref.moveTo(new File("/tmp/emails.csv"))
      Ok(views.html.redirect())
    }.getOrElse {
      Ok("Error file")
    }
  }

  def launch = Action {
    Logger.info("Launching!")
    master ! Launch
    Ok(views.html.redirect())
  }

  def get = Action {
    Ok.sendFile(new java.io.File("result.csv"))
  }

  def cooldown(num : Int) = Action.async{
    (master ? SetCooldown(num)).mapTo[StatusResp].map(msg => Ok(views.html.index(msg)))
  }

  def pause(num : Int) = Action.async{
    (master ? SetPause(num)).mapTo[StatusResp].map(msg => Ok(views.html.index(msg)))
  }

}