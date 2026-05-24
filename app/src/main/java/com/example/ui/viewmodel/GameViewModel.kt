package com.example.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.database.GameDatabase
import com.example.data.database.GameSaveEntity
import com.example.data.model.*
import com.example.data.repository.GameRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class GameViewModel(context: Context) : ViewModel() {

    private val database = GameDatabase.getDatabase(context)
    private val repository = GameRepository(database.gameSaveDao())

    // UI state representing saved variables
    private val _gameSave = MutableStateFlow(GameSaveEntity())
    val gameSave: StateFlow<GameSaveEntity> = _gameSave.asStateFlow()

    // Interactive gameplay running variables
    val particles = mutableStateListOf<Particle>()
    val itemsInWorld = mutableStateListOf<GameItem>()
    val bosses = mutableStateListOf<GameBoss>()

    // Current screen layout overlays
    private val _activeDialogueNode = MutableStateFlow<DialogueNode?>(null)
    val activeDialogueNode: StateFlow<DialogueNode?> = _activeDialogueNode.asStateFlow()

    private val _isCraftMenuOpen = MutableStateFlow(false)
    val isCraftMenuOpen: StateFlow<Boolean> = _isCraftMenuOpen.asStateFlow()

    private val _isCustomizationMenuOpen = MutableStateFlow(false)
    val isCustomizationMenuOpen: StateFlow<Boolean> = _isCustomizationMenuOpen.asStateFlow()

    // HUD Dynamic parameters
    private val _stamina = MutableStateFlow(100f)
    val stamina: StateFlow<Float> = _stamina.asStateFlow()

    private val _playerYOffset = MutableStateFlow(0f) // For vertical jumping
    val playerYOffset: StateFlow<Float> = _playerYOffset.asStateFlow()

    private val _screenShake = MutableStateFlow(0f)
    val screenShake: StateFlow<Float> = _screenShake.asStateFlow()

    // Analog Controls Values
    var joypadX = 0f
    var joypadY = 0f

    // Camera look direction & zoom
    var cameraYaw = 4.2f
    var cameraDistance = 18f
    var cameraHeight = 7f

    // Active weapon stats
    val activeWeaponDamage: Float
        get() = when (_gameSave.value.activeWeapon) {
            "Épée Royale" -> 45f
            "Épée en Fer" -> 25f
            else -> 10f // Rusty or makeshift
        }

    val activeShieldDurability: Float
        get() = when (_gameSave.value.activeShield) {
            "Grand Bouclier d'Acier" -> 500f
            else -> 100f
        }

    // Static obstacles in world to collide with
    val obstacles = listOf(
        Obstacle(-20f, 20f, 6f),
        Obstacle(35f, -15f, 4f),
        Obstacle(10f, 40f, 8f),
        Obstacle(-40f, -30f, 5f),
        Obstacle(0f, 0f, 3f), // Ruins center
        Obstacle(45f, 25f, 5f), // Temple pillars
        Obstacle(35f, 35f, 5f),
        Obstacle(-12f, -38f, 4f) // Lab workshop scrap pile
    )

    data class Obstacle(val x: Float, val z: Float, val radius: Float)

    // Game loop control
    private var gameLoopJob: Job? = null
    var isRunning = true

    // Time of day cycle
    private val _timeOfDay = MutableStateFlow(12f) // 0 to 24 hours
    val timeOfDay: StateFlow<Float> = _timeOfDay.asStateFlow()

    // Upgrades recipes list
    val craftRecipes = listOf(
        CraftRecipe(
            id = "RECIPE_POTION_HEAL",
            name = "Potion de Vitalité Max",
            resultIcon = "🧪",
            resultDescription = "Restaure immédiatement tous vos cœurs de vie.",
            cost = mapOf(ItemType.HERBE to 3),
            outputAction = "POTION_HEAL"
        ),
        CraftRecipe(
            id = "RECIPE_SWORD_IRON",
            name = "Épée forgée en Fer",
            resultIcon = "⚔️",
            resultDescription = "Une épée tranchante qui inflige 25 dégâts physiques.",
            cost = mapOf(ItemType.FER to 5, ItemType.BOIS to 4),
            outputAction = "SWORD_IRON"
        ),
        CraftRecipe(
            id = "RECIPE_SWORD_ROYAL",
            name = "Lame Royale Sacrée",
            resultIcon = "🗡️",
            resultDescription = "L'épée légendaire de la couronne sauvage. Inflige 45 dégâts.",
            cost = mapOf(ItemType.FER to 10, ItemType.BOIS to 8, ItemType.COEUR to 1),
            outputAction = "SWORD_ROYAL"
        ),
        CraftRecipe(
            id = "RECIPE_SHIELD_HEAVY",
            name = "Grand Bouclier d'Acier",
            resultIcon = "🛡️",
            resultDescription = "Bloque n'importe quelle attaque de boss sans broncher.",
            cost = mapOf(ItemType.FER to 6, ItemType.BOIS to 3),
            outputAction = "SHIELD_HEAVY"
        ),
        CraftRecipe(
            id = "RECIPE_VEHICLE_ENGINE",
            name = "Moteur Turbo V8",
            resultIcon = "⚙️",
            resultDescription = "Augmente de manière permanente la vitesse max de votre véhicule.",
            cost = mapOf(ItemType.FER to 8, ItemType.BATTERIE to 2),
            outputAction = "VEHICLE_ENGINE"
        ),
        CraftRecipe(
            id = "RECIPE_VEHICLE_BUMPER",
            name = "Blindage Frontal Renforcé",
            resultIcon = "🧱",
            resultDescription = "Ajoute un pare-buffle en acier lourd. Double les dégâts d'impact du véhicule sur les Boss.",
            cost = mapOf(ItemType.FER to 12, ItemType.BATTERIE to 1),
            outputAction = "VEHICLE_BUMPER"
        )
    )

    // Dialogue nodes DB
    private val dialogueDb = mutableMapOf<String, DialogueNode>()

    // Physical values
    private var playerYVelocity = 0f
    private var vehicleYVelocity = 0f
    private var isPlayerJumping = false
    private var isVehicleJumping = false
    private var vehicleSpeed = 0f
    private var vehicleAngle = 0f

    // Foot physics controls
    var isShieldBlocking = false
    var isAttacking = false
    private var attackCooldownTicks = 0

    init {
        setupItems()
        setupBosses()
        setupDialogues()
        loadSaveData()
        startGameLoop()
    }

    private fun loadSaveData() {
        viewModelScope.launch {
            repository.gameSaveState.collect { entity ->
                if (entity != null) {
                    _gameSave.value = entity
                } else {
                    val initial = repository.createInitialState()
                    _gameSave.value = initial
                }
            }
        }
    }

    private fun triggerSave() {
        viewModelScope.launch {
            repository.saveGame(_gameSave.value)
        }
    }

    private fun setupItems() {
        itemsInWorld.clear()
        // Generate high-fidelity initial positions for materials across the map
        val itemsData = listOf(
            GameItem("h1", ItemType.HERBE, -20f, 0f, 10f),
            GameItem("h2", ItemType.HERBE, 25f, 0f, -25f),
            GameItem("h3", ItemType.HERBE, -5f, 0f, 30f),
            GameItem("h4", ItemType.HERBE, 45f, 0f, 15f),
            GameItem("h5", ItemType.HERBE, -35f, 0f, -15f),
            
            GameItem("f1", ItemType.FER, -45f, 0f, 40f),
            GameItem("f2", ItemType.FER, 35f, 0f, -35f),
            GameItem("f3", ItemType.FER, -15f, 0f, -48f),
            GameItem("f4", ItemType.FER, 15f, 0f, -45f),

            GameItem("w1", ItemType.BOIS, -10f, 0f, 5f),
            GameItem("w2", ItemType.BOIS, -25f, 0f, 45f),
            GameItem("w3", ItemType.BOIS, 40f, 0f, -10f),
            GameItem("w4", ItemType.BOIS, 18f, 0f, 35f),

            GameItem("nc1", ItemType.BATTERIE, 42f, 0f, 38f),  // For quest
            GameItem("nc2", ItemType.BATTERIE, -48f, 0f, -48f) // Spare bonus
        )
        itemsInWorld.addAll(itemsData)
    }

    private fun setupBosses() {
        bosses.clear()
        bosses.add(
            GameBoss(
                id = "BOSS_GOLIATH",
                type = BossType.GOLIATH,
                name = "Goliath de Pierre (Giga-Talus)",
                x = 65f,
                y = 0f,
                z = -60f,
                hp = 300f,
                maxHp = 300f
            )
        )
        bosses.add(
            GameBoss(
                id = "BOSS_SENTINELLE",
                type = BossType.SENTINELLE,
                name = "Sentinelle Fléau Mécanique",
                x = -70f,
                y = 0f,
                z = 70f,
                hp = 450f,
                maxHp = 450f
            )
        )
    }

    private fun setupDialogues() {
        // Gedeon dialogue tree
        dialogueDb["GE_HELLO"] = DialogueNode(
            id = "GE_HELLO",
            npcName = "Gédéon le Mécano",
            text = "Hé bonjour ! Ne mets pas de la boue sur mon moteur ! Tu t'intéresses aux véhicules roulants ? Mon bolide est garé ici, il est libre mais le turbo est mort.",
            options = listOf(
                DialogueOption("Que lui manque-t-il ?", "GE_QUEST_ASK"),
                DialogueOption("Je veux repeindre le véhicule !", "GE_COLOR", action = "PAINT_OPEN"),
                DialogueOption("Au revoir", "CLOSE")
            )
        )

        dialogueDb["GE_QUEST_ASK"] = DialogueNode(
            id = "GE_QUEST_ASK",
            npcName = "Gédéon le Mécano",
            text = "Il me manque une 'Batterie Ancienne' ! Normalement, on peut en déterrer près des ruines du grand Pilier Sacré au Nord-Est (vers x:42, z:38). Rapporte la moi !",
            options = listOf(
                DialogueOption("Je vais la chercher", "GE_QUEST_ACCEPT"),
                DialogueOption("J'en ai déjà une !", "GE_QUEST_CHECK", requiredQuestId = "HAS_BATTERY"),
                DialogueOption("Retour", "GE_HELLO")
            )
        )

        dialogueDb["GE_QUEST_ACCEPT"] = DialogueNode(
            id = "GE_QUEST_ACCEPT",
            npcName = "Gédéon le Mécano",
            text = "Super ! Va au Nord-Est. Fais gaffe aux éboulements rocheux causés par le Goliath de Pierre !",
            options = listOf(DialogueOption("D'accord !", "CLOSE"))
        )

        dialogueDb["GE_QUEST_CHECK"] = DialogueNode(
            id = "GE_QUEST_CHECK",
            npcName = "Gédéon le Mécano",
            text = "Tonnerre mécanique ! Tiens, j'insère la batterie dans le réservoir. Ton véhicule dispose maintenant d'un SÉISM-THRUSTER (Boost de saut ! Appuie sur BOOST en conduisant) !",
            options = listOf(
                DialogueOption("Génial !", "CLOSE", action = "COMPLETE_GE_QUEST")
            )
        )

        dialogueDb["GE_DONE"] = DialogueNode(
            id = "GE_DONE",
            npcName = "Gédéon le Mécano",
            text = "Le véhicule est au top ! Profite pour foncer sur les routes sauvages ! Si tu me rapportes du métal et des batteries sacralisées, on pourra l'améliorer encore plus !",
            options = listOf(
                DialogueOption("Améliorer ou Modifier mon buggy", "GE_HELLO"),
                DialogueOption("Merci Gédéon !", "CLOSE")
            )
        )

        // Archibald dialogue tree
        dialogueDb["AR_HELLO"] = DialogueNode(
            id = "AR_HELLO",
            npcName = "Archibald le Forgeron",
            text = "Bienvenue, apprenti. Le feu crépite pour toi. Si tu as du minerai d'acier brut et du bois pour les manches, je peux concevoir des lames éternelles.",
            options = listOf(
                DialogueOption("Ouvrir la Forge", "AR_CRAFT", action = "CRAFT_OPEN"),
                DialogueOption("Où trouver des matières premières ?", "AR_TIPS"),
                DialogueOption("Fermer l'échoppe", "CLOSE")
            )
        )

        dialogueDb["AR_TIPS"] = DialogueNode(
            id = "AR_TIPS",
            npcName = "Archibald le Forgeron",
            text = "C'est simple : frappe les veines étincelantes de minerai de fer avec une arme pour récolter du métal. Le bois se trouve près des grands bosquets d'arbres ancestraux. Et si tu terrasses le Goliath, tu obtiendras un Cœur de Golem extrêmement rare !",
            options = listOf(
                DialogueOption("Bien, je vois !", "AR_HELLO")
            )
        )

        // Celeste dialogue tree
        dialogueDb["CE_HELLO"] = DialogueNode(
            id = "CE_HELLO",
            npcName = "Céleste l'Astrologue",
            text = "L'alignement des étoiles annonce ton éveil, guerrier. Le monde est infesté par deux fléaux colossaux : le Goliath de Pierre au Sud-Est, et le Méca-Sentinelle au Nord-Ouest.",
            options = listOf(
                DialogueOption("Comment les vaincre ?", "CE_TACTICS"),
                DialogueOption("Qu'arrive-t-il si je les détruis ?", "CE_LEGEND"),
                DialogueOption("Je retourne à l'aventure !", "CLOSE")
            )
        )

        dialogueDb["CE_TACTICS"] = DialogueNode(
            id = "CE_TACTICS",
            npcName = "Céleste l'Astrologue",
            text = "Leur carapace de pierre et d'alliage lourd résiste aux simples coups d'épées rouillées. Ton arme forgée royale peut les abattre. Mais le plus efficace reste de fabriquer un lourd pare-choc blindé sur ton véhicule et de leur foncer dessus à pleine vitesse ! L'inertie physique du choc leur causera d'immenses ravages !",
            options = listOf(
                DialogueOption("Utiliser un bolide en bélier ? Ingénieux !", "CE_HELLO")
            )
        )

        dialogueDb["CE_LEGEND"] = DialogueNode(
            id = "CE_LEGEND",
            npcName = "Céleste l'Astrologue",
            text = "Une fois les deux vaincus, notre terre sauvage retrouvera sa quiétude céleste. Je te conférerai alors la bénédiction supérieure.",
            options = listOf(
                DialogueOption("Je m'y attèle de suite.", "CLOSE")
            )
        )
    }

    private fun startGameLoop() {
        gameLoopJob?.cancel()
        gameLoopJob = viewModelScope.launch {
            while (isRunning) {
                updatePhysics(16 / 1000f)
                updateTimeAndQuest()
                delay(16) // ~60fps ticks
            }
        }
    }

    fun getTerrainHeight(x: Float, z: Float): Float {
        // High fidelity undulating terrain calculations (smooth, immersive hills, paths, valleys, river borders)
        val hill1 = sin(x * 0.08f) * cos(z * 0.08f) * 6f
        val hill2 = cos(sqrt(x * x + z * z) * 0.03f) * 8f
        // Beautiful center gorge / road flat path
        val distToCenterPath = sqrt(x * x + z * z)
        val flatFactor = if (distToCenterPath < 25f) distToCenterPath / 25f else 1.0f
        return (hill1 + hill2) * flatFactor
    }

    private fun updatePhysics(dt: Float) {
        val save = _gameSave.value

        // Decay screen shakes smoothly
        if (_screenShake.value > 0.01f) {
            _screenShake.value *= 0.85f
        } else {
            _screenShake.value = 0f
        }

        // Decay attack animations counts
        if (attackCooldownTicks > 0) {
            attackCooldownTicks--
        } else {
            isAttacking = false
        }

        // Update items respawn
        itemsInWorld.forEach { item ->
            if (item.isPicked) {
                // Heuristic simple timer logic
                val roll = (0..1000).random()
                if (roll < 2) { // 0.2% chance of respawning each tick
                    item.isPicked = false
                }
            }
        }

        // Handle Active Player view
        if (!save.inVehicle) {
            // FOOT TRAVEL MODE MODE
            // Speed factor modified by running and stamina usage
            val runPressed = joypadX * joypadX + joypadY * joypadY > 0.64f
            var finalSpeed = 6.5f // Basic run
            if (runPressed && _stamina.value > 15f) {
                finalSpeed = 11.0f // Sprint
                _stamina.update { (it - 0.4f).coerceAtLeast(0f) }
            } else {
                _stamina.update { (it + 0.3f).coerceAtMost(100f) }
            }

            // Calculate movement headings based on camera coordinates
            val moveAngle = cameraYaw + Math.atan2(joypadX.toDouble(), -joypadY.toDouble()).toFloat()
            val moveMag = sqrt(joypadX * joypadX + joypadY * joypadY).coerceAtMost(1.0f)

            if (moveMag > 0.1f) {
                val dx = sin(moveAngle) * finalSpeed * moveMag * dt
                val dz = -cos(moveAngle) * finalSpeed * moveMag * dt
                save.playerX = (save.playerX + dx).coerceIn(-145f, 145f)
                save.playerZ = (save.playerZ + dz).coerceIn(-145f, 145f)
            } else {
                _stamina.update { (it + 0.5f).coerceAtMost(100f) }
            }

            // Gravity & Jumps for Foot Mode
            if (isPlayerJumping) {
                playerYVelocity -= 18f * dt // gravity
                val newValHeight = _playerYOffset.value + playerYVelocity * dt
                if (newValHeight <= 0f) {
                    _playerYOffset.value = 0f
                    playerYVelocity = 0f
                    isPlayerJumping = false
                } else {
                    _playerYOffset.value = newValHeight
                }
            }

            // Set vehicle coordinate offset to rest near the player if vehicle was parked
            // Or keep vehicle stationary at its previous parking location.
        } else {
            // VEHICLE RIDING MODE
            // Speed modifications based on engine level upgrade (Level 1: 1.0x, Level 2: 1.3x, Level 3: 1.6x, Level 4: 2.0x+)
            val maxSp = (25f + save.vehicleEngineLevel * 5f)
            val motorAccel = 12f * (1f + save.vehicleEngineLevel * 0.15f)
            val breakingFriction = 5f

            // Steer turning angle using joystick X
            val targetSteer = joypadX * 0.6f
            vehicleAngle += targetSteer * (Math.abs(vehicleSpeed) / maxSp + 0.3f) * 2.5f * dt

            // Speed throttle using joystick Y
            if (Math.abs(joypadY) > 0.1f) {
                vehicleSpeed += -joypadY * motorAccel * dt
                // Limit speed
                vehicleSpeed = vehicleSpeed.coerceIn(-maxSp * 0.4f, maxSp)
            } else {
                // Apply gradual realistic road traction friction deceleration
                vehicleSpeed = if (vehicleSpeed > 0) {
                    (vehicleSpeed - breakingFriction * dt).coerceAtLeast(0f)
                } else {
                    (vehicleSpeed + breakingFriction * dt).coerceAtMost(0f)
                }
            }

            // Drive in the heading angle direction
            val dxV = sin(vehicleAngle) * vehicleSpeed * dt
            val dzV = cos(vehicleAngle) * vehicleSpeed * dt
            save.vehicleX = (save.vehicleX + dxV).coerceIn(-145f, 145f)
            save.vehicleZ = (save.vehicleZ + dzV).coerceIn(-145f, 145f)

            // Keep player locked inside vehicle driver seat
            save.playerX = save.vehicleX
            save.playerZ = save.vehicleZ

            // Smooth drifting tire Smoke generation logic
            if (Math.abs(joypadX) > 0.4f && Math.abs(vehicleSpeed) > 10f) {
                // Drift smokes
                spawnFrictionSmoke(save.vehicleX, save.vehicleZ)
            }

            // Gravity & Jumps for vehicle
            if (isVehicleJumping) {
                vehicleYVelocity -= 16f * dt // gravity
                val newVehHeight = _playerYOffset.value + vehicleYVelocity * dt
                if (newVehHeight <= 0f) {
                    _playerYOffset.value = 0f
                    vehicleYVelocity = 0f
                    isVehicleJumping = false
                    _screenShake.value = 3f // shock impact screen rumble
                    // Spawn dust impact rings!
                    spawnLandingSparks(save.vehicleX, save.vehicleZ)
                } else {
                    _playerYOffset.value = newVehHeight
                }
            }
        }

        // Apply obstacle physical solid collisions
        obstacles.forEach { obstacle ->
            val px = if (save.inVehicle) save.vehicleX else save.playerX
            val pz = if (save.inVehicle) save.vehicleZ else save.playerZ
            val dist = sqrt((px - obstacle.x) * (px - obstacle.x) + (pz - obstacle.z) * (pz - obstacle.z))
            val collRadius = if (save.inVehicle) obstacle.radius + 1.8f else obstacle.radius + 0.8f

            if (dist < collRadius) {
                // Collision response: push outside radius
                val overlap = collRadius - dist
                val pushX = ((px - obstacle.x) / dist) * overlap
                val pushZ = ((pz - obstacle.z) / dist) * overlap

                if (save.inVehicle) {
                    save.vehicleX += pushX
                    save.vehicleZ += pushZ
                    // Damage vehicle/player if crash impact was massive
                    if (Math.abs(vehicleSpeed) > 12f) {
                        val hullDefense = save.vehicleBumperLevel * 0.15f
                        val dmg = (Math.abs(vehicleSpeed) * 1.5f * (1f - hullDefense)).coerceAtLeast(0f)
                        save.hp = (save.hp - dmg).coerceAtLeast(10f) // Keep at least 10 HP to avoid game over loop
                        _screenShake.value = 8f
                        spawnCrashParticles(save.vehicleX, save.vehicleZ)
                    }
                    vehicleSpeed = -vehicleSpeed * 0.4f // Bounce backward speed
                } else {
                    save.playerX += pushX
                    save.playerZ += pushZ
                }
            }
        }

        // --- BOSSES LOGIC, CHASE, ATTACKS & CRASH COLLISION DAMAGE ---
        bosses.forEach { boss ->
            if (boss.hp <= 0) {
                boss.state = "DEAD"
                return@forEach
            }

            // Track distances to player
            val bpx = if (save.inVehicle) save.vehicleX else save.playerX
            val bpz = if (save.inVehicle) save.vehicleZ else save.playerZ
            val dxB = bpx - boss.x
            val dzB = bpz - boss.z
            val dToP = sqrt(dxB * dxB + dzB * dzB)

            // Trigger Chase & Battle UI if close
            if (dToP < boss.targetRange) {
                boss.state = "PURSUING"
                // Rotate and approach player slowly
                val angleToPlayer = Math.atan2(dxB.toDouble(), dzB.toDouble()).toFloat()
                boss.x += sin(angleToPlayer) * 3.5f * dt
                boss.z += cos(angleToPlayer) * 3.5f * dt

                // Boss Attacks timer logic
                if (boss.cooldown > 0) {
                    boss.cooldown--
                } else {
                    // Wind up attack
                    boss.attackTimer++
                    if (boss.attackTimer > 110) { // slam trigger
                        boss.attackTimer = 0
                        boss.cooldown = 180 // Cool down for next attack

                        // Slam radius damage
                        if (dToP < 8f) {
                            if (isShieldBlocking && !save.inVehicle) {
                                // Blocked successfully !
                                spawnImpactShieldSpark(bpx, bpz)
                                _screenShake.value = 4f
                            } else {
                                // Direct crush damage!
                                val hitDmg = if (boss.type == BossType.GOLIATH) 35f else 25f
                                save.hp = (save.hp - hitDmg).coerceAtLeast(10f)
                                _screenShake.value = 12f
                                spawnBloodImpactFeedback(bpx, bpz, "Aïe !")
                            }
                        }
                    }
                }
            } else {
                boss.state = "IDLE"
            }

            // VEHICLE-BELIER RAM IMPACT MECHANIC ON BOSS!
            if (save.inVehicle && Math.abs(vehicleSpeed) > 8f) {
                if (dToP < 4.2f) {
                    // Compute physics-oriented kinetic impact damage
                    // Bumper upgrade Level 1: 1.0x, Level 2: 1.6x, Level 3: 2.2x, Level 4: 3.0x+
                    val bumperFact = 1.0f + (save.vehicleBumperLevel - 1) * 0.6f
                    val ramDamage = Math.abs(vehicleSpeed) * 2.2f * bumperFact
                    boss.hp = (boss.hp - ramDamage).coerceAtLeast(0f)

                    // Impact feedback
                    _screenShake.value = 14f
                    spawnBloodImpactFeedback(boss.x, boss.z, "-${ramDamage.toInt()} !", Color(0xFFFF5252))
                    spawnCrashParticles(boss.x, boss.z, Color(0xFFFF9800), count = 25)

                    // Bounce vehicle backward on high inertia
                    vehicleSpeed = -vehicleSpeed * 0.3f

                    if (boss.hp <= 0) {
                        boss.state = "DEAD"
                        save.addDefeatedBoss(boss.id)
                        save.rupees += 150
                        // Bestow epic rewards to inventory!
                        val lootType = if (boss.type == BossType.GOLIATH) ItemType.COEUR else ItemType.BATTERIE
                        val inv = save.getInventoryMap().toMutableMap()
                        inv[lootType.name] = (inv[lootType.name] ?: 0) + 1
                        save.setInventoryMap(inv)
                        spawnBloodImpactFeedback(boss.x, boss.z, "BOSS VAINCU !", Color(0xFF4CAF50))
                    }
                }
            }
        }

        // --- PROJECTILES FOR SENTINEL ---
        // Let's spawn shooting projectiles dynamically from Sentinel to player
        bosses.find { it.id == "BOSS_SENTINELLE" && it.state == "PURSUING" }?.let { sent ->
            val tickRoll = (0..200).random()
            if (tickRoll < 4 && sent.hp > 0) { // 2% chance each frame
                // Shoot ancient projectile!
                spawnProjectileParticle(sent.x, sent.z)
            }
        }

        // Update active particles list
        val iterator = particles.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            // Translate particles with acceleration velocities
            p.alpha -= p.decay
            if (p.alpha <= 0f) {
                // Remove out
                particles.remove(p)
                break
            } else {
                // Apply update
                p.alpha = p.alpha.coerceIn(0f, 1f)
            }
        }

        _playerYOffset.update { _playerYOffset.value } // Keep aligned
    }

    private fun updateTimeAndQuest() {
        // Cyclic celestial daylight progression
        _timeOfDay.update { (it + 0.005f) % 24f }

        // Dynamic quest state tracking triggers
        val save = _gameSave.value
        val inv = save.getInventoryMap()

        if (save.currentQuestId == "REVEIL") {
            if (inv.containsKey(ItemType.BATTERIE.name)) {
                _gameSave.update {
                    it.currentQuestId = "REVEIL_BATTERIE_READY"
                    it
                }
            }
        }

        if (save.currentQuestId == "REVEIL_BATTERIE_READY" && save.questStep == 1) {
            _gameSave.update {
                it.currentQuestId = "BOSS_HUNT"
                it
            }
        }

        // Quest completion checks
        if (save.currentQuestId == "BOSS_HUNT") {
            if (save.isBossDefeated("BOSS_GOLIATH") && save.isBossDefeated("BOSS_SENTINELLE")) {
                _gameSave.update {
                    it.currentQuestId = "WORLD_SAVED"
                    it
                }
            }
        }
    }

    // --- DIALOGUE TRIGGER AND STORY PROGRESSION ---
    fun openNpcDialogue(npcId: String) {
        val save = _gameSave.value
        val nodeKey = when (npcId) {
            "NPC_GEDEON" -> if (save.currentQuestId == "WORLD_SAVED") "GE_DONE" else "GE_HELLO"
            "NPC_ARCHIBALD" -> "AR_HELLO"
            "NPC_CELESTE" -> "CE_HELLO"
            else -> "GE_HELLO"
        }
        _activeDialogueNode.value = dialogueDb[nodeKey]
    }

    fun selectDialogueOption(option: DialogueOption) {
        val save = _gameSave.value

        // Execute action triggers embedded inside choices
        when (option.action) {
            "CRAFT_OPEN" -> {
                _isCraftMenuOpen.value = true
                _activeDialogueNode.value = null
            }
            "PAINT_OPEN" -> {
                _isCustomizationMenuOpen.value = true
                _activeDialogueNode.value = null
            }
            "COMPLETE_GE_QUEST" -> {
                // Dedicate battery
                val invMap = save.getInventoryMap().toMutableMap()
                val currentBatteryTypeQty = invMap[ItemType.BATTERIE.name] ?: 0
                if (currentBatteryTypeQty > 0) {
                    invMap[ItemType.BATTERIE.name] = currentBatteryTypeQty - 1
                }
                save.setInventoryMap(invMap)
                save.currentQuestId = "BOSS_HUNT"
                save.questStep = 1 // Step complete
                save.vehicleThrusterLevel = 2 // unlocks boost
                _gameSave.value = save
                triggerSave()
                _activeDialogueNode.value = null
                spawnBloodImpactFeedback(save.playerX, save.playerZ, "BOOSTER ACTIVÉ !", Color(0xFF2196F3))
            }
        }

        if (option.targetNodeId == "CLOSE") {
            _activeDialogueNode.value = null
        } else {
            // Traverse node
            _activeDialogueNode.value = dialogueDb[option.targetNodeId]
        }
    }

    fun closeDialogue() {
        _activeDialogueNode.value = null
    }

    // --- PICKUP ITEM MANUALLY OR AUTO---
    fun pickupItem(item: GameItem) {
        val save = _gameSave.value
        val inv = save.getInventoryMap().toMutableMap()
        inv[item.type.name] = (inv[item.type.name] ?: 0) + 1
        save.setInventoryMap(inv)
        item.isPicked = true

        // Add visual particle sparks
        spawnBloodImpactFeedback(item.x, item.z, "+1 ${item.type.displayName} !", Color(0xFF4CAF50))
        spawnLandingSparks(item.x, item.z, Color(0xFF4CAF50))

        // Trigger save
        _gameSave.value = save
        triggerSave()
    }

    // --- CRAFT MECHANICS ---
    fun craftItem(recipe: CraftRecipe): Boolean {
        val save = _gameSave.value
        val inv = save.getInventoryMap().toMutableMap()

        // Check availability
        for ((reqType, qty) in recipe.cost) {
            val hasQty = inv[reqType.name] ?: 0
            if (hasQty < qty) return false // Insufficient funds
        }

        // Consume ingredients
        for ((reqType, qty) in recipe.cost) {
            inv[reqType.name] = (inv[reqType.name] ?: 0) - qty
        }

        // Apply results
        when (recipe.outputAction) {
            "POTION_HEAL" -> {
                save.hp = save.maxHp
                spawnBloodImpactFeedback(save.playerX, save.playerZ, "POTION CRÉÉE : VIE MAX !", Color(0xFF4CAF50))
            }
            "SWORD_IRON" -> {
                save.activeWeapon = "Épée en Fer"
                spawnBloodImpactFeedback(save.playerX, save.playerZ, "NOUVELLE ÉPÉE FORGÉE !", Color(0xFFFFC107))
            }
            "SWORD_ROYAL" -> {
                save.activeWeapon = "Épée Royale"
                spawnBloodImpactFeedback(save.playerX, save.playerZ, "ARME LÉGENDAIRE OBTENUE !", Color(0xFF9C27B0))
            }
            "SHIELD_HEAVY" -> {
                save.activeShield = "Grand Bouclier d'Acier"
                spawnBloodImpactFeedback(save.playerX, save.playerZ, "LOURD BOUCLIER OBTENU !", Color(0xFF3F51B5))
            }
            "VEHICLE_ENGINE" -> {
                save.vehicleEngineLevel += 1
                spawnBloodImpactFeedback(save.playerX, save.playerZ, "MOTEUR TURBO AMÉLIORÉ !", Color(0xFFFF5722))
            }
            "VEHICLE_BUMPER" -> {
                save.vehicleBumperLevel += 1
                spawnBloodImpactFeedback(save.playerX, save.playerZ, "BLINDAGE PARE-CHOC AMÉLIORÉ !", Color(0xFF795548))
            }
        }

        save.setInventoryMap(inv)
        _gameSave.value = save
        triggerSave()
        return true
    }

    fun closeCraftMenu() {
        _isCraftMenuOpen.value = false
    }

    // --- CAR TUNING GARAGE ---
    fun changeVehicleColor(hex: String) {
        val save = _gameSave.value
        save.vehicleColorHex = hex
        _gameSave.value = save
        triggerSave()
        spawnLandingSparks(save.vehicleX, save.vehicleZ, Color(android.graphics.Color.parseColor(hex)))
    }

    fun closeCustomizationMenu() {
        _isCustomizationMenuOpen.value = false
    }

    // --- RIDING VEHICLE MOUNT / DISMOUNT ---
    fun toggleVehicleRiding() {
        val save = _gameSave.value
        if (!save.inVehicle) {
            // Must be near the parked vehicle to ride
            val dist = sqrt((save.playerX - save.vehicleX) * (save.playerX - save.vehicleX) + (save.playerZ - save.vehicleZ) * (save.playerZ - save.vehicleZ))
            if (dist < 6f) {
                save.inVehicle = true
                _gameSave.value = save
                triggerSave()
                spawnBloodImpactFeedback(save.vehicleX, save.vehicleZ, "Moteur Démarré 💨", Color(0xFFFF9800))
            } else {
                spawnBloodImpactFeedback(save.playerX, save.playerZ, "Trop loin du véhicule !", Color(0xFFFFC107))
            }
        } else {
            // Eject driver
            save.inVehicle = false
            _gameSave.value = save
            triggerSave()
            spawnBloodImpactFeedback(save.vehicleX, save.vehicleZ, "Véhicule Garé 🅿️")
        }
    }

    // --- ACTIONS GESTURES TRRIGERS ---
    fun triggerPlayerJump() {
        val save = _gameSave.value
        if (save.inVehicle) {
            if (save.vehicleThrusterLevel < 2) {
                spawnBloodImpactFeedback(save.vehicleX, save.vehicleZ, "Booster verrouillé ! Gédéon te dira pourquoi..", Color(0xFFFF9800))
                return
            }
            if (_stamina.value > 25f && !isVehicleJumping) {
                isVehicleJumping = true
                vehicleYVelocity = 14f // launch speed
                _stamina.update { (it - 25f).coerceAtLeast(0f) }
                spawnLandingSparks(save.vehicleX, save.vehicleZ, Color(0xFF00E676))
            }
        } else {
            if (!isPlayerJumping) {
                isPlayerJumping = true
                playerYVelocity = 11f // jump speed
                spawnLandingSparks(save.playerX, save.playerZ)
            }
        }
    }

    fun triggerSwordAttack() {
        if (isAttacking) return
        isAttacking = true
        attackCooldownTicks = 20

        // Check if hitting any items (like hitting metallic iron veins compiles damage to harvest)
        val save = _gameSave.value
        itemsInWorld.forEach { item ->
            if (!item.isPicked) {
                val dx = item.x - save.playerX
                val dz = item.z - save.playerZ
                val d = sqrt(dx*dx + dz*dz)
                if (d < 3.8f) {
                    pickupItem(item)
                }
            }
        }

        // Damage Boss with physical sword hit !
        bosses.forEach { boss ->
            if (boss.hp > 0) {
                val dxB = boss.x - save.playerX
                val dzB = boss.z - save.playerZ
                val dToB = sqrt(dxB*dxB + dzB*dzB)
                if (dToB < 4.5f) {
                    val finalDmg = activeWeaponDamage
                    boss.hp = (boss.hp - finalDmg).coerceAtLeast(0f)
                    _screenShake.value = 5f
                    spawnBloodImpactFeedback(boss.x, boss.z, "-${finalDmg.toInt()} 🔥", Color(0xFFFFEB3B))
                    spawnCrashParticles(boss.x, boss.z, Color(0xFFFFEB3B), count = 12)

                    if (boss.hp <= 0) {
                        boss.state = "DEAD"
                        save.addDefeatedBoss(boss.id)
                        save.rupees += 150
                        // Bestow epic material
                        val lootType = if (boss.type == BossType.GOLIATH) ItemType.COEUR else ItemType.BATTERIE
                        val inv = save.getInventoryMap().toMutableMap()
                        inv[lootType.name] = (inv[lootType.name] ?: 0) + 1
                        save.setInventoryMap(inv)
                        spawnBloodImpactFeedback(boss.x, boss.z, "BOSS DÉTRUIT !", Color(0xFF4CAF50))
                    }
                }
            }
        }
    }

    // --- RETRO PHYSICAL DYNAMIC PARTICLE SPAWNERS ---
    private fun spawnCrashParticles(x: Float, z: Float, color: Color = Color.LightGray, count: Int = 15) {
        val y = getTerrainHeight(x, z)
        for (i in 0 until count) {
            val vx = (-5..5).random() * 0.4f
            val vy = (2..12).random() * 0.4f
            val vz = (-5..5).random() * 0.4f
            particles.add(Particle(x, y + 0.5f, z, vx, vy, vz, color, (4..10).random().toFloat()))
        }
    }

    private fun spawnFrictionSmoke(x: Float, z: Float) {
        val y = getTerrainHeight(x, z)
        val roll = (0..5).random()
        if (roll == 0) {
            particles.add(
                Particle(
                    x = x + (-1..1).random() * 0.2f,
                    y = y + 0.1f,
                    z = z + (-1..1).random() * 0.2f,
                    vx = (-2..2).random() * 0.05f,
                    vy = (1..3).random() * 0.05f,
                    vz = (-2..2).random() * 0.05f,
                    color = Color.LightGray.copy(alpha = 0.5f),
                    size = (15..30).random().toFloat(),
                    decay = 0.04f
                )
            )
        }
    }

    private fun spawnLandingSparks(x: Float, z: Float, color: Color = Color(0xFFFFF176)) {
        val y = getTerrainHeight(x, z)
        for (i in 0 until 12) {
            particles.add(
                Particle(
                    x, y + 0.1f, z,
                    vx = sin(i * 0.5f) * 2f,
                    vy = 1.5f,
                    vz = cos(i * 0.5f) * 2f,
                    color = color,
                    size = 5f,
                    decay = 0.06f
                )
            )
        }
    }

    private fun spawnProjectileParticle(sentX: Float, sentZ: Float) {
        val targetX = _gameSave.value.playerX
        val targetZ = _gameSave.value.playerZ
        val yS = getTerrainHeight(sentX, sentZ) + 4f
        val dx = targetX - sentX
        val dz = targetZ - sentZ
        val dist = sqrt(dx*dx + dz*dz)
        if (dist == 0f) return

        // Spawn flying dangerous energy ball particle that moves towards player
        particles.add(
            Particle(
                x = sentX,
                y = yS,
                z = sentZ,
                vx = (dx / dist) * 15f,
                vy = -1.5f,
                vz = (dz / dist) * 15f,
                color = Color(0xFFFF3D00), // Fire Crimson Orb
                size = 18f,
                decay = 0.015f // travel long distance before fade
            )
        )
    }

    private fun spawnBloodImpactFeedback(x: Float, z: Float, message: String, color: Color = Color.White) {
        val y = getTerrainHeight(x, z) + 3f
        particles.add(
            Particle(
                x = x,
                y = y,
                z = z,
                vx = 0f,
                vy = 3f, // floats upwards !
                vz = 0f,
                color = color,
                size = 0f,
                decay = 0.03f,
                text = message
            )
        )
    }

    private fun spawnImpactShieldSpark(x: Float, z: Float) {
        val y = getTerrainHeight(x, z) + 1.5f
        for (i in 0..10) {
            particles.add(
                Particle(
                    x, y, z,
                    vx = (-6..6).random() * 0.3f,
                    vy = (2..8).random() * 0.3f,
                    vz = (-6..6).random() * 0.3f,
                    color = Color(0xFF00E5FF),
                    size = 8f
                )
            )
        }
    }
}
