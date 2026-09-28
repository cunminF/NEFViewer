package com.nefviewer.android.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(project: ProjectEntity)

    @Query("UPDATE projects SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface PhotoDao {
    @Query("SELECT * FROM photos WHERE projectId = :projectId")
    suspend fun byProject(projectId: String): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE projectId = :projectId")
    fun observeByProject(projectId: String): Flow<List<PhotoEntity>>

    @Query("SELECT COUNT(*) FROM photos WHERE projectId = :projectId")
    fun observeCountByProject(projectId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM photos WHERE projectId = :projectId")
    suspend fun countByProject(projectId: String): Int

    @Query("SELECT projectId AS projectId, COUNT(*) AS cnt FROM photos GROUP BY projectId")
    fun observeCounts(): Flow<List<ProjectCount>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(photos: List<PhotoEntity>)

    @Query("UPDATE photos SET rating = :rating WHERE id = :id")
    suspend fun setRating(id: String, rating: Int)

    @Query("UPDATE photos SET rating = :rating WHERE id IN (:ids)")
    suspend fun setRatingBatch(ids: List<String>, rating: Int)

    @Query("SELECT * FROM photos WHERE id = :id")
    suspend fun get(id: String): PhotoEntity?

    @Query("DELETE FROM photos WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: String)

    @Query("DELETE FROM photos WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}
