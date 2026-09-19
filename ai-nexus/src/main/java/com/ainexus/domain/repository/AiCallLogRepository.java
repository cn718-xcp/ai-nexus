package com.ainexus.domain.repository;

import com.ainexus.domain.entity.AiCallLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AiCallLogRepository extends JpaRepository<AiCallLog, Long> {

    /** 按场景+日期统计调用次数与平均耗时 */
    @Query("SELECT l.scene, l.callDate, COUNT(l), AVG(l.costMs) FROM AiCallLog l GROUP BY l.scene, l.callDate ORDER BY l.callDate DESC")
    List<Object[]> statBySceneAndDate();

    /** 按模型统计 token 用量 */
    @Query("SELECT l.modelName, COUNT(l), SUM(l.promptTokens), SUM(l.completionTokens), SUM(CASE WHEN l.success = 0 THEN 1 ELSE 0 END) " +
            "FROM AiCallLog l GROUP BY l.modelName ORDER BY COUNT(l) DESC")
    List<Object[]> statByModel();

    /** 用户隔离版统计（鉴权开启时使用） */
    @Query("SELECT l.scene, l.callDate, COUNT(l), AVG(l.costMs) FROM AiCallLog l WHERE l.userId = :userId " +
            "GROUP BY l.scene, l.callDate ORDER BY l.callDate DESC")
    List<Object[]> statBySceneAndDateForUser(@Param("userId") Long userId);

    @Query("SELECT l.modelName, COUNT(l), SUM(l.promptTokens), SUM(l.completionTokens), SUM(CASE WHEN l.success = 0 THEN 1 ELSE 0 END) " +
            "FROM AiCallLog l WHERE l.userId = :userId GROUP BY l.modelName ORDER BY COUNT(l) DESC")
    List<Object[]> statByModelForUser(@Param("userId") Long userId);

    /** 租户维度统计（多租户开启时使用，含租户内全部用户） */
    @Query("SELECT l.scene, l.callDate, COUNT(l), AVG(l.costMs) FROM AiCallLog l WHERE l.tenantId = :tenantId " +
            "GROUP BY l.scene, l.callDate ORDER BY l.callDate DESC")
    List<Object[]> statBySceneAndDateForTenant(@Param("tenantId") Long tenantId);

    @Query("SELECT l.modelName, COUNT(l), SUM(l.promptTokens), SUM(l.completionTokens), SUM(CASE WHEN l.success = 0 THEN 1 ELSE 0 END) " +
            "FROM AiCallLog l WHERE l.tenantId = :tenantId GROUP BY l.modelName ORDER BY COUNT(l) DESC")
    List<Object[]> statByModelForTenant(@Param("tenantId") Long tenantId);
}
