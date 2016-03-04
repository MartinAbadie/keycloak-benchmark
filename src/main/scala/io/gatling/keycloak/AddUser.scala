package io.gatling.keycloak

import javax.ws.rs.core.{HttpHeaders, Response}

import akka.actor.ActorDSL._
import akka.actor.ActorRef
import io.gatling.core.action.Interruptable
import io.gatling.core.action.builder.ActionBuilder
import io.gatling.core.config.Protocols
import io.gatling.core.result.writer.DataWriterClient
import io.gatling.core.session.{Expression, Session}
import io.gatling.core.validation.{Success, Validation}
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.representations.idm.{CredentialRepresentation, UserRepresentation}

case class AddUserAttributes(
  requestName: Expression[String],
  realm: Expression[RealmResource],
  username: Expression[String],
  enabled: Expression[Boolean] = _ => Success(true),
  firstName: Option[Expression[String]] = None,
  lastName: Option[Expression[String]] = None,
  email: Option[Expression[String]] = None,
  password: Option[Expression[String]] = None,
  passwordTemporary: Option[Expression[Boolean]] = None,
  save: Option[(Session, String) => Session] = None
) {}

/**
  * @author Radim Vansa &lt;rvansa@redhat.com&gt;
  */
case class AddUserActionBuilder(
                                 attributes: AddUserAttributes
                               ) extends ActionBuilder {
  def newInstance(attributes: AddUserAttributes) = new AddUserActionBuilder(attributes)

  def enabled(enabled: Expression[Boolean]) = newInstance(attributes.copy(enabled = enabled))
  def password(password: Expression[String], temporary: Expression[Boolean]) =
    newInstance(attributes.copy(password = Some(password), passwordTemporary = Some(temporary)))
  def firstName(firstName: Expression[String]) = newInstance(attributes.copy(firstName = Some(firstName)))
  def lastName(lastName: Expression[String]) = newInstance(attributes.copy(lastName = Some(lastName)))
  def email(email: Expression[String]) = newInstance(attributes.copy(email = Some(email)))
  def saveWith[T](save: (Session, String) => Session) = newInstance(attributes.copy(save = Some(save)))

  def saveAs(name: String) = saveWith((session, id) => session.set(name, id))

  override def build(next: ActorRef, protocols: Protocols): ActorRef = {
    actor(actorName("add-user"))(new AddUserAction(attributes, next))
  }
}

class AddUserAction(
                     attributes: AddUserAttributes,
                     val next: ActorRef
                   ) extends Interruptable with ExitOnFailure with DataWriterClient {
  override def executeOrFail(session: Session): Validation[_] = {
    val user = new UserRepresentation
    user.setUsername(attributes.username(session).get)
    user.setEnabled(attributes.enabled(session).get)
    attributes.firstName.map(fn => user.setFirstName(fn(session).get))
    attributes.lastName.map(ln => user.setLastName(ln(session).get))
    attributes.email.map(e => user.setEmail(e(session).get))
    attributes.realm(session).map(realm =>
      Blocking(() =>
        Stopwatch(() => realm.users.create(user))
          .check(response => response.getStatus == 201, response => {
            val status = response.getStatusInfo.toString
            response.close()
            status
          })
          .recordAndStopOnFailure(this, session, attributes.requestName(session).get + ".create-user")
          .onSuccess(response => {
            val id = getUserId(response)
            response.close()
            val newSession = attributes.save.map(s => s(session, id)).getOrElse(session)

            attributes.password.map(password => {
              val credentials = new CredentialRepresentation
              credentials.setType(CredentialRepresentation.PASSWORD)
              credentials.setValue(password(newSession).get)
              attributes.passwordTemporary.map(a => credentials.setTemporary(a(newSession).get))
              Stopwatch(() => realm.users.get(id).resetPassword(credentials))
                .recordAndContinue(this, newSession, attributes.requestName(session).get + ".reset-password")
            }).getOrElse(next ! newSession)
          })
      )
    )
  }

  def getUserId(u: Response): String = {
    val location = u.getHeaderString(HttpHeaders.LOCATION)
    val lastSlash = location.lastIndexOf('/');
    if (lastSlash < 0) null else location.substring(lastSlash + 1)
  }
}
