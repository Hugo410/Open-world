package com.example.data.repository

import com.example.data.database.GameSaveDao
import com.example.data.database.GameSaveEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class GameRepository(private val gameSaveDao: GameSaveDao) {

    val gameSaveState: Flow<GameSaveEntity?> = gameSaveDao.getGameSaveState()
        .flowOn(Dispatchers.IO)

    suspend fun saveGame(gameSave: GameSaveEntity) {
        withContext(Dispatchers.IO) {
            gameSaveDao.saveGame(gameSave)
        }
    }

    suspend fun createInitialState(): GameSaveEntity {
        val initial = GameSaveEntity()
        saveGame(initial)
        return initial
    }

    suspend fun clearGame() {
        withContext(Dispatchers.IO) {
            gameSaveDao.clearGameSave()
        }
    }
}
