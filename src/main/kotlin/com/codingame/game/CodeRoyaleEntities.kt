package com.codingame.game

import com.codingame.gameengine.module.entities.Circle
import com.codingame.gameengine.module.entities.GraphicEntityModule

abstract class MyEntity {
  var location = Vector2.Zero
  abstract val mass: Int   // 0 := immovable
  abstract val entity: Circle

  open fun updateEntity() {
    entity.x = location.x.toInt()
    entity.y = location.y.toInt()
    entity.zIndex = 50
  }
}

abstract class MyOwnedEntity(val owner: Player) : MyEntity()

class Zergling(entityManager: GraphicEntityModule, owner: Player, location: Vector2? = null):
  Creep(entityManager, owner, 80, 0, 10, 400, 30, location, CreepType.ZERGLING)

class Archer(entityManager: GraphicEntityModule, owner: Player, location: Vector2? = null):
  Creep(entityManager, owner, 60, 200, 15, 900, 45, location, CreepType.ARCHER)

class Obstacle(entityManager: GraphicEntityModule): MyEntity() {
  override val mass = 0

  val radius = (Math.random() * 50.0 + 60.0).toInt()
  private val area = Math.PI * radius * radius

  var incomeOwner: Player? = null
  var incomeTimer: Int? = null

  var towerOwner: Player? = null
  var towerAttackRadius: Int = 0
  var towerHealth: Int = 0

  override val entity: Circle = entityManager.createCircle()
    .setRadius(radius)
    .setFillColor(0)
    .setFillAlpha(0.5)
    .setZIndex(100)

  private val towerRangeCircle = entityManager.createCircle()
    .setFillAlpha(0.15)
    .setFillColor(0)
    .setLineColor(0)
    .setZIndex(10)

  private val towerSquare = entityManager.createRectangle()
    .setHeight(30).setWidth(30)
    .setLineColor(0xbbbbbb)
    .setLineWidth(4)
    .setFillColor(0)
    .setZIndex(150)

  fun updateEntities() {
    if (towerOwner != null) {
      towerRangeCircle.isVisible = true
      towerRangeCircle.fillColor = towerOwner!!.colorToken
      towerRangeCircle.radius = towerAttackRadius
      towerSquare.isVisible = true
      towerSquare.fillColor = towerOwner!!.colorToken
    } else {
      entity.fillColor = 0
      towerRangeCircle.isVisible = false
      towerSquare.isVisible = false
    }
    if (incomeOwner == null) entity.fillColor = 0
    else entity.fillColor = incomeOwner!!.colorToken

    towerSquare.x = location.x.toInt() - 15
    towerSquare.y = location.y.toInt() - 15
    towerRangeCircle.x = location.x.toInt()
    towerRangeCircle.y = location.y.toInt()
  }

  fun act() {
    if (towerOwner != null) {
      val closestEnemy = towerOwner!!.enemyPlayer.activeCreeps.minBy { it.location.distanceTo(location) }
      if (closestEnemy != null && closestEnemy.location.distanceTo(location) < towerAttackRadius) {
        closestEnemy.damage(6 + (Math.random() * 3).toInt())
      }

      val enemyGeneral = towerOwner!!.enemyPlayer.generalUnit
      if (enemyGeneral.location.distanceTo(location) < towerAttackRadius) {
        enemyGeneral.location += (enemyGeneral.location - location).resizedTo(20.0)
      }

      towerHealth -= 10
      towerAttackRadius = Math.sqrt((towerHealth * 1000 + area) / Math.PI).toInt()

      if (towerHealth < 0) {
        towerHealth = -1
        towerOwner = null
      }
    }
    if (incomeOwner != null) {
      incomeOwner!!.resources += 1
    }
    updateEntities()
  }

  init {
    this.location = Vector2.random(1920, 1080)
  }

  fun setTower(owner: Player, health: Int) {
    towerOwner = owner
    this.towerHealth = health
  }
}

enum class CreepType {
  ZERGLING,
  ARCHER
}

abstract class Creep(
  entityManager: GraphicEntityModule,
  owner: Player,
  private val speed: Int,
  val attackRange: Int,
  radius: Int,
  override val mass: Int,
  var health: Int,
  location: Vector2?,
  val creepType: CreepType
) : MyOwnedEntity(owner) {

  private val maxHealth = health

  override val entity = entityManager.createCircle()
    .setRadius(radius)
    .setFillColor(owner.colorToken)!!

  fun act() {
    val enemyKing = owner.enemyPlayer.kingUnit
    // move toward enemy king, if not yet in range
    if (location.distanceTo(enemyKing.location) - entity.radius - enemyKing.entity.radius > attackRange)
      location = location.towards((enemyKing.location + (location - enemyKing.location).resizedTo(3.0)), speed.toDouble())
  }

  init {
    this.location = location ?: Vector2.random(1920, 1080)
    @Suppress("LeakingThis") updateEntity()
  }

  override fun updateEntity() {
    super.updateEntity()
    entity.fillAlpha = health.toDouble() / maxHealth * 0.8 + 0.2
  }

  fun damage(hp: Int) {
    health -= hp
    if (health <= 0) {
      entity.isVisible = false
      owner.activeCreeps.remove(this)
    }
  }
}

