package com.example.data.model

import androidx.compose.ui.graphics.Color

enum class ItemType(val displayName: String, val icon: String, val baseValue: Int) {
    HERBE("Herbe Sauvage", "🌿", 5),
    FER("Minerai de Fer", "🪨", 15),
    BOIS("Bois Flotté", "🪵", 5),
    BATTERIE("Batterie Ancienne", "🔋", 40),
    RUBIS("Rubis Brut", "💎", 50),
    COEUR("Cœur de Golem", "❤️", 100),
}

data class GameItem(
    val id: String,
    val type: ItemType,
    val x: Float,
    val y: Float,
    val z: Float,
    var isPicked: Boolean = false,
    val respawnTimeMs: Long = 20000 // Respawn after 20 seconds
)

enum class BossType {
    GOLIATH,  // Stone Talus colossus
    SENTINELLE // Robotic Mech sentinel
}

data class GameBoss(
    val id: String,
    val type: BossType,
    val name: String,
    var x: Float,
    var y: Float,
    var z: Float,
    var hp: Float,
    val maxHp: Float,
    var state: String = "IDLE", // IDLE, PERSUING, CHARGING, DEAD
    var targetRange: Float = 40f,
    var cooldown: Int = 0,
    var attackTimer: Int = 0
)

data class Particle(
    val x: Float,
    val y: Float,
    val z: Float,
    val vx: Float,
    val vy: Float,
    val vz: Float,
    val color: Color,
    val size: Float,
    var alpha: Float = 1.0f,
    val decay: Float = 0.05f,
    val text: String? = null // For floating damage numbers
)

data class CraftRecipe(
    val id: String,
    val name: String,
    val resultIcon: String,
    val resultDescription: String,
    val cost: Map<ItemType, Int>,
    val outputAction: String // SWORD_IRON, SWORD_ROYAL, SHIELD_HEAVY, POTION_HEAL, VEHICLE_ENGINE, VEHICLE_BUMPER, VEHICLE_BOOSTER
)

data class DialogueOption(
    val text: String,
    val targetNodeId: String,
    val requiredQuestId: String? = null,
    val action: String? = null // CRAFT_OPEN, PAINT_OPEN, COMPLETE_QUEST
)

data class DialogueNode(
    val id: String,
    val npcName: String,
    val text: String,
    val options: List<DialogueOption>
)
