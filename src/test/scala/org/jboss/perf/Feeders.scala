package org.jboss.perf

import org.jboss.perf.model.User
import org.jboss.perf.Util._
import org.jboss.perf.util.{Invalidatable, InvalidatableRandomContainer, RandomContainer}

import scala.collection.mutable
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.util.Random

/**
  * @author Radim Vansa &lt;rvansa@redhat.com&gt;
  */
object Feeders {

  val inactiveUsers = generateCredentials(Options.totalUsers - Options.activeUsers, false)
  val activeUsers = generateCredentials(Options.activeUsers, true)
  val totalUsers = random.shuffle(activeUsers ++ inactiveUsers)

  def generateCredentials(count: Int, active: Boolean): IndexedSeq[User] = {
    Range(0, count).map(_ => User(randomString(Options.usernameLength, Util.random),
      randomString(Options.passwordLength, Util.random), null, active, randomRoles(Util.random)))
  }

  val activeUsersContainer = new RandomContainer[Map[String, String]](activeUsers
    // this driver node will use only a subset of users
    .filter(u => math.abs(u.username.hashCode) % Options.drivers.size == Options.driver)
    .map(u => addState(u.toMap, random)))
  val executingUsersContainer = new InvalidatableRandomContainer[Map[String, String]](Options.activeUsers)

  def borrowUser(): Invalidatable[Map[String, String]] = {
    val user = activeUsersContainer.removeRandom(ThreadLocalRandom.current())
    if (user != null) {
      executingUsersContainer.add(user)
    } else {
      printf("no users, %d available %d executing%n", activeUsersContainer.size(), executingUsersContainer.size())
      null
    }
  }

  def returnUser(user: Invalidatable[Map[String, String]]): Unit = {
    if (user.invalidate()) {
      activeUsersContainer += user()
    }
  }

  def addUser(username : String, password: String, id : String): Unit = {
    val user: User = new User(username, password, id, true, randomRoles())
    activeUsersContainer += addState(user.toMap)
  }

  def removeUser(): User = {
    val random: Random = ThreadLocalRandom.current()
    val activeSize = activeUsersContainer.size()
    val executingSize = executingUsersContainer.size()
    if (random.nextInt(100) == 0) {
      // just debug
      println(s"${activeSize} active, ${executingSize} executing")
    }
    val allUsers: Int = activeSize + executingSize
    if (allUsers <= 0) {
      return null
    }
    val map = if (random.nextInt(allUsers) < activeSize) {
      activeUsersContainer.removeRandom(random)
    } else {
      // invalidation happens automatically during removal, so user can't add it to active
      executingUsersContainer.removeRandom(random)()
    }
    if (map != null) {
      new User(map)
    } else null
  }

  private def addState(map : Map[String, String], rand: Random = ThreadLocalRandom.current()) = {
    map.updated("state", randomUUID(rand))
  }

  def randomRoles(rand: Random = ThreadLocalRandom.current()) = {
    val roles = mutable.Buffer[String]()
    for (i <- 0 until Options.userRolesPerUser) {
      roles += ("user_role_" + random.nextInt(Options.userRoles))
    }
    roles.toList
  }
}
