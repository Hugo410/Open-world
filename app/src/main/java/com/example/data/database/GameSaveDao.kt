package com.example.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface GameSaveDao {
    @Query("SELECT * FROM game_save WHERE id = 1 LIMIT 1")
    fun getGameSaveState(): Flow<GameSaveEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveGame(gameSave: GameSaveEntity)

    @Query("DELETE FROM game_save WHERE id = 1")
    suspend fun clearGameSave()
}
