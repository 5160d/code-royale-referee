package com.codingame.game

import com.codingame.game.Constants.WORLD_HEIGHT
import com.codingame.game.Constants.WORLD_WIDTH
import com.codingame.game.Constants.OBSTACLE_GAP
import com.codingame.game.Constants.OBSTACLE_MINERAL_RANGE
import com.codingame.game.Constants.OBSTACLE_MINERAL_BASERATE_RANGE
import com.codingame.game.Constants.OBSTACLE_MINERAL_INCREASE
import com.codingame.game.Constants.OBSTACLE_MINERAL_INCREASE_DISTANCE_1
import com.codingame.game.Constants.OBSTACLE_MINERAL_INCREASE_DISTANCE_2
import com.codingame.game.Constants.OBSTACLE_PAIRS
import com.codingame.game.Constants.TOWER_HP_INCREMENT
import com.codingame.game.Constants.TOWER_HP_INITIAL
import com.codingame.game.Constants.TOWER_HP_MAXIMUM
import com.codingame.gameengine.core.AbstractPlayer
import com.codingame.gameengine.core.AbstractReferee
import com.codingame.gameengine.core.GameManager
import com.codingame.gameengine.module.entities.GraphicEntityModule
import tooltipModule.TooltipModule
import com.google.inject.Inject
import java.util.*

lateinit var theRandom: Random

@Suppress("unused")  // injected by magic
class Referee : AbstractReferee() {
  @Inject private lateinit var gameManager: GameManager<Player>
  @Inject private lateinit var entityManager: GraphicEntityModule
  @Inject private lateinit var tooltipModule: TooltipModule

  private var obstacles: List<Obstacle> = listOf()

  private fun allUnits(): List<MyEntity> = gameManager.players.flatMap { it.allUnits() } + obstacles

  override fun init(params: Properties): Properties {
    theRandom = (params["seed"] as? Long)?.let { Random(it) } ?: Random()

    theEntityManager = entityManager
    theTooltipModule = tooltipModule
    theGameManager = gameManager

    gameManager.players[0].enemyPlayer = gameManager.players[1]
    gameManager.players[1].enemyPlayer = gameManager.players[0]
    gameManager.players[1].isSecondPlayer = true

    loop@ do {
      nextObstacleId = 0

      val obstaclePairs = (1 until OBSTACLE_PAIRS).map {
        val rate = OBSTACLE_MINERAL_BASERATE_RANGE.sample()
        val resources = OBSTACLE_MINERAL_RANGE.sample()
        Pair(Obstacle(rate, resources), Obstacle(rate, resources))
      }
      obstacles = obstaclePairs.flatMap { listOf(it.first, it.second) }

      for (iter in 1..100) {
        obstaclePairs.forEach { (o1, o2) ->
          val mid = (o1.location + Vector2(WORLD_WIDTH -o2.location.x, WORLD_HEIGHT -o2.location.y)) / 2.0
          o1.location = mid
          o2.location = Vector2(WORLD_WIDTH -mid.x, WORLD_HEIGHT -mid.y)
          o2.radius = o1.radius
        }
        if (!fixCollisions(OBSTACLE_GAP.toDouble(), obstacles, dontLoop = true)) break@loop
      }
      obstacles.forEach { it.destroy() }
      System.err.println("abandoning")
    } while (true)

    val mapCenter = Vector2(viewportX.length / 2, viewportY.length / 2)
    obstacles.forEach {
      if (it.location.distanceTo(mapCenter) < OBSTACLE_MINERAL_INCREASE_DISTANCE_1) { it.maxMineralRate++; it.minerals += OBSTACLE_MINERAL_INCREASE }
      if (it.location.distanceTo(mapCenter) < OBSTACLE_MINERAL_INCREASE_DISTANCE_2) { it.maxMineralRate++; it.minerals += OBSTACLE_MINERAL_INCREASE }
      it.updateEntities()
    }

    for ((activePlayer, invert) in gameManager.activePlayers.zip(listOf(false, true))) {
      val spawnDistance = 200
      val corner = if (invert)
        Vector2(WORLD_WIDTH - spawnDistance, WORLD_HEIGHT - spawnDistance)
      else
        Vector2(spawnDistance, spawnDistance)

      activePlayer.queenUnit = Queen(activePlayer).also { it.location = corner }
    }

    fixCollisions()

    gameManager.activePlayers.forEach { it.hud.update() }

    gameManager.activePlayers.forEach { player ->
      player.sendInputLine(obstacles.size.toString())
      obstacles.forEach { player.printObstacleInit(it) }
    }

    // Params contains all the game parameters that has been to generate this game
    // For instance, it can be a seed number, the size of a grid/map, ...
    return params
  }

  /**
   * @return true if there is a correction
   */
  private fun fixCollisions(acceptableGap: Double = 0.0, entities: List<MyEntity>? = null, dontLoop: Boolean = false): Boolean {
    val allUnits = entities ?: allUnits()
    var foundAny = false

    for (iter in 0..999) {
      var loopAgain = false

      for (u1 in allUnits) {
        val rad = u1.radius.toDouble()
        val clampDist = if (u1.mass == 0) OBSTACLE_GAP + rad else rad
        u1.location = u1.location.clampWithin(clampDist, WORLD_WIDTH -clampDist, clampDist, WORLD_HEIGHT -clampDist)

        for (u2 in allUnits) {
          if (u1 != u2) {
            val overlap = u1.radius + u2.radius + acceptableGap - u1.location.distanceTo(u2.location)
            if (overlap > 1e-6) {
              val (d1, d2) = when {
                u1.mass == 0 && u2.mass == 0 -> Pair(0.5, 0.5)
                u1.mass == 0 -> Pair(0.0, 1.0)
                u2.mass == 0 -> Pair(1.0, 0.0)
                else -> Pair(u2.mass.toDouble() / (u1.mass + u2.mass), u1.mass.toDouble() / (u1.mass + u2.mass))
              }

              val u1tou2 = u2.location - u1.location
              val gap = if (u1.mass == 0 && u2.mass == 0) 20.0 else 1.0

              u1.location -= u1tou2.resizedTo(d1 * overlap + if (u1.mass == 0 && u2.mass > 0) 0.0 else gap)
              u2.location += u1tou2.resizedTo(d2 * overlap + if (u2.mass == 0 && u1.mass > 0) 0.0 else gap)

              loopAgain = true
              foundAny = true
            }
          }
        }
      }
      if (dontLoop || !loopAgain) break
    }
    return foundAny
  }

  private fun sendGameStates() {
    for (activePlayer in gameManager.activePlayers) {
      activePlayer.sendInputLine("${activePlayer.queenUnit.location.x.toInt()} ${activePlayer.queenUnit.location.y.toInt()} ${activePlayer.health} ${activePlayer.resources}")
      activePlayer.sendInputLine("${activePlayer.enemyPlayer.queenUnit.location.x.toInt()} ${activePlayer.enemyPlayer.queenUnit.location.y.toInt()} ${activePlayer.enemyPlayer.health}")
      obstacles.forEach { activePlayer.printObstaclePerTurn(it) }

      for (player in listOf(activePlayer, activePlayer.enemyPlayer)) {
        activePlayer.sendInputLine(player.activeCreeps.size.toString())
        player.activeCreeps.forEach {
          activePlayer.sendInputLine("${it.location.x.toInt()} ${it.location.y.toInt()} ${it.health} ${it.creepType.ordinal}")
        }
      }
      activePlayer.execute()
    }
  }

  private fun processPlayerActions() {
    val obstaclesAttemptedToBuildUpon = mutableListOf<Obstacle>()
    val scheduledBuildings = mutableListOf<Pair<Player, ()->Unit>>()
    class PlayerInputException(message: String): Exception(message)
    class PlayerInputWarning(message: String): Exception(message)

    fun scheduleBuilding(player: Player, obs: Obstacle, strucType: String) {
      val struc = obs.structure
      if (struc?.owner == player.enemyPlayer) throw PlayerInputWarning("Cannot build: owned by enemy player")
      if (struc is Barracks && struc.owner == player && struc.progress > 0)
        throw PlayerInputWarning("Cannot rebuild: training is in progress")

      obstaclesAttemptedToBuildUpon += obs
      val toks = strucType.split('-').iterator()

      scheduledBuildings += player to {
        if (!toks.hasNext()) throw PlayerInputException("Structure type must be specified")
        val firstToken = toks.next()
        when (firstToken) {
          "MINE" ->
            if (struc is Mine) {
              struc.incomeRate++
              if (struc.incomeRate > obs.maxMineralRate) struc.incomeRate = obs.maxMineralRate
            } else {
              obs.setMine(player)
            }
          "TOWER" -> {
            if (struc is Tower) {
              struc.health += TOWER_HP_INCREMENT
              if (struc.health > TOWER_HP_MAXIMUM) struc.health = TOWER_HP_MAXIMUM
            } else {
              obs.setTower(player, TOWER_HP_INITIAL)
            }
          }
          "BARRACKS" -> {
            if (!toks.hasNext()) throw PlayerInputException("BARRACKS type must be specified")
            val creepInputType = toks.next()
            val creepType = try {
              CreepType.valueOf(creepInputType)
            } catch (e:Exception) {
              throw PlayerInputException("Invalid BARRACKS type: $creepInputType")
            }
            obs.setBarracks(player, creepType)
          }
          else -> throw PlayerInputException("Invalid structure type: $firstToken")
        }
      }
    }

    playerLoop@ for (player in gameManager.activePlayers) {
      val queen = player.queenUnit
      try {
        try {

          // Process building creeps
          val buildingBarracks = player.outputs[1].split(" ").drop(1)
            .map { obsIdStr -> obsIdStr.toIntOrNull() ?: throw PlayerInputException("Couldn't process obstacleId: $obsIdStr") }
            .map { obsId -> obstacles.find { it.obstacleId == obsId } ?: throw PlayerInputException("No obstacle with id = $obsId") }
            .map { obs ->
              val struc = obs.structure as? Barracks ?: throw PlayerInputWarning("Cannot spawn from ${obs.obstacleId}: not a barracks")
              if (struc.owner != player) throw PlayerInputWarning("Cannot spawn from ${obs.obstacleId}: not owned")
              if (struc.isTraining) throw PlayerInputWarning("Barracks ${obs.obstacleId} is training")
              struc
            }

          val sum = buildingBarracks.sumBy { it.creepType.cost }
          if (sum > player.resources) throw PlayerInputWarning("Training too many creeps ($sum total resources requested)")

          player.resources -= sum
          buildingBarracks.forEach { barracks ->
            barracks.progress = 0
            barracks.isTraining = true
            barracks.onComplete = {
              repeat(barracks.creepType.count) {
                player.activeCreeps += when (barracks.creepType) {
                  CreepType.MELEE ->
                    QueenChasingCreep(barracks.owner, barracks.creepType)
                  CreepType.RANGED ->
                    AutoAttackCreep(barracks.owner, barracks.creepType)
                  CreepType.GIANT ->
                    TowerBustingCreep(barracks.owner, barracks.creepType, obstacles)
                }.also {
                  it.location = barracks.obstacle.location
                  it.finalizeFrame()
                  it.location = barracks.obstacle.location.towards(barracks.owner.enemyPlayer.queenUnit.location, 20.0)
                  it.finalizeFrame()
                }
              }
            }
          }
        }
        catch (e: PlayerInputWarning) {
          gameManager.addToGameSummary("WARNING: ${e.message}")
        }

        // Process queen command
        try {
          val line = player.outputs[0]
          val toks = line.split(" ").iterator()
          val command = toks.next()

          when (command) {
            "WAIT" -> {
            }
            "MOVE" -> {
              try {
                val x = toks.next().toInt()
                val y = toks.next().toInt()
                queen.moveTowards(Vector2(x, y))
              } catch (e: Exception) {
                throw PlayerInputException("In MOVE command, x and y must be integers")
              }
            }
            "BUILD" -> {
              val obsId = try { toks.next().toInt() } catch (e:Exception) { throw PlayerInputException("Could not parse obstacleId")}
              val obs = obstacles.find { it.obstacleId == obsId } ?: throw PlayerInputException("ObstacleId $obsId does not exist")
              val strucType = toks.next()

              val dist = obs.location.distanceTo(queen.location) - queen.radius - obs.radius
              if (dist > 10) {
                queen.moveTowards(obs.location)
              } else {
                scheduleBuilding(player, obs, strucType)
              }
            }
            else -> throw PlayerInputException("Didn't understand command: $command")
          }
        } catch (e: PlayerInputWarning) {
          gameManager.addToGameSummary("WARNING: ${e.message}")
        }
      } catch (e: AbstractPlayer.TimeoutException) {
        e.printStackTrace()
        player.deactivate("Timeout!")
      } catch (e: PlayerInputException) {
        System.err.println("WARNING: Terminating ${player.nicknameToken}, because of:")
        e.printStackTrace()
        player.deactivate("${e.message}")
      }
    }

    // If they're both building onto the same one, then actually build neither
    if (obstaclesAttemptedToBuildUpon.size == 2 && obstaclesAttemptedToBuildUpon[0] == obstaclesAttemptedToBuildUpon[1])
      scheduledBuildings.clear()

    // Execute builds that remain
    scheduledBuildings.forEach { (player: Player, callback: () -> Unit) ->
      try { callback.invoke() }
      catch (e: PlayerInputException) {
        System.err.println("WARNING: Deactivating ${player.nicknameToken} because of:")
        e.printStackTrace()
        player.deactivate("${e.message}")
      }
    }
  }

  private fun processCreeps() {
    val allCreeps = gameManager.activePlayers.flatMap { it.activeCreeps }.toList()
    allCreeps.forEach { it.damage(1) }
    repeat(5) {
      allCreeps.forEach { it.move(1.0 / 5) }
      fixCollisions(dontLoop = true)
    }
    allCreeps.forEach { it.dealDamage() }

    // Tear down enemy mines
    allCreeps.forEach { creep ->
      val closestObstacle = obstacles.minBy { it.location.distanceTo(creep.location) }!!
      if (closestObstacle.location.distanceTo(creep.location) - closestObstacle.radius - creep.radius > 5) return@forEach
      val struc = closestObstacle.structure
      if (struc is Mine && struc.owner != creep.owner) closestObstacle.structure = null
    }

    allCreeps.forEach { it.finalizeFrame() }

    // Queens tear down enemy structures (not TOWERs)
    gameManager.activePlayers.forEach {
      val queen = it.queenUnit
      val closestObstacle = obstacles.minBy { it.location.distanceTo(queen.location) }!!
      if (closestObstacle.location.distanceTo(queen.location) - closestObstacle.radius - queen.radius > 5) return@forEach
      val struc = closestObstacle.structure
      if ((struc is Mine || struc is Barracks) && struc.owner != queen.owner) closestObstacle.structure = null
    }
  }

  override fun gameTurn(turn: Int) {
    gameManager.activePlayers.forEach { it.resourcesPerTurn = 0 }

    sendGameStates()
    processPlayerActions()
    processCreeps()

    // Process structures
    obstacles.forEach { it.act() }

    // Check end game
    gameManager.activePlayers.forEach { it.checkQueenHealth() }
    if (gameManager.activePlayers.size < 2) gameManager.endGame()
  }
}

