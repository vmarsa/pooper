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
      (master ? StatusReq).mapTo[actors.Status].map(response _)
    }
  }

  def upload = Action(parse.multipartFormData) { request =>
    request.body.file("emailsFile").map { file =>
      import java.io.File
      val filename = file.filename
      val contentType = file.contentType
      val id = RequestPath.generateId
      file.ref.moveTo(new File(RequestPath.requestPath(id)), true)

      master ! Launch(id)
      Ok(views.html.redirect())
    }.getOrElse {
      Ok("Error file")
    }
  }

  def get(id: String) = Action {
    Ok.sendFile(new java.io.File(RequestPath.resultPath(id)))
  }

  def cooldown(num : Int) = Action{
    master ! SetCooldown(num)
    Ok(views.html.redirect())
  }

  def pause(num : Int) = Action{
    master ! SetPause(num)
    Ok(views.html.redirect())
  }

  def response(status: actors.Status) = Ok(status match {
    case Initial => views.html.upload()
    case s: StatusResp => views.html.index(s)
    case f:Finished => views.html.finished(f)
  })

  def list = Action {
    val requests = RequestPath.lastIds
    Ok(views.html.list(requests))
  }

}