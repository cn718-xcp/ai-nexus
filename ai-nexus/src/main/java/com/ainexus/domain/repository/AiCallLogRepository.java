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
}
