package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "game_save")
data class GameSaveEntity(
    @PrimaryKey val id: Int = 1,
    var playerName: String = "Linka",
    var hp: Float = 100f,
    var maxHp: Float = 100f,
    var playerX: Float = 0f,
    var playerZ: Float = -20f,
    
    // Vehicle positions and state
    var vehicleX: Float = 15f,
    var vehicleZ: Float = 20f,
    var inVehicle: Boolean = false,
    
    // Vehicle customizations
    var vehicleColorHex: String = "#FF3366", // Color customization
    var vehicleEngineLevel: Int = 1,         // Speed modification
    var vehicleBumperLevel: Int = 1,         // Shield / Impact damage resistance
    var vehicleThrusterLevel: Int = 1,       // Jump / Nitro booster upgrade
    
    // Inventory serialized as "item1:qty,item2:qty"
    var inventoryData: String = "bois:2,fer:1,herbe:3",
    
    // Equipped gear
    var activeWeapon: String = "Épée Rouillée",
    var activeShield: String = "Bouclier de Fortune",
    var rupees: Int = 100,
    
    // Progress and bosses defeated e.g., "Boss1,Boss2"
    var defeatedBosses: String = "",
    
    // Active story/quests tracking
    var currentQuestId: String = "REVEIL",
    var questStep: Int = 0,
    
    // NPC relationships / dialogue states spoken to
    var npcStates: String = "Archi:0,Celeste:0,Gedeon:0"
) {
    // Helper to get inventory Map
    fun getInventoryMap(): Map<String, Int> {
        if (inventoryData.isBlank()) return emptyMap()
        return try {
            inventoryData.split(",").associate {
                val parts = it.split(":")
                parts[0] to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // Helper to save inventory Map to string
    fun setInventoryMap(map: Map<String, Int>) {
        inventoryData = map.filter { it.value > 0 }
            .map { "${it.key}:${it.value}" }
            .joinToString(",")
    }

    // Check if boss defeated
    fun isBossDefeated(bossId: String): Boolean {
        if (defeatedBosses.isBlank()) return false
        return defeatedBosses.split(",").contains(bossId)
    }

    // Set boss as defeated
    fun addDefeatedBoss(bossId: String) {
        val list = if (defeatedBosses.isBlank()) emptyList() else defeatedBosses.split(",")
        if (!list.contains(bossId)) {
            defeatedBosses = (list + bossId).joinToString(",")
        }
    }
}
